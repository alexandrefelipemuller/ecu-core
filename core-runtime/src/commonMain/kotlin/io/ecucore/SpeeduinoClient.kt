package io.ecucore

import kotlin.concurrent.Volatile

import io.ecucore.definition.IniDefinition
import io.ecucore.definition.IniFieldKind
import io.ecucore.ecu.FirmwareConsensus
import io.ecucore.ecu.FirmwareHandshakeDomain
import io.ecucore.ecu.FirmwareInfo
import io.ecucore.shared.Logger
import io.ecucore.cache.EcuConfigPageCache
import io.ecucore.cache.NoopEcuConfigPageCache
import io.ecucore.connection.ConnectionTrace
import io.ecucore.connection.ISpeeduinoConnection
import io.ecucore.model.FirmwareEra
import io.ecucore.model.EngineConstants
import io.ecucore.model.Algorithm
import io.ecucore.model.EcuCapabilities
import io.ecucore.model.EcuDefinition
import io.ecucore.model.EcuDefinitionRegistry
import io.ecucore.model.EcuFamily
import io.ecucore.model.EcuConfigReadMode
import io.ecucore.model.OutputField
import io.ecucore.model.SpeeduinoOutputChannels
import io.ecucore.model.SpeeduinoTableDefinitions
import io.ecucore.model.EngineProtectionConfig
import io.ecucore.model.EngineProtectionMapper
import io.ecucore.model.ClosedLoopCorrectionConfig
import io.ecucore.model.ClosedLoopCorrectionMapper
import io.ecucore.model.MegaSpeedIniTableDefinitions
import io.ecucore.model.Ms2TableDefinitions
import io.ecucore.model.PinLayoutDetector
import io.ecucore.model.PinLayoutInfo
import io.ecucore.model.TableDefinitions
import io.ecucore.model.TableMetadata
import io.ecucore.model.TableValidator
import io.ecucore.model.UnsupportedFirmwareException
import io.ecucore.model.ValidationException
import io.ecucore.model.SecondarySerialConfig
import io.ecucore.model.IgnitionTable
import io.ecucore.model.AfrTable
import io.ecucore.model.DwellTable
import io.ecucore.model.IdleControlSettings
import io.ecucore.model.Ms3TableDefinitions
import io.ecucore.model.PressureCalibration
import io.ecucore.model.RusefiF407DiscoveryDefinitions
import io.ecucore.model.RusefiIniTableDefinitions
import io.ecucore.model.RusefiTableDefinitions
import io.ecucore.model.RusefiInputOutputConfig
import io.ecucore.model.RusefiInputOutputSnapshot
import io.ecucore.model.RusefiIniUiParsers
import io.ecucore.model.SpeeduinoIniDefinitions
import io.ecucore.transport.MapSelectionSupport
import io.ecucore.model.TpsCalibration
import io.ecucore.model.VeTable
import io.ecucore.model.TriggerSettings
import io.ecucore.model.afrTableLoadType
import io.ecucore.model.fuelTableLoadType
import io.ecucore.model.ignitionTableLoadType
import io.ecucore.protocol.SerialCapability
import io.ecucore.protocol.SpeeduinoProtocol
import io.ecucore.tables.TableDomainFacade
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.ecucore.shared.MonotonicClock
import io.ecucore.shared.formatDecimal
import io.ecucore.shared.toHex02

/**
 * Cliente principal para comunicação com Speeduino ECU
 *
 * Fachada de alto nível que combina:
 * - Camada de conexão (TCP, Bluetooth, USB, etc)
 * - Camada de protocolo (comandos Legacy e Modern)
 * - Stream contínuo de dados em tempo real
 *
 * Exemplo de uso:
 * ```
 * val connection = SpeeduinoTcpConnection("10.0.2.2", 5555)
 * val client = SpeeduinoClient(connection, onDataReceived, onConnectionStateChanged, onError)
 *
 * client.connect()
 * client.startLiveDataStream()
 * ```
 */
