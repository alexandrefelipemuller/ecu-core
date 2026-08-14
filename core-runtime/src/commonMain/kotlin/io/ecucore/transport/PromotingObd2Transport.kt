package io.ecucore.transport

import io.ecucore.definition.IniDefinition
import io.ecucore.ecu.FirmwareInfo
import io.ecucore.model.AfrTable
import io.ecucore.model.DwellTable
import io.ecucore.model.ClosedLoopCorrectionConfig
import io.ecucore.model.EcuCapabilities
import io.ecucore.model.EcuFamily
import io.ecucore.model.EcuPageDescriptor
import io.ecucore.model.EngineConstants
import io.ecucore.model.EngineProtectionConfig
import io.ecucore.model.IdleControlSettings
import io.ecucore.model.IgnitionTable
import io.ecucore.model.PinLayoutInfo
import io.ecucore.model.PressureCalibration
import io.ecucore.model.RusefiInputOutputSnapshot
import io.ecucore.model.SecondarySerialConfig
import io.ecucore.model.TableDefinitions
import io.ecucore.model.TpsCalibration
import io.ecucore.model.TriggerSettings
import io.ecucore.model.VeTable
import io.ecucore.protocol.SerialCapability

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.ecucore.transport.EcuTransport
import io.ecucore.transport.shouldPromoteToPsa

/**
 * Usa o fluxo OBD2 genérico como entrada rápida e promove para um fluxo proprietário
 * quando o fingerprint básico do veículo indicar uma família suportada.
 *
 * A promoção nunca é decidida só por heurística: cada candidato (PSA, Renault) é
 * conectado de verdade e só "vence" se seu próprio `connect()` confirmar a detecção
 * (ex.: [RenaultTransport] lança exceção se a sondagem KWP proprietária não achar
 * nenhum hint positivo). O [VehicleBrandHint]/[Obd2Transport.hasWeakIdentitySignal]
 * só decidem SE vale tentar promoção e EM QUE ORDEM tentar - nunca decidem sozinhos
 * que o veículo "é" de uma marca.
 */
