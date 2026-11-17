package io.ecucore.transport.sirius32

import io.ecucore.EcuLiveData
import io.ecucore.connection.IEcuConnection
import io.ecucore.ecu.FirmwareInfo
import io.ecucore.model.EcuCapabilities
import io.ecucore.model.EcuFamily
import io.ecucore.model.FirmwareEra
import io.ecucore.model.TableDefinitions
import io.ecucore.shared.Logger
import io.ecucore.shared.MonotonicClock
import io.ecucore.transport.ConnectionDiagnosticsSink
import io.ecucore.transport.DtcCode
import io.ecucore.transport.DtcStatus
import io.ecucore.transport.EcuTransport
import io.ecucore.transport.NoopConnectionDiagnosticsSink
import io.ecucore.transport.Obd2DiagnosticsCapable
import io.ecucore.transport.Obd2InvestigationSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.math.roundToInt

/** Operações avançadas da Sirius 32 (dialeto DDT). Implementado por [Sirius32DdtTransport]. */
interface Sirius32Diagnostics {
    suspend fun readIdentification(): Map<String, String>
    suspend fun readFaults(): List<Sirius32Fault>
    /** Limpa memória de falhas EOBD e não-EOBD (`14 00 00` e `14 FF 00`). */
    suspend fun clearFaults(): Boolean
    /** Lê e decodifica um trame (`21 <id>`): nome -> valor formatado. */
    suspend fun readFrame(id: Int): Map<String, String>
    suspend fun resetAdaptive(reset: Sirius32Actions.AdaptiveReset): Boolean
    suspend fun runSpecialFunction(function: Sirius32Actions.SpecialFunction): Boolean
    suspend fun stopSpecialFunctions(): Boolean
    suspend fun startActuator(actuator: Sirius32Actions.Actuator, cycles: Int): Boolean
    suspend fun stopActuator(actuator: Sirius32Actions.Actuator): Boolean
    /** Sequência SOMENTE LEITURA para validar o dialeto em um carro. Devolve o relatório em texto. */
    suspend fun runCompatibilityProbe(): String
}

/**
 * Transporte para a Siemens Sirius 32 no dialeto KWP2000 "Renault" (DDT2000):
 * `10 A0`, `21 <trame>`, `17 FF 00`, `14 <tipo> 00`, `11 <reset>`, `30 <atuador> ...`, `31 A0 xx`.
 *
 * Referência: perfil `SIRIUS32 - EMS3132 - Soft RE0_A30` (ver [Sirius32Ddt]). Nada disso
 * foi validado em carro ainda - por isso existe [runCompatibilityProbe].
 *
 * ELM327: endereço ECU 0x7A, cabeçalho `81 7A F1`, init 5-baud (ATSP4/ATSI) com fallback
 * para fast init (ATSP5/ATFI) - mesma receita do perfil ISO do [io.ecucore.transport.RenaultTransport].
 */