class SpeeduinoClient(
    private val connection: ISpeeduinoConnection,
    private val onDataReceived: (SpeeduinoLiveData) -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val pageCache: EcuConfigPageCache = NoopEcuConfigPageCache,
    private val pageCacheTtlMs: Long = PAGE_CACHE_TTL_MS,
) : io.ecucore.transport.EcuTransport {
    companion object {
        private const val TAG = "SpeeduinoClient"
        private const val FIRMWARE_HANDSHAKE_DRAIN_DELAY_MS = 80L
        private const val FIRMWARE_HANDSHAKE_RETRY_DELAY_MS = 80L
        private const val LIVE_DATA_FAULT_REPORT_INTERVAL_MS = 30_000L
        private const val LIVE_DATA_FAULT_CONSECUTIVE_THRESHOLD = 3
        private const val LIVE_DATA_FAULT_RECOVERY_INTERVAL_MS = 10_000L
        private const val LIVE_DATA_STREAM_WARMUP_MS = 10_000L
        private const val LIVE_STREAM_RECOVERABLE_TIMEOUT_LIMIT = 3
        // Piso de espera entre comandos do live stream. Sem ele, quando uma leitura estoura o
        // intervalo alvo o ticker realinha o relógio e emite o comando seguinte colado no
        // anterior — num link Bluetooth lento isso vira uma rajada contínua sobre a ECU.
        private const val LIVE_STREAM_MIN_COMMAND_GAP_MS = 25L
        private const val CONFIG_CHUNK_READ_MAX_ATTEMPTS = 2
        private const val CONFIG_CHUNK_READ_RETRY_DELAY_MS = 40L
        private const val RUSEFI_CONFIG_READ_CHUNK_SIZE = 64
        private const val RUSEFI_RECONNECT_SETTLE_DELAY_MS = 3_000L
        private const val GLOBAL_RECONNECT_SETTLE_DELAY_MS = 1_000L
        // TTL padrão do cache de páginas de configuração: 30 dias. Seguro porque um download
        // completo explícito ("Sincronizar") sempre invalida o cache antes de ler
        // (ver invalidateConfigPageCache()/ConfigManager.downloadAllConfigs) — o TTL só governa
        // leituras avulsas sob demanda (ex.: abrir uma tela de config individual).
        const val PAGE_CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000
        private val PARTIAL_TIMEOUT_REGEX = Regex("""Timeout: expected (\d+) bytes, received (\d+)""")
        // Lançada por SpeeduinoProtocol.readLiveData() quando a resposta legacy vem truncada.
        private val INCOMPLETE_LIVE_DATA_REGEX = Regex("""Resposta de live data incompleta: (\d+)/(\d+)""")

        @Volatile
        private var lastGlobalDisconnectWasActive: Boolean = false

        @Volatile
        private var lastGlobalDisconnectAtMs: Long = 0L
    }

    private val protocol = SpeeduinoProtocol(connection)

    private var _isStreaming = false
    private var streamJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Firmware info and table definitions (set after connect)
    private var firmwareInfo: FirmwareInfo? = null
    private var ecuDefinition: EcuDefinition? = null
    private var tableDefinitions: TableDefinitions? = null
    private var outputChannelFields: List<OutputField>? = null
    private var activeIniDefinition: IniDefinition? = null
    private var speeduinoIniCatalog: SpeeduinoIniDefinitions.Catalog? = null
    private var megaSpeedIniCatalog: MegaSpeedIniTableDefinitions.Catalog? = null
    private var rusefiIniCatalog: RusefiIniTableDefinitions.Catalog? = null
    private var pendingRusefiVeTableReadback: VeTable? = null
    private var pendingRusefiIgnitionTableReadback: IgnitionTable? = null
    private val pendingSpeeduinoPageReadbacks = mutableMapOf<Int, ByteArray>()
    private var lastDisconnectWasRusefi: Boolean = false
    private var lastDisconnectAtMs: Long = 0L
    private var cachedEngineConstants: EngineConstants? = null
    private var pinLayoutInfo: PinLayoutInfo? = null
    private val connectMutex = Mutex()
    private var liveDataSampleCounter = 0
    private var consecutiveFaultyLiveDataSamples = 0
    private var manualFirmwareProfile: String? = null
    private var readOnlySafeModeEnabled = false
    private var lastLiveDataStreamStartedAtMs = 0L
    private var pendingLiveDataRecoveryReason: String? = null
    private var lastFaultRecoveryAtMs = 0L
    @Volatile
    private var liveDataStreamStopRequested = false
    @Volatile
    private var forceLegacyFirmwareHandshakeAfterModernDisconnect = false

    init {
        connection.setOnConnectionStateChanged(onConnectionStateChanged)
        connection.setOnError(onError)
    }

    /**
     * Conecta ao Speeduino e valida firmware
     *
     * @throws UnsupportedFirmwareException se firmware não for suportado
     */
    override suspend fun connect() {
        connectMutex.withLock {
            if (connection.isConnected()) {
                Logger.d(TAG, "Conexao ja estabelecida, ignorando novo connect")
                return
            }

            // 1. Estabelecer conexão física
            connection.connect()
            val shouldSettleAfterRusefiDisconnect = lastDisconnectWasRusefi || lastGlobalDisconnectWasActive
            if (shouldSettleAfterRusefiDisconnect) {
                val settleDelayMs = (MonotonicClock.nowMillis() - maxOf(lastDisconnectAtMs, lastGlobalDisconnectAtMs)).let { elapsed ->
                    if (elapsed < RUSEFI_RECONNECT_SETTLE_DELAY_MS) {
                        RUSEFI_RECONNECT_SETTLE_DELAY_MS - elapsed
                    } else if (elapsed < GLOBAL_RECONNECT_SETTLE_DELAY_MS) {
                        GLOBAL_RECONNECT_SETTLE_DELAY_MS - elapsed
                    } else {
                        0L
                    }
                }
                if (settleDelayMs > 0L) {
                    Logger.d(TAG, "Aguardando $settleDelayMs ms para assentamento rusEFI após disconnect")
                    delay(settleDelayMs)
                }
                lastDisconnectWasRusefi = false
                lastGlobalDisconnectWasActive = false
            }

            // 2. Obter informações do firmware
            // Legacy serial (USB/BT/desktop serial) fica mais estável com handshake simples
            // como no fluxo antigo (uma assinatura válida já é suficiente).
            val forcedLegacyHandshake = forceLegacyFirmwareHandshakeAfterModernDisconnect
            val useLegacyHandshakeCore = shouldUseLegacyHandshakeCore()
            val firmwareSamples: List<String>
            val firmwareConsensus: FirmwareConsensus?
            val detectedFirmwareSignature = if (useLegacyHandshakeCore) {
                firmwareSamples = try {
                    readLegacyFirmwareSignatureSamples()
                } catch (legacyError: Exception) {
                    if (connection.supportsModernProtocolFallback()) {
                        Logger.w(TAG, "Legacy handshake falhou, tentando modern fallback: ${legacyError.message}")
                        listOf(protocol.getFirmwareInfo())
                    } else {
                        throw legacyError
                    }
                }
                firmwareConsensus = resolveFirmwareConsensus(firmwareSamples)
                firmwareConsensus.signature
                    ?: firmwareSamples.lastOrNull()
                    ?: "Unknown"
            } else {
                firmwareSamples = readFirmwareSignatureSamples()
                firmwareConsensus = resolveFirmwareConsensus(firmwareSamples)
                firmwareConsensus.signature
                    ?: firmwareSamples.lastOrNull()
                    ?: "Unknown"
            }
            val effectiveFirmwareSignature = manualFirmwareProfile ?: detectedFirmwareSignature

            val productString = if (forcedLegacyHandshake) {
                Logger.w(TAG, "Pulando product string modern após queda no handshake modern anterior")
                "Unknown"
            } else {
                try {
                    protocol.getProductString()
                } catch (e: Exception) {
                    Logger.w(TAG, "Não foi possível obter product string: ${e.message}")
                    "Unknown"
                }
            }

            Logger.d(TAG, "Firmware detectado: $detectedFirmwareSignature")
            Logger.d(TAG, "Product string: $productString")
            if (effectiveFirmwareSignature.startsWith("rusEFI", ignoreCase = true)) {
                drainInputBufferQuietly()
            }
            if (useLegacyHandshakeCore) {
                val reason = if (forcedLegacyHandshake) {
                    "apos queda no handshake modern anterior"
                } else {
                    "para transporte sem modern protocol"
                }
                Logger.d(TAG, "Legacy handshake core ativo $reason")
            }
            if (!useLegacyHandshakeCore) {
                forceLegacyFirmwareHandshakeAfterModernDisconnect = false
            }
            if (manualFirmwareProfile != null) {
                Logger.w(
                    TAG,
                    "⚠️ Usando perfil manual de firmware: $manualFirmwareProfile (safe mode read-only)"
                )
            }

            // 3. Validar compatibilidade e carregar definitions
            try {
                if (manualFirmwareProfile == null) {
                    validateFirmwareConsensus(firmwareConsensus, firmwareSamples)
                }
                val resolvedDefinition = EcuDefinitionRegistry.resolve(
                    signature = effectiveFirmwareSignature,
                    productString = productString
                )
                val definitions = resolvedDefinition.tableDefinitions
                val era = when (resolvedDefinition.family) {
                    EcuFamily.SPEEDUINO -> SpeeduinoTableDefinitions.detectFirmwareEra(effectiveFirmwareSignature)
                    EcuFamily.MS2 -> FirmwareEra.MS2
                    EcuFamily.MEGASPEED -> FirmwareEra.MS2
                    EcuFamily.MS3 -> FirmwareEra.MS3
                    EcuFamily.RUSEFI -> FirmwareEra.RUSEFI
                    else -> throw UnsupportedFirmwareException("Unsupported ECU family: ${resolvedDefinition.family}")
                }

                readOnlySafeModeEnabled = manualFirmwareProfile != null
                protocol.setSessionLegacyPreferred(
                    resolvedDefinition.runtime.configReadMode == EcuConfigReadMode.LEGACY_PAGE
                )
                // A partir daqui a era do firmware manda no enquadramento, não o transporte —
                // mas só onde temos evidência. Afirmamos `true` apenas para Speeduino 202201+,
                // onde o .ini declara `messageEnvelopeFormat = msEnvelope_1.0` e o campo foi
                // validado em ECU real. Todo o resto fica `null` = decide pelos flags do
                // transporte, exatamente como antes: nunca forçamos legacy num firmware que hoje
                // funciona em modern, porque isso seria uma mudança sem cobertura nenhuma.
                protocol.setSessionModernEnvelope(
                    if (resolvedDefinition.family == EcuFamily.SPEEDUINO && era.usesModernEnvelope()) {
                        true
                    } else {
                        null
                    }
                )
                protocol.setSessionEcuFamily(resolvedDefinition.family)
                protocol.setSessionSchemaId(resolvedDefinition.runtime.schemaId)

                // Armazenar informações
                ecuDefinition = resolvedDefinition
                firmwareInfo = FirmwareInfo(
                    signature = effectiveFirmwareSignature,
                    productString = productString,
                    era = era,
                    family = resolvedDefinition.family,
                    capabilities = resolvedDefinition.capabilities
                )
                tableDefinitions = definitions

                // 4. Detect output channels block size and load field definitions
                outputChannelFields = definitions?.let { loadedDefinitions ->
                    SpeeduinoOutputChannels.getDefinition(loadedDefinitions.ochBlockSize)
                }
                preloadPinLayoutInfoIfPossible()

                Logger.i(TAG, "✅ Firmware compatível: $effectiveFirmwareSignature (era: $era)")
                if (definitions != null) {
                    Logger.i(TAG, "✅ Table definitions carregadas: VE=Page${definitions.veTable.page}, Ignition=Page${definitions.ignitionTable.page}")
                    Logger.i(TAG, "✅ Output channels: ${definitions.ochBlockSize} bytes, ${outputChannelFields?.size} fields")
                } else {
                    Logger.i(TAG, "ℹ️ ECU ${resolvedDefinition.family} conectada sem definitions de tuning nesta PR")
                }
                if (readOnlySafeModeEnabled) {
                    Logger.w(TAG, "⚠️ Safe mode read-only habilitado (perfil manual)")
                }

                // Limpa qualquer byte tardio de consultas de firmware antes de iniciar downloads/stream.
                runCatching { connection.clearInputBuffer() }

                connection.markHandshakeSuccess()

            } catch (e: UnsupportedFirmwareException) {
                // Desconectar se firmware incompatível
                connection.disconnect()
                readOnlySafeModeEnabled = false

                val originalMessage = e.message.orEmpty()
                val normalizedMessage = originalMessage.lowercase()
                val isChannelQualityError =
                    "assinatura de firmware" in normalizedMessage ||
                    "no readable legacy candidates" in normalizedMessage ||
                    "invalid legacy candidates" in normalizedMessage ||
                    "firmware signature" in normalizedMessage
                if (isChannelQualityError) {
                    Logger.e(TAG, originalMessage)
                    throw e
                }

                val supportedVersions = EcuDefinitionRegistry.getSupportedFamilies()
                val errorMessage = """
                ❌ Firmware não suportado: $effectiveFirmwareSignature

                Versões suportadas:
                ${supportedVersions.joinToString("\n") { "  • $it" }}

                Por favor, atualize o firmware da ECU ou entre em contato.
            """.trimIndent()

                Logger.e(TAG, errorMessage)
                throw UnsupportedFirmwareException(errorMessage)
            }
        }
    }

    private fun shouldUseLegacyHandshakeCore(): Boolean {
        return forceLegacyFirmwareHandshakeAfterModernDisconnect ||
            FirmwareHandshakeDomain.shouldUseLegacyHandshakeCore(connection.supportsModernProtocol())
    }

    private suspend fun drainInputBufferQuietly() {
        runCatching { connection.clearInputBuffer() }
        delay(50)
        runCatching { connection.clearInputBuffer() }
    }

    private suspend fun readFirmwareSignatureSamples(maxAttempts: Int = 4): List<String> {
        val samples = mutableListOf<String>()
        var lastError: Exception? = null

        repeat(maxAttempts) { attempt ->
            try {
                drainInputBeforeFirmwareHandshake()
                val raw = protocol.getFirmwareInfo()
                val sanitized = FirmwareHandshakeDomain.sanitizeSignature(raw)
                if (sanitized.equals("Unknown", ignoreCase = true) && !connection.isConnected()) {
                    throw UnsupportedFirmwareException("Modern firmware handshake disconnected")
                }
                if (sanitized.isNotBlank()) {
                    samples.add(sanitized)
                }
                val consensus = resolveFirmwareConsensus(samples)
                if (consensus.signature != null && consensus.consensusHits >= 2) {
                    return samples
                }
            } catch (e: Exception) {
                lastError = e
                Logger.w(TAG, "Falha ao ler assinatura de firmware (tentativa ${attempt + 1}/$maxAttempts): ${e.message}")
                if (!connection.isConnected()) {
                    forceLegacyFirmwareHandshakeAfterModernDisconnect = true
                    Logger.w(TAG, "Handshake modern desconectou; próxima tentativa usará legacy-first")
                    throw e
                }
            }

            if (attempt < maxAttempts - 1) {
                delay(FIRMWARE_HANDSHAKE_RETRY_DELAY_MS)
            }
        }

        if (samples.isEmpty()) {
            throw (lastError ?: UnsupportedFirmwareException("Unable to read firmware signature"))
        }

        return samples
    }

    private fun resolveFirmwareConsensus(samples: List<String>): FirmwareConsensus {
        return FirmwareHandshakeDomain.resolveConsensus(samples)
    }

    private suspend fun drainInputBeforeFirmwareHandshake() {
        runCatching { connection.clearInputBuffer() }
        delay(FIRMWARE_HANDSHAKE_DRAIN_DELAY_MS)
        runCatching { connection.clearInputBuffer() }
    }

    private suspend fun readLegacyFirmwareSignatureSamples(): List<String> {
        val maxAttempts = connection.legacyFirmwareHandshakeAttempts().coerceAtLeast(3)
        val retryDelayMs = connection.legacyFirmwareHandshakeRetryDelayMs()
            .coerceAtLeast(FIRMWARE_HANDSHAKE_RETRY_DELAY_MS)
        val samples = mutableListOf<String>()
        var lastError: Exception? = null

        repeat(maxAttempts) { attempt ->
            try {
                if (attempt > 0) {
                    connection.prepareHandshakeRetry(attempt)
                    Logger.w(TAG, "Retry legacy firmware handshake (${attempt + 1}/$maxAttempts)")
                    if (retryDelayMs > 0L) {
                        delay(retryDelayMs)
                    }
                }

                drainInputBeforeFirmwareHandshake()

                val candidates = protocol.getFirmwareInfoLegacyCandidates()
                val selectedSample = FirmwareHandshakeDomain.selectBestCandidate(candidates)
                ConnectionTrace.info(
                    inferHandshakeTraceTransport(),
                    "firmware_handshake attempt=${attempt + 1}/$maxAttempts candidates=${formatHandshakeCandidates(candidates)} selected=${selectedSample ?: "<none>"}"
                )
                if (!selectedSample.isNullOrBlank()) {
                    samples += selectedSample
                    val consensus = resolveFirmwareConsensus(samples)
                    ConnectionTrace.info(
                        inferHandshakeTraceTransport(),
                        "firmware_handshake_consensus attempt=${attempt + 1}/$maxAttempts samples=${formatHandshakeCandidates(samples)} signature=${consensus.signature ?: "<none>"} hits=${consensus.consensusHits}"
                    )
                    if (consensus.signature != null && consensus.consensusHits >= 2) {
                        return samples
                    }
                } else {
                    val cleanedCandidates = candidates
                        .map(FirmwareHandshakeDomain::sanitizeSignature)
                        .filter(String::isNotBlank)
                    val detail = if (cleanedCandidates.isEmpty()) {
                        "no readable legacy candidates"
                    } else {
                        "invalid legacy candidates: ${cleanedCandidates.joinToString(" | ")}"
                    }
                    lastError = UnsupportedFirmwareException(detail)
                    Logger.w(TAG, "Discarding legacy firmware sample (${attempt + 1}/$maxAttempts): $detail")
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        if (samples.isNotEmpty()) {
            return samples
        }

        throw (lastError ?: UnsupportedFirmwareException("Unable to read legacy firmware signature"))
    }

    private fun inferHandshakeTraceTransport(): String {
        val info = connection.getConnectionInfo().lowercase()
        return when {
            info.startsWith("bluetooth:") -> "bluetooth"
            info.startsWith("tcp:") -> "tcp"
            info.startsWith("usb") -> "usb"
            else -> "handshake"
        }
    }

    private fun formatHandshakeCandidates(values: List<String>): String {
        return values.joinToString(" | ") { value ->
            FirmwareHandshakeDomain.sanitizeSignature(value).take(120).ifBlank { "<blank>" }
        }.ifBlank { "<none>" }
    }
    private fun validateFirmwareConsensus(
        consensus: FirmwareConsensus,
        samples: List<String>
    ) {
        try {
            FirmwareHandshakeDomain.validateConsensus(consensus, samples)
        } catch (error: UnsupportedFirmwareException) {
            connection.disconnect()
            Logger.e(TAG, error.message.orEmpty())
            throw error
        }
    }

    private suspend fun preloadPinLayoutInfoIfPossible() {
        if ((firmwareInfo?.family ?: EcuFamily.UNKNOWN) != EcuFamily.SPEEDUINO) {
            return
        }
        if (pinLayoutInfo != null) {
            return
        }

        try {
            val pageData = readPage(pageNum = 1, offset = 0, length = 16)
            if (pageData.size >= 16) {
                pinLayoutInfo = PinLayoutDetector.fromPage1(pageData)
                Logger.d(
                    TAG,
                    "Pin layout detectado no connect: idx=${pinLayoutInfo?.index}, name=${pinLayoutInfo?.name}, mcu=${pinLayoutInfo?.mcuFamily}"
                )
            } else {
                Logger.w(TAG, "Nao foi possivel detectar pin layout no connect: resposta curta (${pageData.size} bytes)")
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Falha ao detectar pin layout no connect: ${e.message}")
        }
    }

    /**
     * Obtém informações do firmware conectado
     * @return FirmwareInfo ou null se não conectado
     */
    override fun getFirmwareInfoCached(): FirmwareInfo? = firmwareInfo

    /**
     * Obtém definição genérica da ECU conectada.
     */
    fun getEcuDefinition(): EcuDefinition? = ecuDefinition

    /**
     * Obtém a família da ECU conectada.
     */
    override fun getEcuFamily(): EcuFamily = firmwareInfo?.family ?: EcuFamily.UNKNOWN

    /**
     * Obtém capacidades declaradas da ECU conectada.
     */
    override fun getEcuCapabilities(): EcuCapabilities? = firmwareInfo?.capabilities

    /**
     * Obtém informações do pin layout detectado (Page 1).
     */
    override fun getPinLayoutInfoCached(): PinLayoutInfo? = pinLayoutInfo

    /**
     * Permite cachear pin layout detectado fora do fluxo padrão.
     */
    override fun cachePinLayoutInfo(info: PinLayoutInfo?) {
        pinLayoutInfo = info
    }

    /**
     * Descarta o cache persistente de páginas desta identidade de ECU. Usado antes de um
     * download completo pedido explicitamente pelo usuário ("Sincronizar"), para garantir que
     * ele sempre reflita o estado atual da ECU em vez de bytes em cache de até [pageCacheTtlMs].
     */
    override fun invalidateConfigPageCache() {
        cacheIdentity()?.let { pageCache.invalidateIdentity(it) }
    }

    /**
     * Obtém table definitions carregadas
     * @return TableDefinitions ou null se não conectado
     */
    override fun getTableDefinitions(): TableDefinitions? = tableDefinitions

    override suspend fun sendLegacyPassthrough(
        command: ByteArray,
        expectResponse: Boolean,
        responseSize: Int?,
    ): ByteArray = withContext(Dispatchers.IO) {
        protocol.sendLegacyPassthrough(
            command = command,
            expectResponse = expectResponse,
            responseSize = responseSize,
        )
    }

    /**
     * Obtém output channel fields carregados
     * @return List<OutputField> ou null se não conectado
     */
    fun getOutputChannelFields(): List<OutputField>? = outputChannelFields

    override fun applyIniDefinition(definition: IniDefinition): Boolean {
        activeIniDefinition = definition

        if (firmwareInfo?.family == EcuFamily.SPEEDUINO) {
            val catalog = SpeeduinoIniDefinitions.fromIni(definition) ?: return false
            speeduinoIniCatalog = catalog
            tableDefinitions = catalog.tableDefinitions
            ecuDefinition = ecuDefinition?.copy(
                runtime = ecuDefinition?.runtime?.copy(
                    blockSize = definition.ochBlockSize ?: catalog.tableDefinitions.ochBlockSize
                ) ?: return false,
                tableDefinitions = catalog.tableDefinitions,
                pageCatalog = catalog.pageCatalog.ifEmpty { ecuDefinition?.pageCatalog ?: emptyList() },
            )
            outputChannelFields = catalog.outputFields.ifEmpty {
                SpeeduinoOutputChannels.getDefinition(
                    definition.ochBlockSize ?: catalog.tableDefinitions.ochBlockSize
                )
            }
            SpeeduinoOutputChannels.registerRuntimeDefinition(
                definition.ochBlockSize ?: catalog.tableDefinitions.ochBlockSize,
                outputChannelFields ?: emptyList(),
            )
            Logger.i(
                TAG,
                "✅ Layout Speeduino aplicado via .ini: VE=${catalog.tableDefinitions.veTable.page}, " +
                    "IGN=${catalog.tableDefinitions.ignitionTable.page}, AFR=${catalog.tableDefinitions.afrTable.page}"
            )
            return true
        }

        if (firmwareInfo?.family == EcuFamily.MEGASPEED) {
            val catalog = MegaSpeedIniTableDefinitions.fromIni(definition) ?: return false
            megaSpeedIniCatalog = catalog
            tableDefinitions = catalog.tableDefinitions
            ecuDefinition = ecuDefinition?.copy(
                runtime = ecuDefinition?.runtime?.copy(
                    blockSize = definition.ochBlockSize ?: catalog.tableDefinitions.ochBlockSize
                ) ?: return false,
                tableDefinitions = catalog.tableDefinitions,
                pageCatalog = catalog.pageCatalog.ifEmpty { ecuDefinition?.pageCatalog ?: emptyList() },
            )
            outputChannelFields = SpeeduinoOutputChannels.getDefinition(
                definition.ochBlockSize ?: catalog.tableDefinitions.ochBlockSize
            )
            Logger.i(
                TAG,
                "✅ Layout MegaSpeed aplicado via .ini: VE=${catalog.veTable.metadata.page}, " +
                    "IGN=${catalog.ignitionTable.metadata.page}, AFR=${catalog.afrTable.metadata.page}"
            )
            return true
        }

        if (firmwareInfo?.family == EcuFamily.RUSEFI) {
            val catalog = RusefiIniTableDefinitions.fromIni(definition) ?: return false
            rusefiIniCatalog = catalog
            tableDefinitions = catalog.tableDefinitions
            ecuDefinition = ecuDefinition?.copy(
                runtime = ecuDefinition?.runtime?.copy(
                    blockSize = definition.ochBlockSize ?: catalog.tableDefinitions.ochBlockSize
                ) ?: return false,
                tableDefinitions = catalog.tableDefinitions,
                pageCatalog = catalog.pageCatalog.ifEmpty { ecuDefinition?.pageCatalog ?: emptyList() },
            )
            outputChannelFields = catalog.outputFields.ifEmpty {
                SpeeduinoOutputChannels.getDefinition(
                    definition.ochBlockSize ?: catalog.tableDefinitions.ochBlockSize
                )
            }
            SpeeduinoOutputChannels.registerRuntimeDefinition(
                definition.ochBlockSize ?: catalog.tableDefinitions.ochBlockSize,
                outputChannelFields ?: emptyList(),
            )
            Logger.i(
                TAG,
                "✅ Layout rusEFI aplicado via .ini: VE=${formatPageId(catalog.veTable.metadata.page)}, " +
                    "IGN=${formatPageId(catalog.ignitionTable.metadata.page)}, AFR=${formatPageId(catalog.afrTable.metadata.page)}"
            )
            return true
        }

        return false
    }

    override fun setManualFirmwareProfile(signature: String, readOnly: Boolean) {
        val normalized = FirmwareHandshakeDomain.normalizeManualProfile(signature)
        manualFirmwareProfile = normalized
        readOnlySafeModeEnabled = readOnly
        Logger.w(TAG, "Perfil manual configurado: $normalized (readOnly=$readOnly)")
    }

    override fun clearManualFirmwareProfile() {
        manualFirmwareProfile = null
        readOnlySafeModeEnabled = false
    }

    fun getManualFirmwareProfile(): String? = manualFirmwareProfile

    override fun isReadOnlySafeMode(): Boolean = readOnlySafeModeEnabled

    /**
     * Desconecta do Speeduino
     */
    override fun disconnect() {
        lastDisconnectWasRusefi = firmwareInfo?.family == EcuFamily.RUSEFI
        lastDisconnectAtMs = MonotonicClock.nowMillis()
        lastGlobalDisconnectWasActive = true
        lastGlobalDisconnectAtMs = lastDisconnectAtMs
        stopLiveDataStream()
        connection.disconnect()
        protocol.setSessionLegacyPreferred(false)
        protocol.setSessionModernEnvelope(null)
        protocol.setSessionEcuFamily(null)
        protocol.setSessionSchemaId(null)
        forceLegacyFirmwareHandshakeAfterModernDisconnect = false
        // NÃO cancelar o scope - apenas o job do stream
        // Isso permite reconectar e reiniciar o stream
        firmwareInfo = null
        ecuDefinition = null
        tableDefinitions = null
        outputChannelFields = null
        activeIniDefinition = null
        speeduinoIniCatalog = null
        megaSpeedIniCatalog = null
        rusefiIniCatalog = null
        pendingRusefiVeTableReadback = null
        pendingRusefiIgnitionTableReadback = null
        pendingSpeeduinoPageReadbacks.clear()
        cachedEngineConstants = null
        pinLayoutInfo = null
        SpeeduinoOutputChannels.clearAllRuntimeDefinitions()
    }

    /**
     * Verifica se está conectado
     */
    override fun isConnected(): Boolean = connection.isConnected()

    /**
     * Verifica se o stream de dados está ativo
     */
    override fun isStreaming(): Boolean = _isStreaming

    /**
     * Obtém informações da conexão
     */
    override fun getConnectionInfo(): String = connection.getConnectionInfo()

    override fun getConnectionProfileTag(): String? = connection.getConnectionProfileTag()

    override fun getLegacyConfigBlockSizeOverride(): Int? = connection.legacyConfigBlockSizeOverride()

    // ==================== Protocol Commands ====================

    /**
     * Obtém informações do firmware (comando 'Q')
     */
    override suspend fun getFirmwareInfo(): String {
        return protocol.getFirmwareInfo()
    }

    /**
     * Obtém string do produto (comando 'S')
     */
    override suspend fun getProductString(): String {
        return protocol.getProductString()
    }

    /**
     * Obtém capacidades seriais (comando 'f')
     */
    override suspend fun getSerialCapability(): SerialCapability {
        return when (getEcuFamily()) {
            EcuFamily.SPEEDUINO -> {
                protocol.getSerialCapability()
            }
            EcuFamily.RUSEFI -> {
                // rusEFI simulator/firmware compatibility profile used by this app.
                SerialCapability(protocolVersion = 1, blockingFactor = 1024, tableBlockingFactor = 1024)
            }
            EcuFamily.MS2,
            EcuFamily.MEGASPEED,
            EcuFamily.MS3 -> {
                // These families may close socket on unsupported 'f' capability command.
                // Use conservative defaults and avoid probing.
                SerialCapability(protocolVersion = 1, blockingFactor = 256, tableBlockingFactor = 256)
            }
            else -> {
                SerialCapability(protocolVersion = 1, blockingFactor = 256, tableBlockingFactor = 256)
            }
        }
    }

    /**
     * Lê CRC32 de uma página (comando 'd')
     */
    suspend fun getPageCRC(pageNum: Int): Long {
        require(pageNum in 0..0xFF) { "CRC page id fora do range legacy: ${formatPageId(pageNum)}" }
        return protocol.getPageCRC(pageNum.toByte())
    }

    suspend fun getPageCRC(pageNum: Byte): Long {
        return protocol.getPageCRC(pageNum)
    }

    /**
     * Lê uma página completa (comando 'p')
     * @param pageNum Número da página (0-15)
     * @param offset Offset inicial
     * @param length Tamanho a ler
     */
    suspend fun readPage(pageNum: Int, offset: Int, length: Int): ByteArray {
        require(pageNum in 0..0xFF) { "Page read legacy requer id de 8 bits: ${formatPageId(pageNum)}" }
        pendingSpeeduinoPageReadbacks.remove(pageNum)?.let { cached ->
            if (firmwareInfo?.family == EcuFamily.SPEEDUINO && offset == 0 && length == cached.size) {
                Logger.d(TAG, "Usando readback em cache para página ${formatPageId(pageNum)}")
                return cached.copyOf()
            } else {
                pendingSpeeduinoPageReadbacks[pageNum] = cached
            }
        }
        resolveMs2Alias(pageNum, offset, length)?.let { alias ->
            return protocol.readTable(alias.pageId, alias.offset, length, getEcuFamily())
        }
        val family = firmwareInfo?.family ?: EcuFamily.UNKNOWN

        // Cache-through persistente: serve páginas frescas do disco sem tocar na ECU.
        val cacheIdentity = cacheIdentity()
        if (cacheIdentity != null) {
            pageCache.read(cacheIdentity, pageNum, offset, length, MonotonicClock.nowMillis(), pageCacheTtlMs)
                ?.let { cached ->
                    Logger.d(TAG, "Cache hit página ${formatPageId(pageNum)} offset=$offset length=$length")
                    return cached
                }
        }

        val readMode = resolveConfigReadMode(family)
        val result = if (family == EcuFamily.SPEEDUINO && readMode == EcuConfigReadMode.MODERN_TABLE) {
            protocol.readPage(
                pageNum = pageNum.toByte(),
                offset = offset,
                length = length,
                allowModernTransportFallback = shouldUseModernConfigReadTransport(family, readMode),
            )
        } else {
            protocol.readPage(pageNum.toByte(), offset, length)
        }

        if (cacheIdentity != null && result.size == length) {
            pageCache.store(cacheIdentity, pageNum, offset, length, result, MonotonicClock.nowMillis())
        }
        return result
    }

    /**
     * Identidade do cache de páginas: assinatura de firmware + endereço de conexão.
     * Retorna `null` (cache desabilitado) para famílias diferentes de Speeduino ou quando o firmware
     * ainda não foi identificado.
     */
    private fun cacheIdentity(): String? {
        val info = firmwareInfo ?: return null
        if (info.family != EcuFamily.SPEEDUINO) return null
        val signature = info.signature.takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
            ?: return null
        val address = connection.getConnectionInfo().ifBlank { "unknown" }
        return "${info.family.name}|$signature|$address"
    }

    private fun invalidateCachedPage(pageNum: Int) {
        cacheIdentity()?.let { pageCache.invalidatePage(it, pageNum) }
    }

    /**
     * Grava uma página na ECU e invalida a entrada correspondente no cache persistente,
     * garantindo que a próxima leitura reflita o novo conteúdo.
     */
    private suspend fun writeConfigPage(pageNum: Byte, offset: Int, data: ByteArray) {
        protocol.writePage(pageNum = pageNum, offset = offset, data = data)
        invalidateCachedPage(pageNum.toInt() and 0xFF)
    }

    suspend fun readPage(pageNum: Byte, offset: Int, length: Int): ByteArray {
        return readPage(pageNum.toInt() and 0xFF, offset, length)
    }

    suspend fun readConfigChunk(pageId: Int, offset: Int, length: Int): ByteArray {
        val pageLabel = formatPageId(pageId)
        val family = firmwareInfo?.family ?: EcuFamily.UNKNOWN
        val readMode = resolveConfigReadMode(family)
        var lastError: Exception? = null
        val shouldResyncRusEfi = family == EcuFamily.RUSEFI
        val configReadDelayMs = maxOf(CONFIG_CHUNK_READ_RETRY_DELAY_MS, connection.legacyConfigReadDelayMs())
        val preferModernConfigRead = shouldUseModernConfigReadTransport(family, readMode)

        if (shouldResyncRusEfi) {
            drainInputBufferQuietly()
        }

        repeat(CONFIG_CHUNK_READ_MAX_ATTEMPTS) { attempt ->
            try {
                if (readMode == EcuConfigReadMode.LEGACY_PAGE && connection.shouldDrainBeforeLegacyConfigRead()) {
                    runCatching { connection.clearInputBuffer() }
                    delay(20)
                    runCatching { connection.clearInputBuffer() }
                }
                if (family == EcuFamily.RUSEFI && length > RUSEFI_CONFIG_READ_CHUNK_SIZE) {
                    return readConfigChunkChunked(pageId, offset, length, RUSEFI_CONFIG_READ_CHUNK_SIZE)
                }
                return if (readMode == EcuConfigReadMode.LEGACY_PAGE) {
                    protocol.readPage(
                        pageNum = (pageId and 0xFF).toByte(),
                        offset = offset,
                        length = length,
                        allowModernTransportFallback = preferModernConfigRead,
                    )
                } else {
                    when (family) {
                        EcuFamily.MS2, EcuFamily.MEGASPEED -> protocol.readTable(pageId, offset, length, EcuFamily.MS2)
                        EcuFamily.MS3 -> protocol.readTable(pageId, offset, length, EcuFamily.MS3)
                        EcuFamily.RUSEFI -> protocol.readTable(pageId, offset, length, EcuFamily.RUSEFI)
                        else -> protocol.readPage(
                            pageNum = (pageId and 0xFF).toByte(),
                            offset = offset,
                            length = length,
                            allowModernTransportFallback = preferModernConfigRead,
                        )
                    }
                }
            } catch (e: Exception) {
                lastError = e
                val canRetry = attempt < CONFIG_CHUNK_READ_MAX_ATTEMPTS - 1 &&
                    (isRecoverableConfigChunkReadFailure(e) || family == EcuFamily.RUSEFI)
                if (!canRetry) {
                    throw Exception(
                        "Config chunk read failed page=$pageLabel family=${family.name} offset=$offset length=$length detail=${e.message ?: "unknown"}",
                        e
                    )
                }

                Logger.w(
                    TAG,
                    "Leitura parcial/timeout recuperável em config chunk page=$pageLabel offset=$offset length=$length; " +
                        "retry ${attempt + 2}/$CONFIG_CHUNK_READ_MAX_ATTEMPTS: ${e.message}"
                )
                runCatching { connection.clearInputBuffer() }
                delay(configReadDelayMs)
            }
        }

        val error = lastError ?: Exception("unknown")
        throw Exception(
            "Config chunk read failed page=$pageLabel family=${family.name} offset=$offset length=$length detail=${error.message ?: "unknown"}",
            error
        )
    }

    suspend fun readConfigChunk(pageId: Byte, offset: Int, length: Int): ByteArray {
        return readConfigChunk(pageId.toInt() and 0xFF, offset, length)
    }

    private suspend fun readConfigChunkChunked(
        pageId: Int,
        offset: Int,
        length: Int,
        chunkSize: Int,
    ): ByteArray {
        val data = ByteArray(length)
        var copied = 0
        while (copied < length) {
            val chunkOffset = offset + copied
            val currentSize = minOf(chunkSize, length - copied)
            val chunk = readConfigChunkSingleAttempt(pageId, chunkOffset, currentSize)
            if (chunk.size != currentSize) {
                throw Exception(
                    "Short rusEFI config chunk page=${formatPageId(pageId)} offset=$chunkOffset length=$currentSize received=${chunk.size}"
                )
            }
            chunk.copyInto(data, copied)
            copied += currentSize
            if (copied < length) {
                delay(10)
            }
        }
        return data
    }

    private suspend fun readConfigChunkSingleAttempt(pageId: Int, offset: Int, length: Int): ByteArray {
        val family = firmwareInfo?.family ?: EcuFamily.UNKNOWN
        return when (family) {
            EcuFamily.RUSEFI -> protocol.readTable(pageId, offset, length, EcuFamily.RUSEFI)
            EcuFamily.MS2, EcuFamily.MEGASPEED -> protocol.readTable(pageId, offset, length, EcuFamily.MS2)
            EcuFamily.MS3 -> protocol.readTable(pageId, offset, length, EcuFamily.MS3)
            else -> readPage(pageId, offset, length)
        }
    }

    private fun resolveConfigReadMode(family: EcuFamily): EcuConfigReadMode {
        val baseMode = ecuDefinition?.runtime?.configReadMode ?: EcuConfigReadMode.MODERN_TABLE
        if (
            family == EcuFamily.SPEEDUINO &&
            baseMode == EcuConfigReadMode.MODERN_TABLE &&
            !connection.supportsModernConfigReads() &&
            // Rebaixar para legacy por causa do transporte quebra firmware 202201+: a ECU lê os
            // dois primeiros bytes como comprimento, então um 'p' cru vira "esperando 28672
            // bytes" e ela trava. Se o firmware usa envelope, o transporte não tem voto.
            firmwareInfo?.era?.usesModernEnvelope() != true
        ) {
            return EcuConfigReadMode.LEGACY_PAGE
        }
        return baseMode
    }

    private fun shouldUseModernConfigReadTransport(
        family: EcuFamily,
        readMode: EcuConfigReadMode,
    ): Boolean {
        return family == EcuFamily.SPEEDUINO &&
            readMode == EcuConfigReadMode.MODERN_TABLE &&
            firmwareInfo?.era?.isModern() == true &&
            connection.supportsModernConfigReads()
    }

    private fun isRecoverableConfigChunkReadFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty()
            if (message.startsWith("Timeout: no data received")) {
                return true
            }
            if (message.startsWith("Timeout:", ignoreCase = true)) {
                return true
            }
            val partialTimeout = PARTIAL_TIMEOUT_REGEX.find(message)
            if (partialTimeout != null) {
                val expectedBytes = partialTimeout.groupValues.getOrNull(1)?.toIntOrNull() ?: return true
                val receivedBytes = partialTimeout.groupValues.getOrNull(2)?.toIntOrNull() ?: return true
                return receivedBytes in 0 until expectedBytes
            }
            if (
                message.contains("short legacy page response", ignoreCase = true) ||
                message.contains("short modern page response", ignoreCase = true) ||
                message.contains("short table response", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * Lê página completa em blocos (para páginas grandes)
     */
    override suspend fun readFullPage(pageNum: Int, pageSize: Int, blockSize: Int): ByteArray = withContext(Dispatchers.IO) {
        val pageLabel = formatPageId(pageNum)
        val family = firmwareInfo?.family ?: EcuFamily.UNKNOWN
        val readMode = resolveConfigReadMode(family)
        if (readMode == EcuConfigReadMode.LEGACY_PAGE && connection.useWholePageLegacyConfigReads()) {
            return@withContext try {
                readConfigChunk(pageNum, 0, pageSize)
            } catch (e: Exception) {
                throw Exception(
                    "Full page read failed page=$pageLabel pageSize=$pageSize blockSize=$blockSize chunk=1/1 offset=0 length=$pageSize detail=${e.message ?: "unknown"}",
                    e
                )
            }
        }

        val pageData = ByteArray(pageSize)
        var offset = 0
        val interChunkDelayMs = maxOf(10L, connection.legacyConfigReadDelayMs())

        while (offset < pageSize) {
            val chunkSize = minOf(blockSize, pageSize - offset)
            val chunkStart = offset
            val chunkIndex = (chunkStart / blockSize) + 1
            val totalChunks = (pageSize + blockSize - 1) / blockSize
            val chunk = try {
                readConfigChunk(pageNum, chunkStart, chunkSize)
            } catch (e: Exception) {
                throw Exception(
                    "Full page read failed page=$pageLabel pageSize=$pageSize blockSize=$blockSize chunk=$chunkIndex/$totalChunks offset=$chunkStart length=$chunkSize detail=${e.message ?: "unknown"}",
                    e
                )
            }
            if (chunk.isEmpty()) {
                throw IllegalStateException(
                    "Full page read returned empty chunk page=$pageLabel chunk=$chunkIndex/$totalChunks offset=$chunkStart length=$chunkSize"
                )
            }

            if (chunk.size < chunkSize) {
                throw IllegalStateException(
                    "Short page chunk page=$pageLabel chunk=$chunkIndex/$totalChunks offset=$chunkStart expected=$chunkSize received=${chunk.size}"
                )
            }

            chunk.copyInto(pageData, offset, endIndex = chunkSize)
            offset += chunkSize

            // Small delay to avoid overwhelming the ECU
            if (offset < pageSize) {
                delay(interChunkDelayMs)
            }
        }

        pageData
    }

    suspend fun readFullPage(pageNum: Byte, pageSize: Int, blockSize: Int): ByteArray {
        return readFullPage(pageNum.toInt() and 0xFF, pageSize, blockSize)
    }

    override fun getEcuPageCatalog() = ecuDefinition?.pageCatalog ?: emptyList()

    /**
     * Lê Engine Constants (Page 1 - 128 bytes)
     */
    override suspend fun readEngineConstants(): EngineConstants {
        val rusefiSchemaId = ecuDefinition?.runtime?.schemaId ?: "rusefi-main"
        val constants = when (firmwareInfo?.family) {
            EcuFamily.MS2, EcuFamily.MEGASPEED -> {
                Logger.d(TAG, "Lendo Engine Constants MS2 (Page 0x04)...")
                val pageData = readFullPage(pageNum = 0x04, pageSize = 1024, blockSize = 256)
                Logger.d(TAG, "Page 0x04 recebida: ${pageData.size} bytes")
                EngineConstants.fromMs2Page1(pageData)
            }

            EcuFamily.RUSEFI -> {
                activeIniDefinition?.let { definition ->
                    val length = RusefiIniUiParsers.requiredBytesForEngine(definition)
                    Logger.d(TAG, "Lendo Engine Constants rusEFI via .ini (Page 0x0000 chunk 0..${length - 1})...")
                    val pageData = readConfigChunk(pageId = 0x0000, offset = 0, length = length)
                    RusefiIniUiParsers.parseEngineConstants(definition, pageData)
                } ?: run {
                    Logger.d(TAG, "Lendo Engine Constants rusEFI (Page 0x0000 chunk 0..555)...")
                    val pageData = readConfigChunk(pageId = 0x0000, offset = 0, length = 556)
                    EngineConstants.fromRusefiMainPage(pageData, rusefiSchemaId)
                }
            }

            else -> {
                Logger.d(TAG, "Lendo Engine Constants (Page 1)...")
                val pageData = readPage(pageNum = 1, offset = 0, length = 128)
                Logger.d(TAG, "Page 1 recebida: ${pageData.size} bytes")
                pinLayoutInfo = PinLayoutDetector.fromPage1(pageData)
                EngineConstants.fromPage1(pageData)
            }
        }

        cachedEngineConstants = constants
        return constants
    }

    /**
     * Lê calibração de pressão (MAP/Baro/EMAP) em Page 1.
     */
    override suspend fun readPressureCalibration(): PressureCalibration = withContext(Dispatchers.IO) {
        val pageData = readPage(pageNum = 1, offset = 0, length = 128)
        PressureCalibration(
            mapMin = readS8(pageData, 46),
            mapMax = readU16(pageData, 47),
            baroMin = readS8(pageData, 64),
            baroMax = readU16(pageData, 65),
            emapMin = readS8(pageData, 67),
            emapMax = readU16(pageData, 68)
        )
    }

    /**
     * Grava calibração de pressão (MAP/Baro/EMAP) em Page 1 + burn.
     */
    override suspend fun writePressureCalibration(calibration: PressureCalibration, burn: Boolean) = withContext(Dispatchers.IO) {
        ensureWritable("writePressureCalibration")
        val basePage = readPage(pageNum = 1, offset = 0, length = 128)
        writeS8(basePage, 46, calibration.mapMin)
        writeU16(basePage, 47, calibration.mapMax)
        writeS8(basePage, 64, calibration.baroMin)
        writeU16(basePage, 65, calibration.baroMax)
        writeS8(basePage, 67, calibration.emapMin)
        writeU16(basePage, 68, calibration.emapMax)
        writeConfigPage(pageNum = 1, offset = 0, data = basePage)
        if (burn) {
            delay(300)
            protocol.burnConfig()
        }
    }

    /**
     * Lê calibração de TPS em Page 1.
     */
    override suspend fun readTpsCalibration(): TpsCalibration = withContext(Dispatchers.IO) {
        val pageData = readPage(pageNum = 1, offset = 0, length = 128)
        TpsCalibration(
            tpsMin = readU8(pageData, 44),
            tpsMax = readU8(pageData, 45)
        )
    }

    /**
     * Grava calibração de TPS em Page 1 + burn.
     */
    override suspend fun writeTpsCalibration(calibration: TpsCalibration, burn: Boolean) = withContext(Dispatchers.IO) {
        ensureWritable("writeTpsCalibration")
        val basePage = readPage(pageNum = 1, offset = 0, length = 128)
        writeU8(basePage, 44, calibration.tpsMin)
        writeU8(basePage, 45, calibration.tpsMax)
        writeConfigPage(pageNum = 1, offset = 0, data = basePage)
        if (burn) {
            delay(300)
            protocol.burnConfig()
        }
    }

    /**
     * Lê as configurações básicas de controle de marcha lenta do Speeduino.
     */
    override suspend fun readIdleControlSettings(): IdleControlSettings = withContext(Dispatchers.IO) {
        if (firmwareInfo?.family != EcuFamily.SPEEDUINO) {
            throw UnsupportedOperationException("Controle de marcha lenta simplificado disponível apenas para Speeduino")
        }
        val basePage = readPage(pageNum = IdleControlSettings.PAGE_NUMBER, offset = 0, length = IdleControlSettings.PAGE_LENGTH)
        val targetPage = readPage(pageNum = IdleControlSettings.TARGET_PAGE_NUMBER, offset = 0, length = IdleControlSettings.TARGET_PAGE_LENGTH)
        IdleControlSettings.fromPage4(basePage).copy(
            idleTargetRpm = IdleControlSettings.readTargetRpmFromPage7(targetPage),
        )
    }

    /**
     * Grava as configurações básicas de controle de marcha lenta no Speeduino.
     */
    override suspend fun writeIdleControlSettings(settings: IdleControlSettings, burn: Boolean) = withContext(Dispatchers.IO) {
        ensureWritable("writeIdleControlSettings")
        if (firmwareInfo?.family != EcuFamily.SPEEDUINO) {
            throw UnsupportedOperationException("Controle de marcha lenta simplificado disponível apenas para Speeduino")
        }
        val basePage = readPage(pageNum = IdleControlSettings.PAGE_NUMBER, offset = 0, length = IdleControlSettings.PAGE_LENGTH)
        val targetPage = readPage(pageNum = IdleControlSettings.TARGET_PAGE_NUMBER, offset = 0, length = IdleControlSettings.TARGET_PAGE_LENGTH)
        val page4Data = settings.applyToPage4(basePage)
        val page7Data = settings.applyTargetRpmToPage7(targetPage)
        writeConfigPage(pageNum = IdleControlSettings.PAGE_NUMBER.toByte(), offset = 0, data = page4Data)
        writeConfigPage(pageNum = IdleControlSettings.TARGET_PAGE_NUMBER.toByte(), offset = 0, data = page7Data)
        if (burn) {
            delay(300)
            protocol.burnConfig()
        }
    }

    /**
     * Lê Trigger Settings (Page 4 - 128 bytes)
     */
    override suspend fun readTriggerSettings(): TriggerSettings {
        if (firmwareInfo?.family == EcuFamily.RUSEFI) {
            activeIniDefinition?.let { definition ->
                val length = RusefiIniUiParsers.requiredBytesForTrigger(definition)
                Logger.d(TAG, "Lendo Trigger Settings rusEFI via .ini (Page 0x0000 chunk 0..${length - 1})...")
                val pageData = readConfigChunk(pageId = 0x0000, offset = 0, length = length)
                return RusefiIniUiParsers.parseTriggerSettings(definition, pageData)
            }
            val schemaId = ecuDefinition?.runtime?.schemaId ?: "rusefi-main"
            val isF407Discovery = schemaId == "rusefi-f407-discovery"
            val chunkOffset = if (isF407Discovery) 484 else 488
            val paddedSize = if (isF407Discovery) 1658 else 1686
            val length = paddedSize - chunkOffset
            Logger.d(TAG, "Lendo Trigger Settings rusEFI (Page 0x0000 chunk $chunkOffset..${paddedSize - 1})...")
            val pageData = readConfigChunk(pageId = 0x0000, offset = chunkOffset, length = length)
            val padded = ByteArray(paddedSize)
            pageData.copyInto(padded, destinationOffset = chunkOffset)
            return TriggerSettings.fromRusefiMainPage(padded, schemaId)
        }
        if (firmwareInfo?.family == EcuFamily.MS2 || firmwareInfo?.family == EcuFamily.MEGASPEED) {
            Logger.d(TAG, "Lendo Trigger Settings MS2 (Page 0x04)...")
            val pageData = readFullPage(
                pageNum = TriggerSettings.MS2_PAGE_NUMBER,
                pageSize = 1024,
                blockSize = 256
            )
            Logger.d(TAG, "Trigger Settings MS2 recebidos: ${pageData.size} bytes")
            return TriggerSettings.fromMs2PageData(pageData)
        }

        Logger.d(TAG, "Lendo Trigger Settings (Page 4)...")
        val pageData = readPage(
            pageNum = TriggerSettings.PAGE_NUMBER.toByte(),
            offset = 0,
            length = TriggerSettings.PAGE_LENGTH
        )
        Logger.d(TAG, "Trigger Settings recebidos: ${pageData.size} bytes")
        return TriggerSettings.fromPageData(pageData)
    }

    override suspend fun readRusefiInputOutputSnapshot(): RusefiInputOutputSnapshot {
        if (firmwareInfo?.family != EcuFamily.RUSEFI) {
            throw UnsupportedOperationException("I/O snapshot disponível apenas para rusEFI")
        }
        activeIniDefinition?.let { definition ->
            val length = RusefiIniUiParsers.requiredBytesForInputOutput(definition)
            Logger.d(TAG, "Lendo rusEFI Input/Output snapshot via .ini (Page 0x0000 chunk 0..${length - 1})...")
            val pageData = readConfigChunk(pageId = 0x0000, offset = 0, length = length)
            return RusefiIniUiParsers.parseInputOutputSnapshot(definition, pageData)
        }
        val schemaId = ecuDefinition?.runtime?.schemaId ?: "rusefi-main"
        val isF407Discovery = schemaId == "rusefi-f407-discovery"
        val chunkOffset = if (isF407Discovery) 52 else 56
        val paddedSize = if (isF407Discovery) 1640 else 1668
        val length = paddedSize - chunkOffset
        Logger.d(TAG, "Lendo rusEFI Input/Output snapshot (Page 0x0000 chunk $chunkOffset..${paddedSize - 1})...")
        val pageData = readConfigChunk(pageId = 0x0000, offset = chunkOffset, length = length)
        val padded = ByteArray(paddedSize)
        pageData.copyInto(padded, destinationOffset = chunkOffset)
        return RusefiInputOutputConfig.fromMainPage(padded, schemaId)
    }

    /**
     * Lê configuração do Secondary Serial (Page 9, offset 0)
     */
    override suspend fun readSecondarySerialConfig(): SecondarySerialConfig = withContext(Dispatchers.IO) {
        Logger.d(TAG, "Lendo Secondary Serial Config (Page 9)...")
        val data = readPage(
            pageNum = SecondarySerialConfig.PAGE_NUMBER.toByte(),
            offset = SecondarySerialConfig.OFFSET,
            length = 1
        )
        val value = data.firstOrNull()?.toInt()?.and(0xFF) ?: 0
        SecondarySerialConfig.fromByte(value)
    }

    /**
     * Lê Engine Protection/Limiters (Page 6)
     */
    override suspend fun readEngineProtectionConfig(): EngineProtectionConfig {
        if (firmwareInfo?.family == EcuFamily.RUSEFI) {
            throw UnsupportedOperationException("Engine Protection rusEFI ainda não mapeado nesta versão")
        }
        Logger.d(TAG, "Lendo Engine Protection (Page 6)...")
        val pageData = readPage(
            pageNum = EngineProtectionMapper.PAGE_NUMBER.toByte(),
            offset = 0,
            length = EngineProtectionMapper.PAGE_SIZE
        )
        val era = firmwareInfo?.era ?: FirmwareEra.MODERN_2025
        return EngineProtectionMapper.fromPage(pageData, era)
    }

    /**
     * Lê correções AFR/O2 em malha fechada (Page 6)
     */
    override suspend fun readClosedLoopCorrectionConfig(): ClosedLoopCorrectionConfig {
        val era = firmwareInfo?.era ?: FirmwareEra.MODERN_2025
        if (!ClosedLoopCorrectionMapper.isSupported(era)) {
            throw UnsupportedOperationException("Correcoes AFR/O2 requerem firmware Speeduino moderno")
        }
        Logger.d(TAG, "Lendo Closed Loop Corrections (Page 6)...")
        val pageData = readPage(
            pageNum = ClosedLoopCorrectionMapper.PAGE_NUMBER.toByte(),
            offset = 0,
            length = ClosedLoopCorrectionMapper.PAGE_SIZE
        )
        return ClosedLoopCorrectionMapper.fromPage(pageData, era)
    }

    /**
     * Grava Engine Protection/Limiters (Page 6) + Burn
     */
    override suspend fun writeEngineProtectionConfig(config: EngineProtectionConfig, burn: Boolean) {
        ensureWritable("writeEngineProtectionConfig")
        if (firmwareInfo?.family == EcuFamily.RUSEFI) {
            throw UnsupportedOperationException("Engine Protection rusEFI ainda não mapeado nesta versão")
        }
        Logger.d(TAG, "Gravando Engine Protection (Page 6)...")
        val basePage = readPage(
            pageNum = EngineProtectionMapper.PAGE_NUMBER.toByte(),
            offset = 0,
            length = EngineProtectionMapper.PAGE_SIZE
        )
        val era = firmwareInfo?.era ?: FirmwareEra.MODERN_2025
        val updatedData = EngineProtectionMapper.applyToPage(basePage, config, era)
        writeConfigPage(
            pageNum = EngineProtectionMapper.PAGE_NUMBER.toByte(),
            offset = 0,
            data = updatedData
        )
        if (burn) {
            delay(300)
            protocol.burnConfig()
            Logger.d(TAG, "Engine Protection gravado e burn executado")
        } else {
            Logger.d(TAG, "Engine Protection gravado (sem burn)")
        }
    }

    /**
     * Grava correções AFR/O2 em malha fechada (Page 6) + Burn
     */
    override suspend fun writeClosedLoopCorrectionConfig(config: ClosedLoopCorrectionConfig, burn: Boolean) {
        ensureWritable("writeClosedLoopCorrectionConfig")
        val era = firmwareInfo?.era ?: FirmwareEra.MODERN_2025
        if (!ClosedLoopCorrectionMapper.isSupported(era)) {
            throw UnsupportedOperationException("Correcoes AFR/O2 requerem firmware Speeduino moderno")
        }
        Logger.d(TAG, "Gravando Closed Loop Corrections (Page 6)...")
        val basePage = readPage(
            pageNum = ClosedLoopCorrectionMapper.PAGE_NUMBER.toByte(),
            offset = 0,
            length = ClosedLoopCorrectionMapper.PAGE_SIZE
        )
        val updatedData = ClosedLoopCorrectionMapper.applyToPage(basePage, config, era)
        writeConfigPage(
            pageNum = ClosedLoopCorrectionMapper.PAGE_NUMBER.toByte(),
            offset = 0,
            data = updatedData
        )
        if (burn) {
            delay(300)
            protocol.burnConfig()
            Logger.d(TAG, "Closed Loop Corrections gravadas e burn executado")
        } else {
            Logger.d(TAG, "Closed Loop Corrections gravadas (sem burn)")
        }
    }

    /**
     * Grava Trigger Settings (Page 4) + Burn
     */
    override suspend fun writeTriggerSettings(settings: TriggerSettings, burn: Boolean) {
        ensureWritable("writeTriggerSettings")
        if (firmwareInfo?.family == EcuFamily.RUSEFI) {
            throw UnsupportedOperationException("Trigger Settings rusEFI ainda não mapeados nesta versão")
        }
        if (firmwareInfo?.family == EcuFamily.MS2 || firmwareInfo?.family == EcuFamily.MEGASPEED) {
            Logger.d(TAG, "Gravando Trigger Settings MS2 (Page 0x04)...")
            val basePage = readFullPage(
                pageNum = TriggerSettings.MS2_PAGE_NUMBER,
                pageSize = 1024,
                blockSize = 256
            )
            val updatedData = settings.toMs2PageData(basePage)
            protocol.writeTable(
                tableId = TriggerSettings.MS2_PAGE_NUMBER,
                offset = 0,
                data = updatedData
            )
            if (burn) {
                delay(300)
                protocol.burnTable(TriggerSettings.MS2_PAGE_NUMBER)
                Logger.d(TAG, "Trigger Settings MS2 gravados e burn executado")
            } else {
                Logger.d(TAG, "Trigger Settings MS2 gravados (sem burn)")
            }
            return
        }

        Logger.d(TAG, "Gravando Trigger Settings (Page 4)...")
        val basePage = readPage(
            pageNum = TriggerSettings.PAGE_NUMBER.toByte(),
            offset = 0,
            length = TriggerSettings.PAGE_LENGTH
        )
        val updatedData = settings.toPageData(basePage)
        writeConfigPage(
            pageNum = TriggerSettings.PAGE_NUMBER.toByte(),
            offset = 0,
            data = updatedData
        )
        if (burn) {
            delay(300)
            protocol.burnConfig()
            Logger.d(TAG, "Trigger Settings gravados e burn executado")
        } else {
            Logger.d(TAG, "Trigger Settings gravados (sem burn)")
        }
    }

    /**
     * Grava configuração do Secondary Serial (Page 9, offset 0) + Burn
     */
    override suspend fun writeSecondarySerialConfig(config: SecondarySerialConfig, burn: Boolean) = withContext(Dispatchers.IO) {
        ensureWritable("writeSecondarySerialConfig")
        Logger.d(TAG, "Gravando Secondary Serial Config (Page 9)...")
        val baseData = readPage(
            pageNum = SecondarySerialConfig.PAGE_NUMBER.toByte(),
            offset = SecondarySerialConfig.OFFSET,
            length = 1
        )
        val original = baseData.firstOrNull()?.toInt()?.and(0xFF) ?: 0
        val updated = config.applyToByte(original)

        writeConfigPage(
            pageNum = SecondarySerialConfig.PAGE_NUMBER.toByte(),
            offset = SecondarySerialConfig.OFFSET,
            data = byteArrayOf(updated.toByte())
        )

        if (burn) {
            delay(300)
            protocol.burnConfig()
            Logger.d(TAG, "Secondary Serial Config gravado e burn executado")
        } else {
            Logger.d(TAG, "Secondary Serial Config gravado (sem burn)")
        }
    }

    /**
     * Lê VE Table usando definitions dinâmicas
     *
     * IMPORTANT: Uses dynamic page number based on firmware version!
     * - Legacy (2016): Page 1
     * - Modern (2020+): Page 2
     *
     * Format:
     * - Modern firmware (288 bytes): VE values first, axes stored as single-byte bins
     * - Legacy firmware (304 bytes): RPM bins (U16) + load bins precede the table
     *
     * @throws IllegalStateException if not connected
     */
    override suspend fun readVeTable(mapIndex: Int): VeTable {
        if (firmwareInfo?.family == EcuFamily.MS2 || firmwareInfo?.family == EcuFamily.MEGASPEED) {
            return readMs2VeTable()
        }
        if (firmwareInfo?.family == EcuFamily.MS3) {
            return readMs3VeTable()
        }
        if (firmwareInfo?.family == EcuFamily.RUSEFI) {
            return readRusefiVeTable()
        }

        val defs = tableDefinitions
            ?: throw IllegalStateException("Not connected! Call connect() first.")

        val fallbackMetadata = defs.veTable
        val metadata = if (mapIndex == 1) {
            fallbackMetadata
        } else {
            resolveSpeeduinoTableMetadata(
                tableNames = listOf("veTable$mapIndex"),
                fallback = fallbackMetadata,
                displayName = "VE Table $mapIndex"
            )
        }

        Logger.d(
            TAG,
            "Lendo VE Table $mapIndex (Page ${metadata.page}, offset ${metadata.offset}, ${metadata.totalSize} bytes)..."
        )
        val pageData = readPage(
            pageNum = metadata.page.toByte(),
            offset = metadata.offset,
            length = metadata.totalSize
        )
        Logger.d(TAG, "VE Table $mapIndex recebida: ${pageData.size} bytes")

        val storageFormat = VeTable.StorageFormat.fromTotalSize(metadata.totalSize)
        val loadType = resolveVeLoadType(mapIndex)
        return VeTable.fromPageData(pageData, storageFormat, loadType)
    }

    /**
     * Lê Ignition Table usando definitions dinâmicas
     *
     * IMPORTANT: Uses dynamic page number based on firmware version!
     * - All versions: Page 3 (stable across all versions)
     *
     * Format:
     * - Modern firmware (288 bytes): table first, axes compacted em 1 byte
     * - Legacy firmware (304 bytes): axes antes da tabela, com RPM em U16
     *
     * @throws IllegalStateException if not connected
     */
    override suspend fun readIgnitionTable(mapIndex: Int): IgnitionTable {
        if (firmwareInfo?.family == EcuFamily.MS2 || firmwareInfo?.family == EcuFamily.MEGASPEED) {
            return readMs2IgnitionTable()
        }
        if (firmwareInfo?.family == EcuFamily.MS3) {
            return readMs3IgnitionTable()
        }
        if (firmwareInfo?.family == EcuFamily.RUSEFI) {
            return readRusefiIgnitionTable()
        }

        val defs = tableDefinitions
            ?: throw IllegalStateException("Not connected! Call connect() first.")

        val fallbackMetadata = defs.ignitionTable
        val metadata = if (mapIndex == 1) {
            fallbackMetadata
        } else {
            resolveSpeeduinoTableMetadata(
                tableNames = listOf("advTable$mapIndex"),
                fallback = fallbackMetadata,
                displayName = "Ignition Table $mapIndex"
            )
        }

        Logger.d(
            TAG,
            "Lendo Ignition Table $mapIndex (Page ${metadata.page}, offset ${metadata.offset}, ${metadata.totalSize} bytes)..."
        )
        val pageData = readPage(
            pageNum = metadata.page.toByte(),
            offset = metadata.offset,
            length = metadata.totalSize
        )
        Logger.d(TAG, "Ignition Table $mapIndex recebida: ${pageData.size} bytes")

        val storageFormat = IgnitionTable.StorageFormat.fromTotalSize(metadata.totalSize)
        val loadType = resolveIgnitionLoadType(mapIndex)
        return IgnitionTable.fromPageData(pageData, storageFormat, loadType)
    }

    /**
     * Lê Dwell Table (Page 12)
     *
     * A dwell map do Speeduino é pequena (4x4) e usa o mesmo eixo de carga da ignição.
     */
    override suspend fun readDwellTable(): DwellTable {
        if (firmwareInfo?.family != EcuFamily.SPEEDUINO) {
            throw UnsupportedOperationException("Dwell Table only supported on Speeduino")
        }
        val loadType = resolveIgnitionLoadType()
        Logger.d(TAG, "Lendo Dwell Table (Page 12, 192 bytes)...")
        val pageData = readPage(
            pageNum = 12,
            offset = 0,
            length = DwellTable.StorageFormat.PAGE_12_192.totalSize
        )
        Logger.d(TAG, "Dwell Table recebida: ${pageData.size} bytes")
        return DwellTable.fromPageData(pageData, loadType = loadType)
    }

    /**
     * Grava Engine Constants (Page 1 - 128 bytes) + Burn
     */
    override suspend fun writeEngineConstants(engineConstants: EngineConstants) {
        ensureWritable("writeEngineConstants")
        if (firmwareInfo?.family == EcuFamily.RUSEFI) {
            throw UnsupportedOperationException("Engine Constants rusEFI ainda não mapeados nesta versão")
        }
        if (firmwareInfo?.family == EcuFamily.MS2 || firmwareInfo?.family == EcuFamily.MEGASPEED) {
            Logger.d(TAG, "Gravando Engine Constants MS2 (Page 0x04)...")
            val basePage = readFullPage(pageNum = 0x04, pageSize = 1024, blockSize = 256)
            val pageData = engineConstants.applyToMs2Page1(basePage)
            protocol.writeTable(tableId = 0x04, offset = 0, data = pageData)
            protocol.burnTable(tableId = 0x04)
            Logger.d(TAG, "Page 0x04 gravada com sucesso")
        } else {
            Logger.d(TAG, "Gravando Engine Constants (Page 1)...")

            // Preserve existing settings on Page 1 and update only known fields
            val basePage = readPage(pageNum = 1, offset = 0, length = 128)
            val pageData = engineConstants.applyToPage1(basePage)
            Logger.d(TAG, "Page 1 serializada: ${pageData.size} bytes")

            // Write to ECU
            writeConfigPage(pageNum = 1, offset = 0, data = pageData)
            Logger.d(TAG, "Page 1 gravada com sucesso")

            // Burn to EEPROM
            protocol.burnConfig()
            Logger.d(TAG, "Burn executado com sucesso")
        }
        cachedEngineConstants = engineConstants
    }

    /**
     * Grava uma página completa de configuração (backup/restore)
     */
    override suspend fun writeRawPage(pageNum: Int, data: ByteArray) {
        ensureWritable("writeRawPage")
        if (ecuDefinition?.runtime?.configReadMode == EcuConfigReadMode.LEGACY_PAGE) {
            writeConfigPage(pageNum = pageNum.toByte(), offset = 0, data = data)
            settleAfterConfigWrite()
            protocol.burnConfig()
            Logger.d(TAG, "Página ${formatPageId(pageNum)} gravada via backup e burn executado")
            return
        }
        if (firmwareInfo?.family == EcuFamily.SPEEDUINO && isModernTablePage(pageNum, data.size)) {
            writeConfigPage(pageNum = pageNum.toByte(), offset = 0, data = data)
            pendingSpeeduinoPageReadbacks[pageNum] = data.copyOf()
            settleAfterConfigWrite()
            protocol.burnConfig()
            delay(150)
            runCatching { connection.clearInputBuffer() }
            Logger.d(TAG, "Página ${formatPageId(pageNum)} gravada via backup e burn executado")
            return
        }
        if (
            firmwareInfo?.family == EcuFamily.MS2 ||
            firmwareInfo?.family == EcuFamily.MEGASPEED ||
            firmwareInfo?.family == EcuFamily.MS3 ||
            firmwareInfo?.family == EcuFamily.RUSEFI
        ) {
            val alias = resolveMs2Alias(pageNum, 0, data.size)
            val tableId = alias?.pageId ?: pageNum
            val tableOffset = alias?.offset ?: 0
            protocol.writeTable(tableId = tableId, offset = tableOffset, data = data, family = getEcuFamily())
            settleAfterConfigWrite()
            burnTableIfSupported(tableId = tableId, family = getEcuFamily())
        } else {
            writeConfigPage(pageNum = pageNum.toByte(), offset = 0, data = data)
            settleAfterConfigWrite()
            protocol.burnConfig()
        }
        delay(150)
        runCatching { connection.clearInputBuffer() }
        Logger.d(TAG, "Página ${formatPageId(pageNum)} gravada via backup e burn executado")
    }

    suspend fun writeRawPage(pageNum: Byte, data: ByteArray) {
        writeRawPage(pageNum.toInt() and 0xFF, data)
    }

    /**
     * Grava uma página completa sem executar burn (para restauração em lote).
     */
    override suspend fun writeRawPageWithoutBurn(pageNum: Int, data: ByteArray) {
        ensureWritable("writeRawPageWithoutBurn")
        if (ecuDefinition?.runtime?.configReadMode == EcuConfigReadMode.LEGACY_PAGE) {
            writeConfigPage(pageNum = pageNum.toByte(), offset = 0, data = data)
            settleAfterConfigWrite()
            Logger.d(TAG, "Página ${formatPageId(pageNum)} gravada via backup (sem burn)")
            return
        }
        if (firmwareInfo?.family == EcuFamily.SPEEDUINO && isModernTablePage(pageNum, data.size)) {
            writeConfigPage(pageNum = pageNum.toByte(), offset = 0, data = data)
            settleAfterConfigWrite()
            Logger.d(TAG, "Página ${formatPageId(pageNum)} gravada via backup (sem burn)")
            return
        }
        if (
            firmwareInfo?.family == EcuFamily.MS2 ||
            firmwareInfo?.family == EcuFamily.MEGASPEED ||
            firmwareInfo?.family == EcuFamily.MS3 ||
            firmwareInfo?.family == EcuFamily.RUSEFI
        ) {
            val alias = resolveMs2Alias(pageNum, 0, data.size)
            val tableId = alias?.pageId ?: pageNum
            val tableOffset = alias?.offset ?: 0
            protocol.writeTable(tableId = tableId, offset = tableOffset, data = data, family = getEcuFamily())
        } else {
            writeConfigPage(pageNum = pageNum.toByte(), offset = 0, data = data)
        }
        if (firmwareInfo?.family == EcuFamily.SPEEDUINO && isModernTablePage(pageNum, data.size)) {
            pendingSpeeduinoPageReadbacks[pageNum] = data.copyOf()
        }
        settleAfterConfigWrite()
        delay(150)
        runCatching { connection.clearInputBuffer() }
        Logger.d(TAG, "Página ${formatPageId(pageNum)} gravada via backup (sem burn)")
    }

    suspend fun writeRawPageWithoutBurn(pageNum: Byte, data: ByteArray) {
        writeRawPageWithoutBurn(pageNum.toInt() and 0xFF, data)
    }

    /**
     * Grava uma pagina em blocos menores para contornar timeouts de recepcao
     * em firmwares que nao conseguem consumir um pacote legado inteiro a tempo.
     */
    override suspend fun writeRawPageChunkedWithoutBurn(pageNum: Int, data: ByteArray, chunkSize: Int) {
        ensureWritable("writeRawPageChunkedWithoutBurn")
        if (chunkSize <= 0) {
            throw IllegalArgumentException("chunkSize must be > 0")
        }
        if (firmwareInfo?.family != EcuFamily.SPEEDUINO) {
            writeRawPageWithoutBurn(pageNum, data)
            return
        }

        var offset = 0
        var chunkIndex = 0
        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            val chunk = data.copyOfRange(offset, end)
            chunkIndex++
            Logger.d(TAG, "Gravando pagina ${formatPageId(pageNum)} em chunk #$chunkIndex offset=$offset size=${chunk.size}")
            var attempt = 0
            while (true) {
                attempt++
                try {
                    writeConfigPage(pageNum = pageNum.toByte(), offset = offset, data = chunk)
                    break
                } catch (e: Exception) {
                    if (attempt >= 3) throw e
                    Logger.w(TAG, "Chunk #$chunkIndex da pagina ${formatPageId(pageNum)} falhou (tentativa $attempt): ${e.message}; retentando")
                    runCatching { connection.abortPendingRead() }
                    runCatching { connection.clearInputBuffer() }
                    delay(250L * attempt)
                }
            }
            offset = end
            if (offset < data.size) {
                delay(60)
            }
        }
        settleAfterConfigWrite()
        delay(150)
        runCatching { connection.clearInputBuffer() }
        Logger.d(TAG, "Pagina ${formatPageId(pageNum)} gravada em chunks sem burn")
    }

    suspend fun writeRawPageChunkedWithoutBurn(pageNum: Byte, data: ByteArray, chunkSize: Int = 64) {
        writeRawPageChunkedWithoutBurn(pageNum.toInt() and 0xFF, data, chunkSize)
    }

    /**
     * Executa burn após gravações em lote.
     */
    override suspend fun burnConfigs() {
        ensureWritable("burnConfigs")
        if (
            firmwareInfo?.family == EcuFamily.MS2 ||
            firmwareInfo?.family == EcuFamily.MEGASPEED ||
            firmwareInfo?.family == EcuFamily.MS3 ||
            firmwareInfo?.family == EcuFamily.RUSEFI
        ) {
            throw IllegalStateException("${firmwareInfo?.family} requires page-specific burn; use table-specific write helpers.")
        }
        protocol.burnConfig()
        Logger.d(TAG, "Burn executado após restauração em lote")
    }

    override suspend fun burnLastWrittenLegacyPage() {
        ensureWritable("burnLastWrittenLegacyPage")
        if (
            firmwareInfo?.family == EcuFamily.MS2 ||
            firmwareInfo?.family == EcuFamily.MEGASPEED ||
            firmwareInfo?.family == EcuFamily.MS3 ||
            firmwareInfo?.family == EcuFamily.RUSEFI
        ) {
            throw IllegalStateException("${firmwareInfo?.family} requires table-specific burn.")
        }
        protocol.burnConfig()
        Logger.d(TAG, "Burn legacy executado para a última página gravada")
    }

    /**
     * Grava VE Table (Page 1 - offset 0, 304 bytes) + Burn
     *
     * IMPORTANT: This method validates the table before writing.
     * If validation fails, it throws ValidationException.
     *
     * @param veTable VE Table to write
     * @throws ValidationException if table has critical errors
     */
    override suspend fun writeVeTable(veTable: VeTable, mapIndex: Int) {
        ensureWritable("writeVeTable")
        if (firmwareInfo?.family == EcuFamily.MS2 || firmwareInfo?.family == EcuFamily.MEGASPEED) {
            writeMs2VeTable(veTable)
            return
        }
        if (firmwareInfo?.family == EcuFamily.MS3) {
            writeMs3VeTable(veTable)
            return
        }
        if (firmwareInfo?.family == EcuFamily.RUSEFI) {
            writeRusefiVeTable(veTable)
            return
        }
        val defs = tableDefinitions
            ?: throw IllegalStateException("Not connected! Call connect() first.")
        val metadata = if (mapIndex == 1) {
            defs.veTable
        } else {
            resolveSpeeduinoTableMetadata(
                tableNames = listOf("veTable$mapIndex"),
                fallback = defs.veTable,
                displayName = "VE Table $mapIndex"
            )
        }
        Logger.d(TAG, "Gravando VE Table $mapIndex (Page ${metadata.page})...")

        val pageData = TableDomainFacade.prepareVeWrite(metadata, veTable).data
        Logger.d(TAG, "VE Table serializada: ${pageData.size} bytes")

        // 3. Write to ECU using dynamic page number (fire-and-forget, não aguarda resposta)
        writeConfigPage(
            pageNum = metadata.page.toByte(),
            offset = metadata.offset,
            data = pageData
        )
        Logger.d(TAG, "VE Table $mapIndex enviada para Page ${metadata.page}")

        // ⚠️ CRÍTICO: Delay MAIOR para Speeduino processar write completo
        // Write page é assíncrono - 304 bytes levam tempo para gravar na RAM
        // Delay conservador: ~3ms por byte @ 115200 baud = ~900ms + margem
        delay(1000) // 1 segundo de delay (conservador)
        Logger.d(TAG, "Aguardou 1s para processamento do write")

        // Burn to EEPROM (também demora - grava na flash/EEPROM)
        protocol.burnConfig()
        Logger.d(TAG, "✅ Burn executado com sucesso!")
    }

    /**
     * Grava Ignition Table (Page 3 - offset 0, 304 bytes) + Burn
     *
     * CRITICAL: This method validates the table before writing.
     * Dangerous ignition advance values (>45°) will cause validation to FAIL.
     *
     * @param ignitionTable Ignition Table to write
     * @throws ValidationException if table has critical errors (esp. dangerous advance)
     */
    override suspend fun writeIgnitionTable(ignitionTable: IgnitionTable, mapIndex: Int) {
        ensureWritable("writeIgnitionTable")
        if (firmwareInfo?.family == EcuFamily.MS2 || firmwareInfo?.family == EcuFamily.MEGASPEED) {
            writeMs2IgnitionTable(ignitionTable)
            return
        }
        if (firmwareInfo?.family == EcuFamily.MS3) {
            writeMs3IgnitionTable(ignitionTable)
            return
        }
        if (firmwareInfo?.family == EcuFamily.RUSEFI) {
            writeRusefiIgnitionTable(ignitionTable)
            return
        }
        val defs = tableDefinitions
            ?: throw IllegalStateException("Not connected! Call connect() first.")
        val metadata = if (mapIndex == 1) {
            defs.ignitionTable
        } else {
            resolveSpeeduinoTableMetadata(
                tableNames = listOf("advTable$mapIndex"),
                fallback = defs.ignitionTable,
                displayName = "Ignition Table $mapIndex"
            )
        }
        Logger.d(TAG, "Gravando Ignition Table $mapIndex (Page ${metadata.page})...")

        val pageData = TableDomainFacade.prepareIgnitionWrite(metadata, ignitionTable).data
        Logger.d(TAG, "Ignition Table serializada: ${pageData.size} bytes")

        // 3. Write to ECU (fire-and-forget, não aguarda resposta)
        writeConfigPage(pageNum = metadata.page.toByte(), offset = metadata.offset, data = pageData)
        Logger.d(TAG, "Ignition Table enviada")

        // 4. Delay para processar write
        delay(1000)
        Logger.d(TAG, "Aguardou 1s para processamento do write")

        // 5. Burn to EEPROM
        protocol.burnConfig()
        Logger.d(TAG, "✅ Burn executado com sucesso!")
    }

    /**
     * Grava Dwell Table (Page 12 - 192 bytes) + Burn
     */
    override suspend fun writeDwellTable(dwellTable: DwellTable) {
        ensureWritable("writeDwellTable")
        if (firmwareInfo?.family != EcuFamily.SPEEDUINO) {
            throw UnsupportedOperationException("Dwell Table only supported on Speeduino")
        }
        Logger.d(TAG, "Gravando Dwell Table (Page 12)...")

        val pageData = dwellTable.toByteArray()
        Logger.d(TAG, "Dwell Table serializada: ${pageData.size} bytes")

        writeConfigPage(pageNum = 12.toByte(), offset = 0, data = pageData)
        Logger.d(TAG, "Dwell Table enviada")

        delay(1000)
        Logger.d(TAG, "Aguardou 1s para processamento do write")

        protocol.burnConfig()
        Logger.d(TAG, "✅ Burn executado com sucesso!")
    }

    /**
     * Lê AFR Target Table usando definitions dinâmicas
     *
     * IMPORTANT: Uses dynamic page number based on firmware version!
     * - All versions: Page 5 (stable across all versions)
     *
     * Format:
     * - Modern firmware (288 bytes): valores primeiro, eixos compactados
     * - Legacy firmware (304 bytes): eixos de 16 bits antes dos valores
     *
     * @throws IllegalStateException if not connected
     */
    override suspend fun readAfrTable(): AfrTable {
        if (firmwareInfo?.family == EcuFamily.MS2 || firmwareInfo?.family == EcuFamily.MEGASPEED) {
            return readMs2AfrTable()
        }
        if (firmwareInfo?.family == EcuFamily.MS3) {
            return readMs3AfrTable()
        }
        if (firmwareInfo?.family == EcuFamily.RUSEFI) {
            return readRusefiAfrTable()
        }

        val defs = tableDefinitions
            ?: throw IllegalStateException("Not connected! Call connect() first.")

        val metadata = defs.afrTable

        Logger.d(TAG, "Lendo AFR Table (Page ${metadata.page}, offset ${metadata.offset}, ${metadata.totalSize} bytes)...")
        val pageData = readPage(
            pageNum = metadata.page.toByte(),
            offset = metadata.offset,
            length = metadata.totalSize
        )
        Logger.d(TAG, "AFR Table recebida: ${pageData.size} bytes")

        val storageFormat = AfrTable.StorageFormat.fromTotalSize(metadata.totalSize)
        val loadType = resolveAfrLoadType(isLegacyFormat = storageFormat == AfrTable.StorageFormat.LEGACY_304)
        return AfrTable.fromPageData(pageData, storageFormat, loadType)
    }

    private suspend fun readMs3VeTable(): VeTable {
        val loadType = if (isMapLoad()) VeTable.LoadType.MAP else VeTable.LoadType.TPS
        val layout = Ms3TableDefinitions.VE_TABLE_1
        Logger.d(
            TAG,
            "Lendo MS3 VE Table 1 (table 0x${layout.metadata.page.toString(16).uppercase()}, ${layout.metadata.totalSize} bytes)..."
        )
        val valuesData = readConfigChunk(
            pageId = layout.metadata.page.toByte(),
            offset = layout.metadata.offset,
            length = layout.metadata.totalSize
        )
        val rpmAxisData = readConfigChunk(
            pageId = layout.rpmAxis.tableId.toByte(),
            offset = layout.rpmAxis.offset,
            length = layout.rpmAxis.count * 2
        )
        val loadAxisData = readConfigChunk(
            pageId = layout.loadAxis.tableId.toByte(),
            offset = layout.loadAxis.offset,
            length = layout.loadAxis.count * 2
        )
        return Ms3TableDefinitions.parseVeTable(
            valuesData = valuesData,
            rpmAxisData = rpmAxisData,
            loadAxisData = loadAxisData,
            loadType = loadType
        )
    }

    private suspend fun readMs2VeTable(): VeTable {
        val loadType = if (isMapLoad()) VeTable.LoadType.MAP else VeTable.LoadType.TPS
        if (firmwareInfo?.family == EcuFamily.MEGASPEED) {
            megaSpeedIniCatalog?.let { catalog ->
                Logger.d(TAG, "Lendo MegaSpeed VE Table 1 via .ini (${formatPageId(catalog.veTable.metadata.page)})...")
                val valuesData = readConfigChunk(catalog.veTable.metadata.page, catalog.veTable.metadata.offset, catalog.veTable.metadata.totalSize)
                val rpmAxisData = readConfigChunk(catalog.veTable.rpmAxis.pageId, catalog.veTable.rpmAxis.offset, catalog.veTable.rpmAxis.count * 2)
                val loadAxisData = readConfigChunk(catalog.veTable.loadAxis.pageId, catalog.veTable.loadAxis.offset, catalog.veTable.loadAxis.count * 2)
                return MegaSpeedIniTableDefinitions.parseVeTable(catalog.veTable, valuesData, rpmAxisData, loadAxisData, loadType)
            }
        }
        val layout = Ms2TableDefinitions.VE_TABLE_1
        Logger.d(
            TAG,
            "Lendo MS2 VE Table 1 (table 0x${layout.metadata.page.toString(16).uppercase()}, ${layout.metadata.totalSize} bytes)..."
        )
        val valuesData = readConfigChunk(
            pageId = layout.metadata.page.toByte(),
            offset = layout.metadata.offset,
            length = layout.metadata.totalSize
        )
        val rpmAxisData = readConfigChunk(
            pageId = layout.rpmAxis.tableId.toByte(),
            offset = layout.rpmAxis.offset,
            length = layout.rpmAxis.count * 2
        )
        val loadAxisData = readConfigChunk(
            pageId = layout.loadAxis.tableId.toByte(),
            offset = layout.loadAxis.offset,
            length = layout.loadAxis.count * 2
        )
        return Ms2TableDefinitions.parseVeTable(
            valuesData = valuesData,
            rpmAxisData = rpmAxisData,
            loadAxisData = loadAxisData,
            loadType = loadType
        )
    }

    private suspend fun readMs3IgnitionTable(): IgnitionTable {
        val loadType = if (isMapLoad()) IgnitionTable.LoadType.MAP else IgnitionTable.LoadType.TPS
        val layout = Ms3TableDefinitions.IGNITION_TABLE_1
        Logger.d(
            TAG,
            "Lendo MS3 Ignition Table 1 (table 0x${layout.metadata.page.toString(16).uppercase()}, ${layout.metadata.totalSize} bytes)..."
        )
        val valuesData = readConfigChunk(
            pageId = layout.metadata.page.toByte(),
            offset = layout.metadata.offset,
            length = layout.metadata.totalSize
        )
        val rpmAxisData = readConfigChunk(
            pageId = layout.rpmAxis.tableId.toByte(),
            offset = layout.rpmAxis.offset,
            length = layout.rpmAxis.count * 2
        )
        val loadAxisData = readConfigChunk(
            pageId = layout.loadAxis.tableId.toByte(),
            offset = layout.loadAxis.offset,
            length = layout.loadAxis.count * 2
        )
        return Ms3TableDefinitions.parseIgnitionTable(
            valuesData = valuesData,
            rpmAxisData = rpmAxisData,
            loadAxisData = loadAxisData,
            loadType = loadType
        )
    }

    private suspend fun readMs2IgnitionTable(): IgnitionTable {
        val loadType = if (isMapLoad()) IgnitionTable.LoadType.MAP else IgnitionTable.LoadType.TPS
        if (firmwareInfo?.family == EcuFamily.MEGASPEED) {
            megaSpeedIniCatalog?.let { catalog ->
                Logger.d(TAG, "Lendo MegaSpeed Ignition Table 1 via .ini (${formatPageId(catalog.ignitionTable.metadata.page)})...")
                val valuesData = readConfigChunk(catalog.ignitionTable.metadata.page, catalog.ignitionTable.metadata.offset, catalog.ignitionTable.metadata.totalSize)
                val rpmAxisData = readConfigChunk(catalog.ignitionTable.rpmAxis.pageId, catalog.ignitionTable.rpmAxis.offset, catalog.ignitionTable.rpmAxis.count * 2)
                val loadAxisData = readConfigChunk(catalog.ignitionTable.loadAxis.pageId, catalog.ignitionTable.loadAxis.offset, catalog.ignitionTable.loadAxis.count * 2)
                return MegaSpeedIniTableDefinitions.parseIgnitionTable(catalog.ignitionTable, valuesData, rpmAxisData, loadAxisData, loadType)
            }
        }
        val layout = Ms2TableDefinitions.IGNITION_TABLE_1
        Logger.d(
            TAG,
            "Lendo MS2 Ignition Table 1 (table 0x${layout.metadata.page.toString(16).uppercase()}, ${layout.metadata.totalSize} bytes)..."
        )
        val valuesData = readConfigChunk(
            pageId = layout.metadata.page.toByte(),
            offset = layout.metadata.offset,
            length = layout.metadata.totalSize
        )
        val rpmAxisData = readConfigChunk(
            pageId = layout.rpmAxis.tableId.toByte(),
            offset = layout.rpmAxis.offset,
            length = layout.rpmAxis.count * 2
        )
        val loadAxisData = readConfigChunk(
            pageId = layout.loadAxis.tableId.toByte(),
            offset = layout.loadAxis.offset,
            length = layout.loadAxis.count * 2
        )
        return Ms2TableDefinitions.parseIgnitionTable(
            valuesData = valuesData,
            rpmAxisData = rpmAxisData,
            loadAxisData = loadAxisData,
            loadType = loadType
        )
    }

    private suspend fun readMs3AfrTable(): AfrTable {
        val loadType = if (isMapLoad()) AfrTable.LoadType.MAP else AfrTable.LoadType.TPS
        val layout = Ms3TableDefinitions.AFR_TABLE_1
        Logger.d(
            TAG,
            "Lendo MS3 AFR Table 1 (table 0x${layout.metadata.page.toString(16).uppercase()}, ${layout.metadata.totalSize} bytes)..."
        )
        val valuesData = readConfigChunk(
            pageId = layout.metadata.page.toByte(),
            offset = layout.metadata.offset,
            length = layout.metadata.totalSize
        )
        val rpmAxisData = readConfigChunk(
            pageId = layout.rpmAxis.tableId.toByte(),
            offset = layout.rpmAxis.offset,
            length = layout.rpmAxis.count * 2
        )
        val loadAxisData = readConfigChunk(
            pageId = layout.loadAxis.tableId.toByte(),
            offset = layout.loadAxis.offset,
            length = layout.loadAxis.count * 2
        )
        return Ms3TableDefinitions.parseAfrTable(
            valuesData = valuesData,
            rpmAxisData = rpmAxisData,
            loadAxisData = loadAxisData,
            loadType = loadType
        )
    }

    private suspend fun readMs2AfrTable(): AfrTable {
        val loadType = if (isMapLoad()) AfrTable.LoadType.MAP else AfrTable.LoadType.TPS
        if (firmwareInfo?.family == EcuFamily.MEGASPEED) {
            megaSpeedIniCatalog?.let { catalog ->
                Logger.d(TAG, "Lendo MegaSpeed AFR Table 1 via .ini (${formatPageId(catalog.afrTable.metadata.page)})...")
                val valuesData = readConfigChunk(catalog.afrTable.metadata.page, catalog.afrTable.metadata.offset, catalog.afrTable.metadata.totalSize)
                val rpmAxisData = readConfigChunk(catalog.afrTable.rpmAxis.pageId, catalog.afrTable.rpmAxis.offset, catalog.afrTable.rpmAxis.count * 2)
                val loadAxisData = readConfigChunk(catalog.afrTable.loadAxis.pageId, catalog.afrTable.loadAxis.offset, catalog.afrTable.loadAxis.count * 2)
                return MegaSpeedIniTableDefinitions.parseAfrTable(catalog.afrTable, valuesData, rpmAxisData, loadAxisData, loadType)
            }
        }
        val layout = Ms2TableDefinitions.AFR_TABLE_1
        Logger.d(
            TAG,
            "Lendo MS2 AFR Table 1 (table 0x${layout.metadata.page.toString(16).uppercase()}, ${layout.metadata.totalSize} bytes)..."
        )
        val valuesData = readConfigChunk(
            pageId = layout.metadata.page.toByte(),
            offset = layout.metadata.offset,
            length = layout.metadata.totalSize
        )
        val rpmAxisData = readConfigChunk(
            pageId = layout.rpmAxis.tableId.toByte(),
            offset = layout.rpmAxis.offset,
            length = layout.rpmAxis.count * 2
        )
        val loadAxisData = readConfigChunk(
            pageId = layout.loadAxis.tableId.toByte(),
            offset = layout.loadAxis.offset,
            length = layout.loadAxis.count * 2
        )
        return Ms2TableDefinitions.parseAfrTable(
            valuesData = valuesData,
            rpmAxisData = rpmAxisData,
            loadAxisData = loadAxisData,
            loadType = loadType
        )
    }

    private suspend fun readRusefiVeTable(): VeTable {
        val loadType = if (isMapLoad()) VeTable.LoadType.MAP else VeTable.LoadType.TPS
        drainInputBufferQuietly()
        pendingRusefiVeTableReadback?.let { cached ->
            pendingRusefiVeTableReadback = null
            Logger.d(TAG, "Retornando rusEFI VE Table 1 em cache após write recente")
            return cached
        }
        rusefiIniCatalog?.let { catalog ->
            Logger.d(TAG, "Lendo rusEFI VE Table 1 via .ini (${formatPageId(catalog.veTable.metadata.page)})...")
            val valuesData = readConfigChunk(catalog.veTable.metadata.page, catalog.veTable.metadata.offset, catalog.veTable.metadata.totalSize)
            val rpmAxisData = readConfigChunk(catalog.veTable.rpmAxis.tableId, catalog.veTable.rpmAxis.offset, catalog.veTable.rpmAxis.count * 2)
            val loadAxisData = readConfigChunk(catalog.veTable.loadAxis.tableId, catalog.veTable.loadAxis.offset, catalog.veTable.loadAxis.count * 2)
            return RusefiTableDefinitions.parseVeTableWithLayout(catalog.veTable, valuesData, rpmAxisData, loadAxisData, loadType = loadType)
        }
        val schemaId = ecuDefinition?.runtime?.schemaId ?: "rusefi-main"
        val isF407Discovery = schemaId == "rusefi-f407-discovery"
        val layout = if (isF407Discovery) RusefiF407DiscoveryDefinitions.VE_TABLE_1 else RusefiTableDefinitions.VE_TABLE_1
        Logger.d(TAG, "Lendo rusEFI VE Table 1 (${formatPageId(layout.metadata.page)})...")
        val valuesData = readConfigChunk(layout.metadata.page, layout.metadata.offset, layout.metadata.totalSize)
        val rpmAxisData = readConfigChunk(layout.rpmAxis.tableId, layout.rpmAxis.offset, layout.rpmAxis.count * 2)
        val loadAxisData = readConfigChunk(layout.loadAxis.tableId, layout.loadAxis.offset, layout.loadAxis.count * 2)
        return if (isF407Discovery) {
            RusefiF407DiscoveryDefinitions.parseVeTable(valuesData, rpmAxisData, loadAxisData)
        } else {
            RusefiTableDefinitions.parseVeTable(valuesData, rpmAxisData, loadAxisData, loadType = loadType)
        }
    }

    private suspend fun readRusefiIgnitionTable(): IgnitionTable {
        val loadType = if (isMapLoad()) IgnitionTable.LoadType.MAP else IgnitionTable.LoadType.TPS
        drainInputBufferQuietly()
        pendingRusefiIgnitionTableReadback?.let { cached ->
            pendingRusefiIgnitionTableReadback = null
            Logger.d(TAG, "Retornando rusEFI Ignition Table 1 em cache após write recente")
            return cached
        }
        rusefiIniCatalog?.let { catalog ->
            Logger.d(TAG, "Lendo rusEFI Ignition Table 1 via .ini (${formatPageId(catalog.ignitionTable.metadata.page)})...")
            val valuesData = readConfigChunk(catalog.ignitionTable.metadata.page, catalog.ignitionTable.metadata.offset, catalog.ignitionTable.metadata.totalSize)
            val rpmAxisData = readConfigChunk(catalog.ignitionTable.rpmAxis.tableId, catalog.ignitionTable.rpmAxis.offset, catalog.ignitionTable.rpmAxis.count * 2)
            val loadAxisData = readConfigChunk(catalog.ignitionTable.loadAxis.tableId, catalog.ignitionTable.loadAxis.offset, catalog.ignitionTable.loadAxis.count * 2)
            return RusefiTableDefinitions.parseIgnitionTableWithLayout(catalog.ignitionTable, valuesData, rpmAxisData, loadAxisData, loadType = loadType)
        }
        val schemaId = ecuDefinition?.runtime?.schemaId ?: "rusefi-main"
        val isF407Discovery = schemaId == "rusefi-f407-discovery"
        val layout = if (isF407Discovery) RusefiF407DiscoveryDefinitions.IGNITION_TABLE_1 else RusefiTableDefinitions.IGNITION_TABLE_1
        Logger.d(TAG, "Lendo rusEFI Ignition Table 1 (${formatPageId(layout.metadata.page)})...")
        val valuesData = readConfigChunk(layout.metadata.page, layout.metadata.offset, layout.metadata.totalSize)
        val rpmAxisData = readConfigChunk(layout.rpmAxis.tableId, layout.rpmAxis.offset, layout.rpmAxis.count * 2)
        val loadAxisData = readConfigChunk(layout.loadAxis.tableId, layout.loadAxis.offset, layout.loadAxis.count * 2)
        return if (isF407Discovery) {
            RusefiF407DiscoveryDefinitions.parseIgnitionTable(valuesData, rpmAxisData, loadAxisData)
        } else {
            RusefiTableDefinitions.parseIgnitionTable(valuesData, rpmAxisData, loadAxisData, loadType = loadType)
        }
    }

    private suspend fun readRusefiAfrTable(): AfrTable {
        val loadType = if (isMapLoad()) AfrTable.LoadType.MAP else AfrTable.LoadType.TPS
        drainInputBufferQuietly()
        rusefiIniCatalog?.let { catalog ->
            Logger.d(TAG, "Lendo rusEFI AFR Table 1 via .ini (${formatPageId(catalog.afrTable.metadata.page)})...")
            val valuesData = readConfigChunk(catalog.afrTable.metadata.page, catalog.afrTable.metadata.offset, catalog.afrTable.metadata.totalSize)
            val rpmAxisData = readConfigChunk(catalog.afrTable.rpmAxis.tableId, catalog.afrTable.rpmAxis.offset, catalog.afrTable.rpmAxis.count * 2)
            val loadAxisData = readConfigChunk(catalog.afrTable.loadAxis.tableId, catalog.afrTable.loadAxis.offset, catalog.afrTable.loadAxis.count * 2)
            return RusefiTableDefinitions.parseAfrTableWithLayout(catalog.afrTable, valuesData, rpmAxisData, loadAxisData, loadType = loadType)
        }
        val schemaId = ecuDefinition?.runtime?.schemaId ?: "rusefi-main"
        val isF407Discovery = schemaId == "rusefi-f407-discovery"
        val layout = if (isF407Discovery) RusefiF407DiscoveryDefinitions.AFR_TABLE_1 else RusefiTableDefinitions.AFR_TABLE_1
        Logger.d(TAG, "Lendo rusEFI AFR Table 1 (${formatPageId(layout.metadata.page)})...")
        val valuesData = readConfigChunk(layout.metadata.page, layout.metadata.offset, layout.metadata.totalSize)
        val rpmAxisData = readConfigChunk(layout.rpmAxis.tableId, layout.rpmAxis.offset, layout.rpmAxis.count * 2)
        val loadAxisData = readConfigChunk(layout.loadAxis.tableId, layout.loadAxis.offset, layout.loadAxis.count * 2)
        return if (isF407Discovery) {
            RusefiF407DiscoveryDefinitions.parseAfrTable(valuesData, rpmAxisData, loadAxisData)
        } else {
            RusefiTableDefinitions.parseAfrTable(valuesData, rpmAxisData, loadAxisData, loadType = loadType)
        }
    }

    /**
     * Grava AFR Target Table (Page 5 - offset 0, 304 bytes) + Burn
     *
     * Note: AFR Table doesn't require as strict validation as Ignition,
     * but extreme values (too rich/lean) will generate warnings.
     *
     * @param afrTable AFR Target Table to write
     * @throws IllegalStateException if not connected
     */
    override suspend fun writeAfrTable(afrTable: AfrTable) {
        ensureWritable("writeAfrTable")
        if (firmwareInfo?.family == EcuFamily.MS2 || firmwareInfo?.family == EcuFamily.MEGASPEED) {
            writeMs2AfrTable(afrTable)
            return
        }
        if (firmwareInfo?.family == EcuFamily.MS3) {
            writeMs3AfrTable(afrTable)
            return
        }
        if (firmwareInfo?.family == EcuFamily.RUSEFI) {
            writeRusefiAfrTable(afrTable)
            return
        }
        val defs = tableDefinitions
            ?: throw IllegalStateException("Not connected! Call connect() first.")

        val metadata = defs.afrTable

        Logger.d(TAG, "Gravando AFR Table (Page ${metadata.page})...")

        val pageData = TableDomainFacade.prepareAfrWrite(metadata, afrTable).data
        Logger.d(TAG, "AFR Table serializada: ${pageData.size} bytes")

        // Write to ECU using dynamic page number
        writeConfigPage(
            pageNum = metadata.page.toByte(),
            offset = metadata.offset,
            data = pageData
        )
        Logger.d(TAG, "AFR Table enviada para Page ${metadata.page}")

        // Delay para processar write
        delay(1000)
        Logger.d(TAG, "Aguardou 1s para processamento do write")

        // Burn to EEPROM
        protocol.burnConfig()
        Logger.d(TAG, "✅ Burn executado com sucesso!")
    }

    private suspend fun writeMs3VeTable(veTable: VeTable) {
        val validationResult = TableValidator(Ms3TableDefinitions.VE_TABLE_1.metadata)
            .validateBeforeWrite(veTable)
        if (!validationResult.isValid) {
            throw ValidationException(validationResult)
        }

        val serialized = Ms3TableDefinitions.serializeVeTable(veTable)
        protocol.writeTable(serialized.valuesTableId.toByte(), serialized.valuesOffset, serialized.valuesData)
        protocol.writeTable(serialized.rpmAxisTableId.toByte(), serialized.rpmAxisOffset, serialized.rpmAxisData)
        protocol.writeTable(serialized.loadAxisTableId.toByte(), serialized.loadAxisOffset, serialized.loadAxisData)
        delay(300)
        serialized.burnTableIds.sorted().forEach { protocol.burnTable(it.toByte()) }
        Logger.d(TAG, "✅ MS3 VE Table 1 gravada e burn executado")
    }

    private suspend fun writeMs2VeTable(veTable: VeTable) {
        if (firmwareInfo?.family == EcuFamily.MEGASPEED) {
            megaSpeedIniCatalog?.let { catalog ->
                val validationResult = TableValidator(catalog.veTable.metadata).validateBeforeWrite(veTable)
                if (!validationResult.isValid) {
                    throw ValidationException(validationResult)
                }
                val serialized = MegaSpeedIniTableDefinitions.serializeVeTable(catalog.veTable, veTable)
                protocol.writeTable(serialized.valuesTableId.toByte(), serialized.valuesOffset, serialized.valuesData)
                protocol.writeTable(serialized.rpmAxisTableId.toByte(), serialized.rpmAxisOffset, serialized.rpmAxisData)
                protocol.writeTable(serialized.loadAxisTableId.toByte(), serialized.loadAxisOffset, serialized.loadAxisData)
                delay(300)
                serialized.burnTableIds.sorted().forEach { protocol.burnTable(it.toByte()) }
                Logger.d(TAG, "✅ MegaSpeed VE Table 1 gravada via .ini e burn executado")
                return
            }
        }
        val validationResult = TableValidator(Ms2TableDefinitions.VE_TABLE_1.metadata)
            .validateBeforeWrite(veTable)
        if (!validationResult.isValid) {
            throw ValidationException(validationResult)
        }

        val serialized = Ms2TableDefinitions.serializeVeTable(veTable)
        protocol.writeTable(serialized.valuesTableId.toByte(), serialized.valuesOffset, serialized.valuesData)
        protocol.writeTable(serialized.rpmAxisTableId.toByte(), serialized.rpmAxisOffset, serialized.rpmAxisData)
        protocol.writeTable(serialized.loadAxisTableId.toByte(), serialized.loadAxisOffset, serialized.loadAxisData)
        delay(300)
        serialized.burnTableIds.sorted().forEach { protocol.burnTable(it.toByte()) }
        Logger.d(TAG, "✅ MS2 VE Table 1 gravada e burn executado")
    }

    private suspend fun writeMs3IgnitionTable(ignitionTable: IgnitionTable) {
        val validationResult = TableValidator(Ms3TableDefinitions.IGNITION_TABLE_1.metadata)
            .validateBeforeWrite(ignitionTable)
        if (!validationResult.isValid) {
            throw ValidationException(validationResult)
        }

        val serialized = Ms3TableDefinitions.serializeIgnitionTable(ignitionTable)
        protocol.writeTable(serialized.valuesTableId.toByte(), serialized.valuesOffset, serialized.valuesData)
        protocol.writeTable(serialized.rpmAxisTableId.toByte(), serialized.rpmAxisOffset, serialized.rpmAxisData)
        protocol.writeTable(serialized.loadAxisTableId.toByte(), serialized.loadAxisOffset, serialized.loadAxisData)
        delay(300)
        serialized.burnTableIds.sorted().forEach { protocol.burnTable(it.toByte()) }
        Logger.d(TAG, "✅ MS3 Ignition Table 1 gravada e burn executado")
    }

    private suspend fun writeMs2IgnitionTable(ignitionTable: IgnitionTable) {
        if (firmwareInfo?.family == EcuFamily.MEGASPEED) {
            megaSpeedIniCatalog?.let { catalog ->
                val validationResult = TableValidator(catalog.ignitionTable.metadata).validateBeforeWrite(ignitionTable)
                if (!validationResult.isValid) {
                    throw ValidationException(validationResult)
                }
                val serialized = MegaSpeedIniTableDefinitions.serializeIgnitionTable(catalog.ignitionTable, ignitionTable)
                protocol.writeTable(serialized.valuesTableId.toByte(), serialized.valuesOffset, serialized.valuesData)
                protocol.writeTable(serialized.rpmAxisTableId.toByte(), serialized.rpmAxisOffset, serialized.rpmAxisData)
                protocol.writeTable(serialized.loadAxisTableId.toByte(), serialized.loadAxisOffset, serialized.loadAxisData)
                delay(300)
                serialized.burnTableIds.sorted().forEach { protocol.burnTable(it.toByte()) }
                Logger.d(TAG, "✅ MegaSpeed Ignition Table 1 gravada via .ini e burn executado")
                return
            }
        }
        val validationResult = TableValidator(Ms2TableDefinitions.IGNITION_TABLE_1.metadata)
            .validateBeforeWrite(ignitionTable)
        if (!validationResult.isValid) {
            throw ValidationException(validationResult)
        }

        val serialized = Ms2TableDefinitions.serializeIgnitionTable(ignitionTable)
        protocol.writeTable(serialized.valuesTableId.toByte(), serialized.valuesOffset, serialized.valuesData)
        protocol.writeTable(serialized.rpmAxisTableId.toByte(), serialized.rpmAxisOffset, serialized.rpmAxisData)
        protocol.writeTable(serialized.loadAxisTableId.toByte(), serialized.loadAxisOffset, serialized.loadAxisData)
        delay(300)
        serialized.burnTableIds.sorted().forEach { protocol.burnTable(it.toByte()) }
        Logger.d(TAG, "✅ MS2 Ignition Table 1 gravada e burn executado")
    }

    private suspend fun writeMs3AfrTable(afrTable: AfrTable) {
        val serialized = Ms3TableDefinitions.serializeAfrTable(afrTable)
        protocol.writeTable(serialized.valuesTableId.toByte(), serialized.valuesOffset, serialized.valuesData)
        protocol.writeTable(serialized.rpmAxisTableId.toByte(), serialized.rpmAxisOffset, serialized.rpmAxisData)
        protocol.writeTable(serialized.loadAxisTableId.toByte(), serialized.loadAxisOffset, serialized.loadAxisData)
        delay(300)
        serialized.burnTableIds.sorted().forEach { protocol.burnTable(it.toByte()) }
        Logger.d(TAG, "✅ MS3 AFR Table 1 gravada e burn executado")
    }

    private suspend fun writeMs2AfrTable(afrTable: AfrTable) {
        if (firmwareInfo?.family == EcuFamily.MEGASPEED) {
            megaSpeedIniCatalog?.let { catalog ->
                val serialized = MegaSpeedIniTableDefinitions.serializeAfrTable(catalog.afrTable, afrTable)
                protocol.writeTable(serialized.valuesTableId.toByte(), serialized.valuesOffset, serialized.valuesData)
                protocol.writeTable(serialized.rpmAxisTableId.toByte(), serialized.rpmAxisOffset, serialized.rpmAxisData)
                protocol.writeTable(serialized.loadAxisTableId.toByte(), serialized.loadAxisOffset, serialized.loadAxisData)
                delay(300)
                serialized.burnTableIds.sorted().forEach { protocol.burnTable(it.toByte()) }
                Logger.d(TAG, "✅ MegaSpeed AFR Table 1 gravada via .ini e burn executado")
                return
            }
        }
        val serialized = Ms2TableDefinitions.serializeAfrTable(afrTable)
        protocol.writeTable(serialized.valuesTableId.toByte(), serialized.valuesOffset, serialized.valuesData)
        protocol.writeTable(serialized.rpmAxisTableId.toByte(), serialized.rpmAxisOffset, serialized.rpmAxisData)
        protocol.writeTable(serialized.loadAxisTableId.toByte(), serialized.loadAxisOffset, serialized.loadAxisData)
        delay(300)
        serialized.burnTableIds.sorted().forEach { protocol.burnTable(it.toByte()) }
        Logger.d(TAG, "✅ MS2 AFR Table 1 gravada e burn executado")
    }

    private suspend fun writeRusefiVeTable(veTable: VeTable) {
        rusefiIniCatalog?.let { catalog ->
            val validationResult = TableValidator(catalog.veTable.metadata).validateBeforeWrite(veTable)
            if (!validationResult.isValid) {
                throw ValidationException(validationResult)
            }
            val serialized = RusefiTableDefinitions.serializeVeTableWithLayout(
                layout = catalog.veTable,
                table = veTable,
                signedValues = false,
                valueScale = 10,
            )
        protocol.writeTable(serialized.valuesTableId, serialized.valuesOffset, serialized.valuesData, EcuFamily.RUSEFI)
        protocol.writeTable(serialized.rpmAxisTableId, serialized.rpmAxisOffset, serialized.rpmAxisData, EcuFamily.RUSEFI)
        protocol.writeTable(serialized.loadAxisTableId, serialized.loadAxisOffset, serialized.loadAxisData, EcuFamily.RUSEFI)
        delay(300)
        serialized.burnTableIds.sorted().forEach { burnTableIfSupported(it, EcuFamily.RUSEFI) }
        pendingRusefiVeTableReadback = veTable.copy()
        Logger.d(TAG, "✅ rusEFI VE Table 1 gravada via .ini e burn executado")
        return
        }
        val schemaId = ecuDefinition?.runtime?.schemaId ?: "rusefi-main"
        val isF407Discovery = schemaId == "rusefi-f407-discovery"
        val metadata = if (isF407Discovery) RusefiF407DiscoveryDefinitions.VE_TABLE_1.metadata else RusefiTableDefinitions.VE_TABLE_1.metadata
        val validationResult = TableValidator(metadata)
            .validateBeforeWrite(veTable)
        if (!validationResult.isValid) {
            throw ValidationException(validationResult)
        }

        val serialized = if (isF407Discovery) {
            RusefiF407DiscoveryDefinitions.serializeVeTable(veTable)
        } else {
            RusefiTableDefinitions.serializeVeTable(veTable)
        }
        protocol.writeTable(serialized.valuesTableId, serialized.valuesOffset, serialized.valuesData, EcuFamily.RUSEFI)
        protocol.writeTable(serialized.rpmAxisTableId, serialized.rpmAxisOffset, serialized.rpmAxisData, EcuFamily.RUSEFI)
        protocol.writeTable(serialized.loadAxisTableId, serialized.loadAxisOffset, serialized.loadAxisData, EcuFamily.RUSEFI)
        delay(300)
        serialized.burnTableIds.sorted().forEach { burnTableIfSupported(it, EcuFamily.RUSEFI) }
        pendingRusefiVeTableReadback = veTable.copy()
        Logger.d(TAG, "✅ rusEFI VE Table 1 gravada e burn executado")
    }

    private suspend fun writeRusefiIgnitionTable(ignitionTable: IgnitionTable) {
        rusefiIniCatalog?.let { catalog ->
            val validationResult = TableValidator(catalog.ignitionTable.metadata).validateBeforeWrite(ignitionTable)
            if (!validationResult.isValid) {
                throw ValidationException(validationResult)
            }
            val serialized = RusefiTableDefinitions.serializeIgnitionTableWithLayout(catalog.ignitionTable, ignitionTable)
        protocol.writeTable(serialized.valuesTableId, serialized.valuesOffset, serialized.valuesData, EcuFamily.RUSEFI)
        protocol.writeTable(serialized.rpmAxisTableId, serialized.rpmAxisOffset, serialized.rpmAxisData, EcuFamily.RUSEFI)
        protocol.writeTable(serialized.loadAxisTableId, serialized.loadAxisOffset, serialized.loadAxisData, EcuFamily.RUSEFI)
        delay(300)
        serialized.burnTableIds.sorted().forEach { burnTableIfSupported(it, EcuFamily.RUSEFI) }
        pendingRusefiIgnitionTableReadback = ignitionTable.copy()
        Logger.d(TAG, "✅ rusEFI Ignition Table 1 gravada via .ini e burn executado")
        return
        }
        val schemaId = ecuDefinition?.runtime?.schemaId ?: "rusefi-main"
        val isF407Discovery = schemaId == "rusefi-f407-discovery"
        val metadata = if (isF407Discovery) RusefiF407DiscoveryDefinitions.IGNITION_TABLE_1.metadata else RusefiTableDefinitions.IGNITION_TABLE_1.metadata
        val validationResult = TableValidator(metadata)
            .validateBeforeWrite(ignitionTable)
        if (!validationResult.isValid) {
            throw ValidationException(validationResult)
        }

        val serialized = if (isF407Discovery) {
            RusefiF407DiscoveryDefinitions.serializeIgnitionTable(ignitionTable)
        } else {
            RusefiTableDefinitions.serializeIgnitionTable(ignitionTable)
        }
        protocol.writeTable(serialized.valuesTableId, serialized.valuesOffset, serialized.valuesData, EcuFamily.RUSEFI)
        protocol.writeTable(serialized.rpmAxisTableId, serialized.rpmAxisOffset, serialized.rpmAxisData, EcuFamily.RUSEFI)
        protocol.writeTable(serialized.loadAxisTableId, serialized.loadAxisOffset, serialized.loadAxisData, EcuFamily.RUSEFI)
        delay(300)
        serialized.burnTableIds.sorted().forEach { burnTableIfSupported(it, EcuFamily.RUSEFI) }
        pendingRusefiIgnitionTableReadback = ignitionTable.copy()
        Logger.d(TAG, "✅ rusEFI Ignition Table 1 gravada e burn executado")
    }

    private suspend fun writeRusefiAfrTable(afrTable: AfrTable) {
        rusefiIniCatalog?.let { catalog ->
            val serialized = RusefiTableDefinitions.serializeAfrTableWithLayout(catalog.afrTable, afrTable)
        protocol.writeTable(serialized.valuesTableId, serialized.valuesOffset, serialized.valuesData, EcuFamily.RUSEFI)
        protocol.writeTable(serialized.rpmAxisTableId, serialized.rpmAxisOffset, serialized.rpmAxisData, EcuFamily.RUSEFI)
        protocol.writeTable(serialized.loadAxisTableId, serialized.loadAxisOffset, serialized.loadAxisData, EcuFamily.RUSEFI)
        delay(300)
        serialized.burnTableIds.sorted().forEach { burnTableIfSupported(it, EcuFamily.RUSEFI) }
        Logger.d(TAG, "✅ rusEFI AFR Table 1 gravada via .ini e burn executado")
        return
        }
        val schemaId = ecuDefinition?.runtime?.schemaId ?: "rusefi-main"
        val isF407Discovery = schemaId == "rusefi-f407-discovery"
        val serialized = if (isF407Discovery) {
            RusefiF407DiscoveryDefinitions.serializeAfrTable(afrTable)
        } else {
            RusefiTableDefinitions.serializeAfrTable(afrTable)
        }
        protocol.writeTable(serialized.valuesTableId, serialized.valuesOffset, serialized.valuesData, EcuFamily.RUSEFI)
        protocol.writeTable(serialized.rpmAxisTableId, serialized.rpmAxisOffset, serialized.rpmAxisData, EcuFamily.RUSEFI)
        protocol.writeTable(serialized.loadAxisTableId, serialized.loadAxisOffset, serialized.loadAxisData, EcuFamily.RUSEFI)
        delay(300)
        serialized.burnTableIds.sorted().forEach { burnTableIfSupported(it, EcuFamily.RUSEFI) }
        Logger.d(TAG, "✅ rusEFI AFR Table 1 gravada e burn executado")
    }

    /**
     * Lê dados em tempo real
     * Em conexões legacy-first (USB/Bluetooth), tenta Legacy primeiro e Modern como fallback.
     * Nas demais, tenta Modern primeiro e faz fallback para Legacy.
     */
    suspend fun readLiveData(): SpeeduinoLiveData = withContext(Dispatchers.IO) {
        val ecuFamily = firmwareInfo?.family ?: EcuFamily.UNKNOWN
        val outputSize = ecuDefinition?.runtime?.liveDataBlockSize
            ?: ecuDefinition?.runtime?.blockSize
            ?: tableDefinitions?.ochBlockSize
            ?: 0
        val isModernEra = firmwareInfo?.era?.isModern() == true
        // Firmware com envelope (202201+) não entende comando legacy solto: 'A' é lido como o
        // byte alto de um comprimento, e a ECU trava esperando um payload gigante. Nesses casos
        // o live data é modern, em qualquer transporte, e nunca tenta legacy primeiro.
        val usesModernEnvelope = firmwareInfo?.era?.usesModernEnvelope() == true
        val canTryModern = ecuFamily == EcuFamily.SPEEDUINO &&
            (
                usesModernEnvelope ||
                    connection.supportsModernProtocol() ||
                    connection.supportsModernProtocolFallback()
                ) &&
            outputSize > 0 &&
            isModernEra
        val preferLegacyFirst = connection.prefersLegacyProtocol() && !usesModernEnvelope
        var usedModernRead = false

        val data = if (ecuFamily == EcuFamily.RUSEFI) {
            try {
                drainInputBufferQuietly()
                protocol.readRusefiOutputChannels(outputSize)
            } catch (e: Exception) {
                if (connection.isConnected()) {
                    connection.clearInputBuffer()
                }
                throw e
            }
        } else if (canTryModern && preferLegacyFirst) {
            try {
                protocol.readLiveData(outputSize.takeIf { it > 0 } ?: 128)
            } catch (legacyError: Exception) {
                if (shouldSkipLiveDataFallback(legacyError)) {
                    throw legacyError
                }
                if (isRecoverableLiveDataTimeout(legacyError)) {
                    throw legacyError
                }
                if (!connection.isConnected()) {
                    Logger.e(TAG, "Conexão perdida durante fallback para modern live data")
                    throw Exception("Não conectado")
                }

                Logger.w(TAG, "Legacy live data falhou, tentando modern fallback: ${legacyError.message}")
                connection.clearInputBuffer()
                val maxAttempts = 2
                var attempt = 0
                var modernData: ByteArray? = null
                var lastModernError: Exception? = null

                while (attempt < maxAttempts && modernData == null) {
                    attempt++
                    try {
                        modernData = protocol.readLiveDataModern(outputSize)
                        usedModernRead = true
                    } catch (modernError: Exception) {
                        lastModernError = modernError
                        Logger.w(TAG, "Modern fallback falhou (tentativa $attempt/$maxAttempts): ${modernError.message}")
                        if (attempt < maxAttempts) {
                            connection.clearInputBuffer()
                            delay(25)
                        }
                    }
                }

                modernData ?: throw Exception(
                    "Legacy live data falhou (${legacyError.message}); modern fallback também falhou (${lastModernError?.message})"
                )
            }
        } else if (canTryModern) {
            var lastError: Exception? = null
            val maxAttempts = 2
            var attempt = 0
            var modernData: ByteArray? = null

            while (attempt < maxAttempts && modernData == null) {
                attempt++
                try {
                    // ✅ Tenta Modern Protocol primeiro (Speeduino 2020+)
                    modernData = protocol.readLiveDataModern(outputSize)
                    usedModernRead = true
                } catch (e: Exception) {
                    if (shouldSkipLiveDataFallback(e)) {
                        throw e
                    }
                    if (!connection.isConnected()) {
                        Logger.e(TAG, "Conexão perdida durante leitura de live data")
                        throw Exception("Não conectado")
                    }

                    lastError = e
                    Logger.w(TAG, "Modern Protocol falhou (tentativa $attempt/$maxAttempts): ${e.message}")
                    connection.clearInputBuffer()
                    if (attempt < maxAttempts) {
                        delay(25)
                    }
                }
            }

            if (modernData == null && usesModernEnvelope) {
                // Nunca cair para legacy num firmware com envelope: o 'A' solto seria lido como
                // byte alto de um comprimento e deixaria a ECU presa esperando payload.
                throw lastError ?: Exception("Modern live data falhou após $maxAttempts tentativas")
            } else if (modernData == null) {
                Logger.w(TAG, "Modern Protocol falhou após $maxAttempts tentativas, tentando Legacy: ${lastError?.message}")
                // ⚠️ Fallback para Legacy Protocol (versões antigas)
                protocol.readLiveData(outputSize.takeIf { it > 0 } ?: 128)
            } else {
                modernData
            }
        } else {
            protocol.readLiveData(outputSize.takeIf { it > 0 } ?: 128)
        }

        if (outputSize > 0 && data.size != outputSize) {
            val mode = if (canTryModern) "modern_or_fallback" else "legacy"
            val message = "oc_mismatch family=${ecuFamily.name} mode=$mode expected=$outputSize actual=${data.size}"
            Logger.w(TAG, "⚠ $message")
            ConnectionTrace.info("live_data", message)
        }

        val isModernData = usedModernRead && data.size == outputSize
        // When legacy command returns exactly ochBlockSize bytes for a known modern-era ECU,
        // the data is still the modern OCH block — use the modern parser (not fromLegacyFrame).
        val isModernFrameBySize = !usedModernRead &&
            isModernEra &&
            ecuFamily == EcuFamily.SPEEDUINO &&
            outputSize > 0 &&
            data.size == outputSize
        if (isModernFrameBySize) {
            ConnectionTrace.info("live_data", "legacy command returned modern-sized frame; using modern parser (size=$outputSize)")
        }
        val liveData = when {
            ecuDefinition?.runtime?.configReadMode == EcuConfigReadMode.LEGACY_PAGE -> {
                SpeeduinoLiveDataParser.fromLegacyFrame(data)
            }
            ecuFamily == EcuFamily.RUSEFI && data.size >= outputSize && outputSize > 0 -> {
                RusefiLiveDataParser.fromOutputChannels(data)
            }
            (ecuFamily == EcuFamily.MS2 || ecuFamily == EcuFamily.MEGASPEED) && data.size >= outputSize && outputSize > 0 -> {
                Ms2LiveDataParser.fromOutputChannels(data)
            }
            ecuFamily == EcuFamily.MS3 && data.size >= outputSize && outputSize > 0 -> {
                Ms3LiveDataParser.fromOutputChannels(data)
            }
            isModernData || isModernFrameBySize -> {
                parseOutputChannelsWithFallback(data)
            }
            else -> {
                SpeeduinoLiveDataParser.fromLegacyFrame(data)
            }
        }
        logLiveDataSample(data, liveData, isModernData || isModernFrameBySize)
        liveData
    }

    // ==================== Live Data Streaming ====================

    /**
     * Inicia stream contínuo de dados em tempo real
     */
    override fun startLiveDataStream(intervalMs: Long) {
        if (_isStreaming) {
            Logger.w(TAG, "Stream já está ativo, ignorando nova requisição")
            return
        }

        if (firmwareInfo?.capabilities?.supportsLiveData == false) {
            Logger.w(TAG, "Live data não suportado para ${firmwareInfo?.family}")
            return
        }

        Logger.d(TAG, "Iniciando live data stream (intervalo: ${intervalMs}ms)")

        // Cancelar job anterior se existir
        streamJob?.cancel()
        _isStreaming = true
        liveDataStreamStopRequested = false
        lastLiveDataStreamStartedAtMs = MonotonicClock.nowMillis()
        consecutiveFaultyLiveDataSamples = 0
        pendingLiveDataRecoveryReason = null

        streamJob = scope.launch {
            val intervalNs = intervalMs.coerceAtLeast(1L) * 1_000_000L
            var nextTickNs = MonotonicClock.nowNanos()
            var packetCount = 0
            var recoverableReadTimeouts = 0
            var restartReason: String? = null
            while (_isStreaming && connection.isConnected()) {
                try {
                    val liveData = readLiveData()
                    recoverableReadTimeouts = 0
                    onDataReceived(liveData)
                    packetCount++

                    consumePendingLiveDataRecoveryReason()?.let { reason ->
                        restartReason = reason
                        Logger.w(TAG, "Reiniciando stream após amostras inválidas: $reason")
                        ConnectionTrace.info("live_data", "recovering stream after $reason")
                        connection.clearInputBuffer()
                        break
                    }

                    // Log a cada 50 pacotes (~5 segundos com intervalo de 100ms)
                    if (packetCount % 50 == 0) {
                        Logger.d(TAG, "Stream ativo: $packetCount pacotes recebidos (RPM: ${liveData.rpm})")
                    }

                    nextTickNs += intervalNs
                    val remainingNs = nextTickNs - MonotonicClock.nowNanos()
                    if (remainingNs > 0L) {
                        delay(remainingNs / 1_000_000L)
                    } else {
                        // Se a leitura ficou mais lenta que o alvo, realinha o relógio sem acumular
                        // drift — mas sempre respeitando um gap mínimo, para nunca emitir comandos
                        // colados uns nos outros num transporte lento.
                        val gapMs = LIVE_STREAM_MIN_COMMAND_GAP_MS.coerceAtMost(intervalMs)
                        delay(gapMs)
                        nextTickNs = MonotonicClock.nowNanos()
                    }
                } catch (_: CancellationException) {
                    // Stream cancelado por troca de fluxo (pause/restart/disconnect).
                    // Isso não é erro de protocolo/transporte.
                    break
                } catch (e: Exception) {
                    val isRecoverableLiveTimeout =
                        _isStreaming &&
                            connection.isConnected() &&
                            isRecoverableLiveDataTimeout(e)
                    if (isRecoverableLiveTimeout) {
                        recoverableReadTimeouts++
                        ConnectionTrace.info(
                            "live_data",
                            "recoverable_timeout attempt=$recoverableReadTimeouts message=${e.message ?: "unknown"}"
                        )
                        Logger.w(
                            TAG,
                            "Timeout parcial no stream (${recoverableReadTimeouts}/${LIVE_STREAM_RECOVERABLE_TIMEOUT_LIMIT}): ${e.message}"
                        )
                        connection.clearInputBuffer()

                        if (recoverableReadTimeouts < LIVE_STREAM_RECOVERABLE_TIMEOUT_LIMIT) {
                            delay(intervalMs.coerceAtLeast(25L))
                            continue
                        }

                        Logger.e(
                            TAG,
                            "Falha no stream após $recoverableReadTimeouts timeouts parciais consecutivos"
                        )
                    }

                    if (_isStreaming) { // Only report error if still streaming
                        Logger.e(TAG, "Erro no stream: ${e.message}", e)
                        if (packetCount == 0) {
                            ConnectionTrace.error(
                                "live_data",
                                "stream_failed_before_first_packet message=${e.message ?: "unknown"}",
                                e
                            )
                        }
                        onError("Erro no stream: ${e.message}")

                        if (!connection.isConnected()) {
                            Logger.w(TAG, "🔴 Conexão perdida detectada durante stream")
                        } else if (shouldDisconnectAfterStreamFailure(e)) {
                            Logger.w(
                                TAG,
                                "🔴 Stream falhou com socket ainda marcado como conectado; forçando disconnect"
                            )
                            disconnect()
                        }
                    }
                    break
                }
            }
            Logger.d(TAG, "Stream finalizado (total: $packetCount pacotes)")
            _isStreaming = false
            if (streamJob === coroutineContext[Job]) {
                streamJob = null
            }
            if (restartReason != null && connection.isConnected()) {
                delay(150)
                startLiveDataStream(intervalMs)
            }
        }
    }

    // internal (não private) para permitir teste direto da classificação de erro, que decide
    // se o stream apenas repete o comando legacy ou escala para um caminho mais agressivo.
    internal fun isRecoverableLiveDataTimeout(error: Exception): Boolean {
        val message = error.message ?: return false
        if (message.startsWith("Timeout: no data received")) {
            return true
        }
        // Resposta truncada é o caso comum num link Bluetooth ruidoso e tem o mesmo tratamento
        // de um timeout parcial: limpar o buffer e repetir o comando legacy. Sem isso o erro
        // escalava para o fallback modern, que não é seguro por aqui (ver canAttemptModernLiveData).
        if (INCOMPLETE_LIVE_DATA_REGEX.containsMatchIn(message)) {
            return true
        }
        val match = PARTIAL_TIMEOUT_REGEX.find(message) ?: return false
        val received = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return false
        return received >= 0
    }

    private fun shouldDisconnectAfterStreamFailure(error: Exception): Boolean {
        if (!connection.isConnected() || liveDataStreamStopRequested) {
            return false
        }

        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty()
            if (
                message.startsWith("Timeout:", ignoreCase = true) ||
                message.contains("Read timed out", ignoreCase = true) ||
                message.contains("Conexão encerrada pelo remoto", ignoreCase = true) ||
                message.contains("Connection reset", ignoreCase = true) ||
                message.contains("Broken pipe", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * Para stream de dados
     */
    override fun stopLiveDataStream() {
        Logger.d(TAG, "Parando live data stream...")
        liveDataStreamStopRequested = true
        _isStreaming = false
        pendingLiveDataRecoveryReason = null
        connection.abortPendingRead()
        streamJob?.cancel()
        streamJob = null
    }

    private suspend fun resolveEngineConstantsOrNull(): EngineConstants? {
        return cachedEngineConstants ?: run {
            try {
                readEngineConstants()
            } catch (e: Exception) {
                Logger.w(TAG, "NÇœo foi possÇðvel ler Engine Constants para detectar loadType: ${e.message}")
                null
            }
        }
    }

    override suspend fun readMapSelectionSupport(): MapSelectionSupport {
        if (firmwareInfo?.family != EcuFamily.SPEEDUINO) {
            return MapSelectionSupport()
        }
        if (activeIniDefinition == null) {
            return MapSelectionSupport()
        }

        val veMaps = mutableListOf(1)
        val ignitionMaps = mutableListOf(1)

        for (index in 2..4) {
            val veTableExists = findIniFieldByName("veTable$index") != null
            val veMode = readIniFieldNumericValue("fuel${index}Mode")
            if (veTableExists && (veMode ?: 0) > 0) {
                veMaps += index
            }

            val ignTableExists = findIniFieldByName("advTable$index") != null
            val ignMode = readIniFieldNumericValue("spark${index}Mode")
            if (ignTableExists && (ignMode ?: 0) > 0) {
                ignitionMaps += index
            }
        }

        return MapSelectionSupport(
            veMapIndices = veMaps.distinct().sorted(),
            ignitionMapIndices = ignitionMaps.distinct().sorted(),
        )
    }

    private suspend fun resolveVeLoadType(mapIndex: Int = 1): VeTable.LoadType {
        val engineConstants = resolveEngineConstantsOrNull()
        if (mapIndex > 1) {
            val algorithmBits = readIniFieldNumericValue("fuel${mapIndex}Algorithm")
            return TableDomainFacade.resolveVeLoadType(engineConstants, algorithmBits)
        }
        return TableDomainFacade.resolveVeLoadType(engineConstants)
    }

    private suspend fun resolveIgnitionLoadType(mapIndex: Int = 1): IgnitionTable.LoadType {
        val engineConstants = resolveEngineConstantsOrNull()
        if (mapIndex > 1) {
            val algorithmBits = readIniFieldNumericValue("spark${mapIndex}Algorithm")
            return TableDomainFacade.resolveIgnitionLoadType(engineConstants, algorithmBits)
        }
        return TableDomainFacade.resolveIgnitionLoadType(engineConstants)
    }

    private suspend fun resolveAfrLoadType(isLegacyFormat: Boolean = false): AfrTable.LoadType {
        return TableDomainFacade.resolveAfrLoadType(resolveEngineConstantsOrNull(), isLegacyFormat)
    }

    private suspend fun isMapLoad(): Boolean {
        return TableDomainFacade.isMapLoad(resolveEngineConstantsOrNull())
    }

    private fun findIniFieldByName(name: String) =
        activeIniDefinition?.fields?.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private suspend fun readIniFieldNumericValue(name: String): Int? {
        val field = findIniFieldByName(name) ?: return null
        val pageId = field.page ?: return null
        val offset = field.offset ?: return null
        val length = when (field.dataType.trim().uppercase()) {
            "U16", "S16" -> 2
            else -> 1
        }
        val data = readConfigChunk(pageId, offset, length)
        if (data.size < length) return null

        val rawValue = when (field.dataType.trim().uppercase()) {
            "U16", "S16" -> (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
            else -> data[0].toInt() and 0xFF
        }

        if (field.kind != IniFieldKind.BITS) {
            return rawValue
        }

        val bitRange = Regex("""\[(\d+):(\d+)]""")
            .find(field.rawDefinition)
            ?.groupValues
            ?.drop(1)
            ?.mapNotNull { it.toIntOrNull() }
            ?: return rawValue
        if (bitRange.size != 2) return rawValue

        val start = bitRange[0]
        val end = bitRange[1]
        if (start > end) return rawValue
        val width = (end - start + 1).coerceAtMost(31)
        val mask = (1 shl width) - 1
        return (rawValue ushr start) and mask
    }

    private fun resolveSpeeduinoTableMetadata(
        tableNames: List<String>,
        fallback: TableMetadata,
        displayName: String,
    ): TableMetadata {
        val field = tableNames.firstNotNullOfOrNull { findIniFieldByName(it) } ?: return fallback
        val shape = field.shape
        return TableDomainFacade.resolveSpeeduinoTableMetadata(
            fieldDataType = field.dataType,
            fieldPage = field.page,
            fieldOffset = field.offset,
            fieldRows = shape?.rows,
            fieldColumns = shape?.columns,
            fallback = fallback,
            displayName = displayName,
        )
    }

    private data class Ms2Alias(
        val pageId: Int,
        val offset: Int,
    )

    private fun resolveMs2Alias(pageNum: Int, offset: Int, length: Int): Ms2Alias? {
        val family = firmwareInfo?.family ?: return null
        if (offset != 0 || length != 288) {
            return null
        }
        return when (pageNum) {
            11 -> when (family) {
                EcuFamily.MS3 -> Ms2Alias(pageId = 0x0C, offset = 0)
                EcuFamily.MS2, EcuFamily.MEGASPEED -> Ms2Alias(pageId = 0x09, offset = 256)
                else -> null
            }
            14 -> when (family) {
                EcuFamily.MS3 -> Ms2Alias(pageId = 0x0D, offset = 0)
                EcuFamily.MS2, EcuFamily.MEGASPEED -> Ms2Alias(pageId = 0x0A, offset = 288)
                else -> null
            }
            else -> null
        }
    }

    private fun isModernTablePage(pageNum: Int, length: Int): Boolean {
        return length == 288 && pageNum in setOf(11, 14)
    }

    private suspend fun burnTableIfSupported(tableId: Int, family: EcuFamily) {
        runCatching {
            protocol.burnTable(tableId = tableId, family = family)
        }.onFailure { error ->
            val message = error.message.orEmpty()
            val notBurnable = message.contains("0x84") ||
                message.contains("RANGE_ERR", ignoreCase = true) ||
                message.contains("Valor fora do range", ignoreCase = true) ||
                message.contains("Incomplete modern response", ignoreCase = true) ||
                message.contains("received 0 bytes", ignoreCase = true) ||
                message.contains("sem resposta", ignoreCase = true)
            if (family == EcuFamily.MS3 && notBurnable) {
                Logger.w(
                    TAG,
                    "Ignorando burn não suportado para tabela ${formatPageId(tableId)} em MS3: $message"
                )
                return
            }
            throw error
        }
    }

    private suspend fun settleAfterConfigWrite() {
        delay(250)
        runCatching { connection.clearInputBuffer() }
    }

    private fun readU8(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xFF

    private fun readS8(data: ByteArray, offset: Int): Int = data[offset].toInt()

    private fun readU16(data: ByteArray, offset: Int): Int {
        val lsb = readU8(data, offset)
        val msb = readU8(data, offset + 1)
        return lsb or (msb shl 8)
    }

    private fun writeU8(data: ByteArray, offset: Int, value: Int) {
        data[offset] = value.coerceIn(0, 255).toByte()
    }

    private fun writeS8(data: ByteArray, offset: Int, value: Int) {
        data[offset] = value.coerceIn(-128, 127).toByte()
    }

    private fun writeU16(data: ByteArray, offset: Int, value: Int) {
        val clamped = value.coerceIn(0, 65535)
        data[offset] = (clamped and 0xFF).toByte()
        data[offset + 1] = ((clamped shr 8) and 0xFF).toByte()
    }

    private fun ensureWritable(operation: String) {
        if (readOnlySafeModeEnabled) {
            throw IllegalStateException(
                "Read-only safe mode enabled ($operation blocked). Disable manual firmware profile to write."
            )
        }
    }

    /**
     * Pausa stream aguardando o ciclo atual finalizar para evitar respostas pendentes.
     */
    override suspend fun pauseLiveDataStream(timeoutMs: Long) {
        if (!_isStreaming) {
            return
        }

        Logger.d(TAG, "Pausando live data stream...")
        liveDataStreamStopRequested = true
        _isStreaming = false
        connection.abortPendingRead()

        val job = streamJob
        if (job != null) {
            val finished = withTimeoutOrNull(timeoutMs) {
                job.join()
                true
            } ?: false

            if (!finished) {
                Logger.w(TAG, "Timeout aguardando stream, cancelando job")
                job.cancel()
                withTimeoutOrNull(500) {
                    job.join()
                }
            }
        }

        streamJob = null
        pendingLiveDataRecoveryReason = null
        connection.clearInputBuffer()
        liveDataStreamStopRequested = false
    }

    private fun shouldSkipLiveDataFallback(error: Exception): Boolean {
        if (liveDataStreamStopRequested) {
            return true
        }
        return error.message?.contains("Read aborted", ignoreCase = true) == true
    }

    // ==================== Data Parsing ====================

    /**
     * Parse live data packet (127 bytes)
     * Based on Speeduino logger.cpp getTSLogEntry function
     */
    private fun parseLiveData(data: ByteArray): SpeeduinoLiveData {
        return SpeeduinoLiveDataParser.fromLegacyFrame(data)
    }

    private fun parseOutputChannelsWithFallback(data: ByteArray): SpeeduinoLiveData {
        val parsed = SpeeduinoLiveDataParser.fromOutputChannels(data)
        val score = liveDataOutOfRangeScore(parsed)
        if (score < 2 || data[0].toInt() != 0) {
            return parsed
        }

        val shifted = shiftOutputChannels(data)
        val shiftedParsed = SpeeduinoLiveDataParser.fromOutputChannels(shifted)
        val shiftedScore = liveDataOutOfRangeScore(shiftedParsed)

        return if (shiftedScore < score && shiftedScore <= 1) {
            Logger.w(TAG, "Live data parece desalinhado; aplicando shift de 1 byte (score $score -> $shiftedScore)")
            io.ecucore.connection.ConnectionTrace.info(
                "live_data",
                "shifted output channels by 1 byte (score=$score->$shiftedScore)"
            )
            shiftedParsed
        } else {
            parsed
        }
    }

    private fun shiftOutputChannels(data: ByteArray): ByteArray {
        if (data.isEmpty()) {
            return data
        }
        val shifted = ByteArray(data.size)
        data.copyInto(shifted, 0, 1, data.size)
        shifted[shifted.lastIndex] = 0
        return shifted
    }

    private fun liveDataOutOfRangeScore(data: SpeeduinoLiveData): Int {
        var score = 0
        if (data.rpm < 0 || data.rpm > 20000) score++
        if (data.mapPressure >= 256 && data.mapPressure % 256 == 0) score += 2
        if (data.mapPressure > 500) score++
        if (data.tps < 0 || data.tps > 100) score++
        if (data.batteryVoltage < 6.0 || data.batteryVoltage > 20.0) score++
        if (data.coolantTemp < -40 || data.coolantTemp > 170) score++
        if (data.intakeTemp < -40 || data.intakeTemp > 170) score++
        return score
    }

    private var lastFaultSampleAtMs = 0L

    private fun logLiveDataSample(data: ByteArray, liveData: SpeeduinoLiveData, isModern: Boolean) {
        if (!io.ecucore.connection.ConnectionTrace.enabled) {
            return
        }
        val now = MonotonicClock.nowMillis()
        val (shouldReport, score) = shouldReportLiveDataIssue(liveData)
        if (!shouldReport || isWithinLiveDataWarmupWindow(now)) {
            consecutiveFaultyLiveDataSamples = 0
            return
        }

        consecutiveFaultyLiveDataSamples += 1
        if (consecutiveFaultyLiveDataSamples < LIVE_DATA_FAULT_CONSECUTIVE_THRESHOLD) {
            return
        }

        maybeScheduleLiveDataRecovery(now, score)

        liveDataSampleCounter += 1
        if (liveDataSampleCounter % 25 != 0) {
            return
        }
        if (now - lastFaultSampleAtMs < LIVE_DATA_FAULT_REPORT_INTERVAL_MS) {
            return
        }
        lastFaultSampleAtMs = now

        val hex = data.joinToString(" ") { it.toHex02() }
        val message = buildLiveDataFaultMessage(
            data = data,
            liveData = liveData,
            isModern = isModern,
            score = score,
            hexPayload = hex,
            faultCount = consecutiveFaultyLiveDataSamples
        )
        io.ecucore.connection.ConnectionTrace.info("live_data", message)
    }

    private fun isWithinLiveDataWarmupWindow(now: Long = MonotonicClock.nowMillis()): Boolean {
        val startedAt = lastLiveDataStreamStartedAtMs
        return startedAt <= 0L || now - startedAt < LIVE_DATA_STREAM_WARMUP_MS
    }

    private fun maybeScheduleLiveDataRecovery(now: Long, score: Int) {
        if (!_isStreaming || !connection.isConnected()) {
            return
        }
        if (pendingLiveDataRecoveryReason != null) {
            return
        }
        if (now - lastFaultRecoveryAtMs < LIVE_DATA_FAULT_RECOVERY_INTERVAL_MS) {
            return
        }
        lastFaultRecoveryAtMs = now
        pendingLiveDataRecoveryReason =
            "faulty_sample score=$score count=$consecutiveFaultyLiveDataSamples"
    }

    private fun consumePendingLiveDataRecoveryReason(): String? {
        val pending = pendingLiveDataRecoveryReason
        pendingLiveDataRecoveryReason = null
        return pending
    }

    private fun shouldReportLiveDataIssue(liveData: SpeeduinoLiveData): Pair<Boolean, Int> {
        val score = liveDataOutOfRangeScore(liveData)
        return Pair(score >= 3, score)
    }

    private fun buildLiveDataFaultMessage(
        data: ByteArray,
        liveData: SpeeduinoLiveData,
        isModern: Boolean,
        score: Int,
        hexPayload: String,
        faultCount: Int
    ): String {
        val batteryStr = formatDecimal(liveData.batteryVoltage, 1)
        return buildString {
            append("faulty sample score=$score")
            append(" count=$faultCount")
            append(" ${if (isModern) "modern" else "legacy"}")
            append(" len=${data.size}")
            append(" rpm=${liveData.rpm}")
            append(" map=${liveData.mapPressure}")
            append(" tps=${liveData.tps}")
            append(" batt=$batteryStr")
            append(" temp=${liveData.coolantTemp}/${liveData.intakeTemp}")
            append(" bytes=$hexPayload")
        }
    }
}