class PromotingObd2Transport(
    private val genericTransport: Obd2Transport,
    private val psaTransport: EcuTransport,
    private val renaultTransport: EcuTransport,
    private val diagnosticsSink: ConnectionDiagnosticsSink = NoopConnectionDiagnosticsSink,
) : EcuTransport, Obd2DiagnosticsCapable {
    private val connectMutex = Mutex()
    private var activeTransport: EcuTransport? = null

    override suspend fun connect() {
        connectMutex.withLock {
            val current = activeTransport
            if (current?.isConnected() == true) {
                return
            }

            genericTransport.connect()
            activeTransport = genericTransport

            val brandHint = genericTransport.getVehicleBrandHint()
            val vin = genericTransport.getDetectedVin().orEmpty()
            val weakIdentity = genericTransport.hasWeakIdentitySignal()
            val candidates = buildPromotionCandidates(brandHint, weakIdentity)
            diagnosticsSink.log(
                "autodetect",
                "brand_hint",
                "primary=obd2 brand=$brandHint vin=${vin.ifBlank { "-" }} " +
                    "weak_identity=$weakIdentity candidates=${candidates.joinToString(",") { it.first }}"
            )

            if (candidates.isEmpty()) {
                return
            }

            runCatching { genericTransport.disconnect() }

            for ((label, transport) in candidates) {
                val promotionError = runCatching {
                    transport.connect()
                    activeTransport = transport
                    diagnosticsSink.log("autodetect", "protocol", "selected=${label}_promoted")
                }.exceptionOrNull()

                if (promotionError == null) {
                    return
                }
                if (promotionError is CancellationException) {
                    activeTransport = null
                    throw promotionError
                }
                diagnosticsSink.log(
                    "autodetect",
                    "promotion",
                    "target=$label failed=${promotionError.message ?: promotionError::class.simpleName}"
                )
            }

            genericTransport.connect()
            activeTransport = genericTransport
        }
    }

    /**
     * Só sonda candidatos proprietários quando há um motivo concreto: hint de marca
     * explícito (VIN/calibration ID) ou identidade OBD2 fraca (sem VIN, bitmap Mode 01
     * vazio). Um carro genérico com VIN e PIDs completos não paga custo nenhum de sondagem.
     * A ordem prioriza o candidato sugerido pelo hint; o outro ainda é tentado em seguida
     * porque quem confirma de fato é o `connect()` de cada transporte, não o hint.
     */
    private fun buildPromotionCandidates(
        brandHint: VehicleBrandHint,
        weakIdentity: Boolean,
    ): List<Pair<String, EcuTransport>> {
        if (brandHint == VehicleBrandHint.UNKNOWN && !weakIdentity) {
            return emptyList()
        }
        val renaultCandidate = "renault" to renaultTransport
        val psaCandidate = "psa" to psaTransport
        return if (brandHint == VehicleBrandHint.RENAULT) {
            listOf(renaultCandidate, psaCandidate)
        } else {
            listOf(psaCandidate, renaultCandidate)
        }
    }

    override fun disconnect() {
        runCatching { psaTransport.disconnect() }
        runCatching { genericTransport.disconnect() }
        activeTransport = null
    }

    override fun isConnected(): Boolean = activeTransport?.isConnected() == true

    private fun selected(): EcuTransport = activeTransport ?: genericTransport

    override fun isStreaming(): Boolean = selected().isStreaming()

    override fun startLiveDataStream(intervalMs: Long) = selected().startLiveDataStream(intervalMs)

    override fun stopLiveDataStream() = selected().stopLiveDataStream()

    override suspend fun pauseLiveDataStream(timeoutMs: Long) = selected().pauseLiveDataStream(timeoutMs)

    override suspend fun getFirmwareInfo(): String = selected().getFirmwareInfo()

    override fun getFirmwareInfoCached(): FirmwareInfo? = selected().getFirmwareInfoCached()

    override fun getEcuFamily(): EcuFamily = selected().getEcuFamily()

    override fun getEcuCapabilities(): EcuCapabilities? = selected().getEcuCapabilities()

    override fun getTableDefinitions(): TableDefinitions? = selected().getTableDefinitions()

    override fun getEcuPageCatalog(): List<EcuPageDescriptor> = selected().getEcuPageCatalog()

    override fun getPinLayoutInfoCached(): PinLayoutInfo? = selected().getPinLayoutInfoCached()

    override fun getConnectionProfileTag(): String? = selected().getConnectionProfileTag()

    override fun cachePinLayoutInfo(info: PinLayoutInfo?) = selected().cachePinLayoutInfo(info)

    override fun isReadOnlySafeMode(): Boolean = selected().isReadOnlySafeMode()

    override fun applyIniDefinition(definition: IniDefinition): Boolean = selected().applyIniDefinition(definition)

    override fun setManualFirmwareProfile(signature: String, readOnly: Boolean) {
        genericTransport.setManualFirmwareProfile(signature, readOnly)
        psaTransport.setManualFirmwareProfile(signature, readOnly)
    }

    override suspend fun getSerialCapability(): SerialCapability = selected().getSerialCapability()

    override suspend fun readFullPage(pageNum: Int, pageSize: Int, blockSize: Int): ByteArray =
        selected().readFullPage(pageNum, pageSize, blockSize)

    override suspend fun readVeTable(mapIndex: Int): VeTable = selected().readVeTable(mapIndex)

    override suspend fun readIgnitionTable(mapIndex: Int): IgnitionTable = selected().readIgnitionTable(mapIndex)

    override suspend fun readAfrTable(): AfrTable = selected().readAfrTable()

    override suspend fun readDwellTable(): DwellTable = selected().readDwellTable()

    override suspend fun readEngineConstants(): EngineConstants = selected().readEngineConstants()

    override suspend fun readTriggerSettings(): TriggerSettings = selected().readTriggerSettings()

    override suspend fun readRusefiInputOutputSnapshot(): RusefiInputOutputSnapshot =
        selected().readRusefiInputOutputSnapshot()

    override suspend fun readEngineProtectionConfig(): EngineProtectionConfig =
        selected().readEngineProtectionConfig()

    override suspend fun readIdleControlSettings(): IdleControlSettings = selected().readIdleControlSettings()

    override suspend fun writeIdleControlSettings(settings: IdleControlSettings, burn: Boolean) =
        selected().writeIdleControlSettings(settings, burn)

    override suspend fun readClosedLoopCorrectionConfig() = selected().readClosedLoopCorrectionConfig()

    override suspend fun writeClosedLoopCorrectionConfig(
        config: ClosedLoopCorrectionConfig,
        burn: Boolean,
    ) = selected().writeClosedLoopCorrectionConfig(config, burn)

    override suspend fun readMapSelectionSupport() = selected().readMapSelectionSupport()

    override suspend fun readPressureCalibration(): PressureCalibration = selected().readPressureCalibration()

    override suspend fun writePressureCalibration(calibration: PressureCalibration, burn: Boolean) =
        selected().writePressureCalibration(calibration, burn)

    override suspend fun readTpsCalibration(): TpsCalibration = selected().readTpsCalibration()

    override suspend fun writeTpsCalibration(calibration: TpsCalibration, burn: Boolean) =
        selected().writeTpsCalibration(calibration, burn)

    override suspend fun readSecondarySerialConfig(): SecondarySerialConfig =
        selected().readSecondarySerialConfig()

    override suspend fun writeSecondarySerialConfig(config: SecondarySerialConfig, burn: Boolean) =
        selected().writeSecondarySerialConfig(config, burn)

    override suspend fun writeEngineProtectionConfig(config: EngineProtectionConfig, burn: Boolean) =
        selected().writeEngineProtectionConfig(config, burn)

    override suspend fun writeTriggerSettings(settings: TriggerSettings, burn: Boolean) =
        selected().writeTriggerSettings(settings, burn)

    override suspend fun writeRawPage(pageNum: Int, data: ByteArray) = selected().writeRawPage(pageNum, data)

    override suspend fun writeRawPageWithoutBurn(pageNum: Int, data: ByteArray) =
        selected().writeRawPageWithoutBurn(pageNum, data)

    override suspend fun writeRawPageChunkedWithoutBurn(pageNum: Int, data: ByteArray, chunkSize: Int, startOffset: Int) =
        selected().writeRawPageChunkedWithoutBurn(pageNum, data, chunkSize, startOffset)

    override suspend fun burnConfigs() = selected().burnConfigs()

    override suspend fun burnLastWrittenLegacyPage() = selected().burnLastWrittenLegacyPage()

    override suspend fun writeVeTable(veTable: VeTable, mapIndex: Int) =
        selected().writeVeTable(veTable, mapIndex)

    override suspend fun writeIgnitionTable(ignitionTable: IgnitionTable, mapIndex: Int) =
        selected().writeIgnitionTable(ignitionTable, mapIndex)

    override suspend fun writeAfrTable(afrTable: AfrTable) = selected().writeAfrTable(afrTable)

    override suspend fun writeDwellTable(dwellTable: DwellTable) = selected().writeDwellTable(dwellTable)

    override suspend fun writeEngineConstants(engineConstants: EngineConstants) =
        selected().writeEngineConstants(engineConstants)

    override suspend fun readDtcCodes(): List<DtcCode> =
        (selected() as? Obd2DiagnosticsCapable)?.readDtcCodes() ?: emptyList()

    override suspend fun readPendingDtcCodes(): List<DtcCode> =
        (selected() as? Obd2DiagnosticsCapable)?.readPendingDtcCodes() ?: emptyList()

    override suspend fun clearDtcCodes(): Boolean =
        (selected() as? Obd2DiagnosticsCapable)?.clearDtcCodes() ?: false

    override fun isDiagnosticsAvailable(): Boolean =
        (selected() as? Obd2DiagnosticsCapable)?.isDiagnosticsAvailable() ?: false
}