class Sirius32DdtTransport(
    private val connection: IEcuConnection,
    private val onDataReceived: (EcuLiveData) -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val investigationRecorder: Obd2InvestigationSink? = null,
    private val diagnosticsSink: ConnectionDiagnosticsSink = NoopConnectionDiagnosticsSink,
    private val ecuAddress: String = "7A",
) : EcuTransport, Obd2DiagnosticsCapable, Sirius32Diagnostics {
    companion object {
        private const val TAG = "Sirius32DdtTransport"
        private const val COMMAND_TIMEOUT_MS = 1500L
        private const val BUS_INIT_TIMEOUT_MS = 9000L
        private const val CYCLE_MIN_MS = 250L
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val ioMutex = Mutex()
    private var streamJob: Job? = null
    @Volatile private var streaming = false
    @Volatile private var connected = false
    @Volatile private var cachedFirmwareInfo: FirmwareInfo? = null
    private var lastSample: EcuLiveData? = null

    // ---- Conexão ------------------------------------------------------------------

    override suspend fun connect() {
        try {
            if (!connection.isConnected()) connection.connect()
            investigationRecorder?.startSession(
                transport = "sirius32_ddt",
                metadataExtras = mapOf("connection" to connection.getConnectionInfo().trim().ifBlank { "unknown" }),
            )
            ioMutex.withLock {
                var ok = false
                for (mode in listOf("slow", "fast")) {
                    initElmLocked(mode)
                    // 21 80 (identificação) é a sonda: só uma ECU no dialeto certo responde 61 80.
                    val id = sendLocked("2180", BUS_INIT_TIMEOUT_MS)
                    if (Sirius32Ddt.trameBytes(0x80, id) != null) { ok = true; break }
                    investigationRecorder?.info("connect", "init=$mode sem resposta 61 80: ${id.take(80)}")
                }
                if (!ok) throw IllegalStateException("Sirius 32 (DDT): a central não respondeu a 21 80")
                // Sessão é opcional: algumas versões respondem 21 sem 10 A0.
                openSessionLocked()
            }
            cachedFirmwareInfo = FirmwareInfo(
                signature = "Siemens Sirius 32 (EMS3132) KWP2000/DDT",
                productString = "SIRIUS32",
                era = FirmwareEra.LEGACY,
                family = EcuFamily.UNKNOWN,
                capabilities = EcuCapabilities(
                    supportsModernProtocol = false, supportsLegacyProtocol = false,
                    supportsPageRead = false, supportsPageWrite = false, supportsBurn = false,
                    supportsLiveData = true,
                ),
            )
            connected = true
            onConnectionStateChanged(true)
            diagnosticsSink.log("sirius32_ddt", "connect", "21 80 ok")
        } catch (t: Throwable) {
            investigationRecorder?.info("connect_failure", "reason=${t.message ?: t::class.simpleName}")
            connected = false
            cachedFirmwareInfo = null
            runCatching { connection.disconnect() }
            throw t
        }
    }

    private suspend fun initElmLocked(mode: String) {
        val common = listOf(
            "ATWS", "ATE0", "ATL0", "ATH0", "ATS0",
            "ATSH81${ecuAddress}F1", "ATWM81${ecuAddress}F13E", "ATSW96", "ATIB10", "ATAT0", "ATSTFF",
        )
        val proto = if (mode == "slow") listOf("ATSP4", "ATIIA$ecuAddress", "ATSI") else listOf("ATSP5", "ATFI")
        (common + proto + listOf("ATAT1")).forEach { sendLocked(it) }
    }

    /** Tenta as sessões do perfil; aceita a primeira que responder `50 xx`. */
    private suspend fun openSessionLocked() {
        for (s in listOf(Sirius32Actions.Session.STUDIES, Sirius32Actions.Session.AFTER_SALES)) {
            val r = sendLocked("10${hex2(s.code)}")
            if (positive(r, 0x50)) {
                investigationRecorder?.info("session", "sessão ${s.label} (${hex2(s.code)}) aberta")
                return
            }
        }
        investigationRecorder?.info("session", "nenhuma sessão 10 xx aceita; seguindo sem sessão")
    }

    override fun disconnect() {
        stopLiveDataStream()
        connected = false
        cachedFirmwareInfo = null
        runCatching { connection.disconnect() }
        onConnectionStateChanged(false)
        investigationRecorder?.closeSession(summary = mapOf("transport" to "sirius32_ddt"))
    }

    override fun isConnected(): Boolean = connected && connection.isConnected()
    override fun isStreaming(): Boolean = streaming

    // ---- Live data ---------------------------------------------------------------

    override fun startLiveDataStream(intervalMs: Long) {
        if (streaming) return
        streaming = true
        streamJob = scope.launch {
            var failures = 0
            while (isActive && streaming && isConnected()) {
                val ok = runCatching { pollLiveOnce() }.onFailure {
                    Logger.d(TAG, "poll falhou: ${it.message}")
                    if (failures++ % 5 == 0) onError(it.message ?: "Sirius 32: falha ao ler dados ao vivo")
                }.isSuccess
                if (ok) failures = 0
                delay(intervalMs.coerceAtLeast(CYCLE_MIN_MS))
            }
        }
    }

    override fun stopLiveDataStream() {
        streaming = false
        streamJob?.cancel()
        streamJob = null
    }

    override suspend fun pauseLiveDataStream(timeoutMs: Long) {
        stopLiveDataStream()
        ioMutex.withLock { runCatching { connection.clearInputBuffer() } }
    }

    private suspend fun pollLiveOnce() {
        val (a2, a4) = ioMutex.withLock {
            val first = Sirius32Ddt.trameBytes(0xA2, sendLocked("21A2"))
                ?: throw IllegalStateException("Sirius 32: sem resposta 61 A2")
            first to Sirius32Ddt.trameBytes(0xA4, sendLocked("21A4"))
        }
        val f2 = Sirius32Ddt.frames.getValue(0xA2)
        val f4 = Sirius32Ddt.frames.getValue(0xA4)
        fun v(f: DdtFrame, bytes: List<Int>?, name: String): Double? =
            bytes?.let { b -> f.items.firstOrNull { it.name == name }?.value(b) }

        val rpm = v(f2, a2, "Régime moteur copie trame 3")?.roundToInt() ?: 0
        val coolant = v(f2, a2, "Température d eau copie trame 3")?.roundToInt() ?: 0
        val air = v(f2, a2, "Température d air copie trame 3")?.roundToInt() ?: 0
        val mapKpa = (v(f2, a2, "Pression collecteur admission copie trame 3") ?: 1000.0) / 10.0
        val throttleDeg = v(f2, a2, "Position papillon copie trame 3") ?: 0.0
        val battery = v(f2, a2, "Tension batterie copie trame 3") ?: 0.0
        val o2Up = v(f2, a2, "Tension de la sonde à oxygène amont copie trame 3")?.div(1000.0)
        val o2Down = v(f2, a2, "Tension de la sonde à oxygène aval copie trame 3")?.div(1000.0)
        val advance = v(f4, a4, "Avance à l allumage copie trame 5")?.roundToInt() ?: 0
        val injMs = v(f4, a4, "Temps d injection copie trame 5")
        val afr = o2Up?.let { (14.7 + ((it - 0.45) * 6.0)).coerceIn(10.0, 19.9) }

        val sample = EcuLiveData(
            secl = 0,
            rpm = rpm.coerceIn(0, 12000),
            coolantTemp = coolant,
            intakeTemp = air,
            mapPressure = mapKpa.roundToInt().coerceIn(0, 255),
            // Ângulo do potenciômetro (0,4°/bit) -> % assumindo curso útil de ~84°.
            tps = (throttleDeg * 100.0 / 84.0).roundToInt().coerceIn(0, 100),
            batteryVoltage = battery,
            advance = advance,
            o2 = ((afr ?: 0.0) * 10.0).roundToInt(),
            engineStatus = if (rpm > 0) 0x01 else 0x00,
            sparkStatus = if (rpm > 0) 0x01 else 0x00,
            candidateInjectionDurationMs = injMs,
            o2UpstreamVolts = o2Up,
            o2DownstreamVolts = o2Down,
            unavailableChannels = if (afr == null) setOf("o2") else emptySet(),
        )
        lastSample = sample
        onDataReceived(sample)
    }

    // ---- Diagnóstico ----------------------------------------------------------------

    override suspend fun readIdentification(): Map<String, String> = ioMutex.withLock {
        val bytes = Sirius32Ddt.trameBytes(0x80, sendLocked("2180"))
            ?: throw IllegalStateException("Sirius 32: sem resposta à identificação (21 80)")
        Sirius32Ddt.identification(bytes)
    }

    override suspend fun readFaults(): List<Sirius32Fault> = ioMutex.withLock {
        val frames = HashMap<Int, List<Int>>()
        for (id in Sirius32Ddt.FAULT_FRAME_IDS) {
            val r = sendLocked("21${hex2(id)}")
            Sirius32Ddt.trameBytes(id, r)?.let { frames[id] = it }
        }
        if (frames.isEmpty()) {
            throw IllegalStateException("Sirius 32: nenhum trame de falhas respondeu (21 A0/A1/AC/AD/AF)")
        }
        Sirius32Ddt.evaluateFaults(frames)
    }

    override suspend fun readFrame(id: Int): Map<String, String> = ioMutex.withLock {
        val frame = Sirius32Ddt.frames[id] ?: throw IllegalArgumentException("Trame desconhecido: ${hex2(id)}")
        val bytes = Sirius32Ddt.trameBytes(id, sendLocked("21${hex2(id)}"))
            ?: throw IllegalStateException("Sirius 32: sem resposta ao trame 21 ${hex2(id)}")
        Sirius32Ddt.decodeFrame(frame, bytes)
    }

    override suspend fun clearFaults(): Boolean = ioMutex.withLock {
        // EOBD e fora do EOBD são memórias separadas no perfil (tipo 0x00 / 0xFF).
        val eobd = positive(sendLocked("140000"), 0x54)
        val other = positive(sendLocked("14FF00"), 0x54)
        eobd || other
    }

    override suspend fun resetAdaptive(reset: Sirius32Actions.AdaptiveReset): Boolean = ioMutex.withLock {
        positive(sendLocked(Sirius32Actions.adaptiveResetRequest(reset)), 0x51)
    }

    override suspend fun runSpecialFunction(function: Sirius32Actions.SpecialFunction): Boolean = ioMutex.withLock {
        positive(sendLocked(Sirius32Actions.specialFunctionRequest(function)), 0x71)
    }

    override suspend fun stopSpecialFunctions(): Boolean = ioMutex.withLock {
        positive(sendLocked(Sirius32Actions.stopFunctionsRequest()), 0x72)
    }

    override suspend fun startActuator(actuator: Sirius32Actions.Actuator, cycles: Int): Boolean = ioMutex.withLock {
        positive(sendLocked(Sirius32Actions.outputStartRequest(actuator, cycles)), 0x70)
    }

    override suspend fun stopActuator(actuator: Sirius32Actions.Actuator): Boolean = ioMutex.withLock {
        positive(sendLocked(Sirius32Actions.outputStopRequest(actuator)), 0x70)
    }

    // Obd2DiagnosticsCapable (tela de códigos existente)
    override fun isDiagnosticsAvailable(): Boolean = isConnected()
    override suspend fun readDtcCodes(): List<DtcCode> = Sirius32Ddt.faultsToDtcCodes(readFaults(), DtcStatus.ACTIVE)
    override suspend fun readPendingDtcCodes(): List<DtcCode> = Sirius32Ddt.faultsToDtcCodes(readFaults(), DtcStatus.PENDING)
    override suspend fun clearDtcCodes(): Boolean = clearFaults()

    // ---- Teste de compatibilidade (somente leitura) ------------------------------------

    override suspend fun runCompatibilityProbe(): String = ioMutex.withLock {
        val report = StringBuilder()
        fun line(label: String, response: String) {
            report.append(label.padEnd(10)).append(" -> ").append(response.replace('\r', ' ').replace('\n', ' ').trim()).append('\n')
        }
        report.append("=== Teste de compatibilidade Sirius 32 (somente leitura) ===\n")
        for (cmd in listOf("ATI", "ATDP", "ATDPN", "ATRV", "10A0", "2180", "17FF00")) line(cmd, sendLocked(cmd))
        val ids = Sirius32Ddt.frames.keys.filter { it != 0x80 }.sorted()
        for (id in ids) {
            val cmd = "21${hex2(id)}"
            val r = sendLocked(cmd)
            line(cmd, r)
        }
        for (cmd in listOf("0100", "0105", "010C", "0111")) line(cmd, sendLocked(cmd))
        report.append("=== fim ===\n")
        val text = report.toString()
        investigationRecorder?.info("compat_probe", text.take(4000))
        text
    }

    override suspend fun getFirmwareInfo(): String = cachedFirmwareInfo?.signature ?: "Siemens Sirius 32 (EMS3132) KWP2000/DDT"
    override fun getFirmwareInfoCached(): FirmwareInfo? = cachedFirmwareInfo
    override fun getEcuFamily(): EcuFamily = cachedFirmwareInfo?.family ?: EcuFamily.UNKNOWN
    override fun getEcuCapabilities(): EcuCapabilities? = cachedFirmwareInfo?.capabilities
    override fun getTableDefinitions(): TableDefinitions? = null

    // ---- E/S -----------------------------------------------------------------------------

    /** Resposta positiva = contém `<sid> ` (ex.: 0x54). Respostas `7F xx yy` nunca contam. */
    private fun positive(response: String, sid: Int): Boolean {
        val bytes = Sirius32Ddt.parseBytes(response)
        return bytes.firstOrNull() == sid
    }

    private suspend fun sendLocked(command: String, timeoutMs: Long = COMMAND_TIMEOUT_MS): String {
        val startedAt = MonotonicClock.nowMillis()
        return runCatching {
            connection.clearInputBuffer()
            connection.send("$command\r".encodeToByteArray())
            val deadline = startedAt + timeoutMs
            val builder = StringBuilder()
            while (MonotonicClock.nowMillis() < deadline) {
                val remaining = (deadline - MonotonicClock.nowMillis()).coerceAtLeast(1L)
                val chunk = runCatching { connection.readAvailable(maxBytes = 0, timeoutMs = remaining.toInt()) }.getOrNull()
                if (chunk == null || chunk.isEmpty()) break
                builder.append(chunk.decodeToString())
                if (builder.contains(">")) break
            }
            builder.toString().replace('\u0000', ' ').trim()
        }.onSuccess { response ->
            investigationRecorder?.recordCommand(
                transport = "sirius32_ddt", command = command, response = response,
                timeoutMs = timeoutMs, elapsedMs = MonotonicClock.nowMillis() - startedAt,
            )
        }.onFailure { e ->
            Logger.d(TAG, "comando falhou cmd=$command err=${e.message}")
            investigationRecorder?.info("command_failure", "cmd=$command reason=${e.message ?: e::class.simpleName}")
        }.getOrThrow()
    }
}
