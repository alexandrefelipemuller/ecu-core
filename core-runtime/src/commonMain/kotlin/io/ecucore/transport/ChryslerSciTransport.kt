package io.ecucore.transport

import io.ecucore.EcuLiveData
import io.ecucore.connection.IEcuConnection
import io.ecucore.ecu.FirmwareInfo
import io.ecucore.model.EcuCapabilities
import io.ecucore.model.EcuFamily
import io.ecucore.model.FirmwareEra
import io.ecucore.model.TableDefinitions
import io.ecucore.shared.Logger
import io.ecucore.shared.MonotonicClock
import io.ecucore.transport.chrysler.ChryslerSciDtcTable
import io.ecucore.transport.chrysler.ChryslerSciProtocol as P
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

/**
 * Transporte para PCMs Chrysler/Jeep OBD1 (SBEC 1991-95) via barramento SCI 7812,5 baud.
 *
 * NÃO usa ELM327: precisa de uma ponte que repasse bytes crus (ver `xj_bridge.ino` do
 * xj-scanner): ao abrir, a ponte imprime `XJBRIDGE x.y`; mandamos `PROBE\n` (nível de
 * repouso da linha) e `GO I\n` (modo transparente, lógica invertida). Depois disso cada
 * byte enviado é ecoado pela PCM, então a conversa é sempre byte a byte com espera de eco.
 *
 * Validado só em um Jeep Cherokee XJ 1994 4.0L (PCM 56030117), ver [ChryslerSciProtocol].
 *
 * Somente leitura por padrão: atuadores ([startActuatorTest]) e reset de trim
 * ([resetLearnedFuelTrim]) são chamadas explícitas, nunca feitas pelo stream.
 *
 * @param vehicleSpeedFactor multiplica o PID 0x41 (mph). Na PCM de teste ele lê o DOBRO,
 *   então use 0.5 após conferir com o velocímetro; padrão 1.0 (valor cru).
 */
class ChryslerSciTransport(
    private val connection: IEcuConnection,
    private val onDataReceived: (EcuLiveData) -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val vehicleSpeedFactor: Double = 1.0,
    private val diagnosticsSink: ConnectionDiagnosticsSink = NoopConnectionDiagnosticsSink,
) : EcuTransport, Obd2DiagnosticsCapable {
    companion object {
        private const val TAG = "ChryslerSciTransport"
        private const val BANNER = "XJBRIDGE"
        private const val BANNER_TIMEOUT_MS = 4000L
        private const val LINE_TIMEOUT_MS = 1500L
        private const val BYTE_TIMEOUT_MS = 250L
        private const val QUIET_MS = 80L
        private const val MAX_COLLECT_MS = 1000L
        private const val MPH_TO_KPH = 1.609344
        private const val PSI_TO_KPA = 6.894757
        private const val TPS_CLOSED_V = 0.5
        private const val TPS_WOT_V = 4.5
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var streamJob: Job? = null
    @Volatile private var streaming = false
    @Volatile private var connected = false
    @Volatile private var cachedFirmwareInfo: FirmwareInfo? = null
    @Volatile private var nibbleSwap = false
    private val ioMutex = Mutex()
    private val rx = ArrayDeque<Int>()
    private var startedAtMs = 0L

    /** Linha de repouso da PCM (% alto em 200 ms) reportada pela ponte no connect. */
    @Volatile var lastProbeHighPercent: Int? = null
        private set

    // ---- ciclo de vida -----------------------------------------------------------------

    override suspend fun connect() {
        try {
            if (!connection.isConnected()) connection.connect()
            ioMutex.withLock {
                // Não limpa o buffer da conexão: o banner da ponte pode já ter chegado.
                rx.clear()
                openBridgeLocked()
                detectByteOrderLocked()
            }
            val part = ioMutex.withLock { runCatching { readPartNumberLocked() }.getOrNull() }
            cachedFirmwareInfo = FirmwareInfo(
                signature = "Chrysler SBEC OBD1 SCI" + (part?.let { " PCM $it" } ?: ""),
                productString = part ?: "CHRYSLER-SCI",
                era = FirmwareEra.LEGACY,
                family = EcuFamily.UNKNOWN,
                capabilities = EcuCapabilities(
                    supportsModernProtocol = false,
                    supportsLegacyProtocol = false,
                    supportsPageRead = false,
                    supportsPageWrite = false,
                    supportsBurn = false,
                    supportsLiveData = true,
                ),
            )
            startedAtMs = MonotonicClock.nowMillis()
            connected = true
            onConnectionStateChanged(true)
            diagnosticsSink.log("chrysler_sci", "connect", "bridge up, swap=$nibbleSwap part=$part")
        } catch (t: Throwable) {
            connected = false
            cachedFirmwareInfo = null
            runCatching { connection.disconnect() }
            throw t
        }
    }

    override fun disconnect() {
        stopLiveDataStream()
        connected = false
        cachedFirmwareInfo = null
        runCatching { connection.disconnect() }
        onConnectionStateChanged(false)
    }

    override fun isConnected(): Boolean = connected && connection.isConnected()
    override fun isStreaming(): Boolean = streaming

    override fun startLiveDataStream(intervalMs: Long) {
        if (streaming) return
        streaming = true
        streamJob = scope.launch {
            while (isActive && streaming && isConnected()) {
                runCatching { pollLiveDataOnce() }.onFailure { err ->
                    Logger.d(TAG, "poll failed: ${err.message}")
                    onError(err.message ?: "Chrysler SCI: live data read failed")
                }
                delay(intervalMs.coerceAtLeast(50L))
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
        ioMutex.withLock { rx.clear(); runCatching { connection.clearInputBuffer() } }
    }

    override suspend fun getFirmwareInfo(): String = cachedFirmwareInfo?.signature ?: "Chrysler SBEC OBD1 SCI"
    override fun getFirmwareInfoCached(): FirmwareInfo? = cachedFirmwareInfo
    override fun getEcuFamily(): EcuFamily = EcuFamily.UNKNOWN
    override fun getEcuCapabilities(): EcuCapabilities? = cachedFirmwareInfo?.capabilities
    override fun getTableDefinitions(): TableDefinitions? = null
    override fun isReadOnlySafeMode(): Boolean = true
    override fun getConnectionInfo(): String = connection.getConnectionInfo()

    // ---- diagnósticos (DTC) --------------------------------------------------------------

    override suspend fun readDtcCodes(): List<DtcCode> = ioMutex.withLock {
        readCodesLocked().map {
            DtcCode(code = "SCI-" + it.toString(16).uppercase().padStart(2, '0'), description = ChryslerSciDtcTable.describe(it), status = DtcStatus.ACTIVE)
        }
    }

    /** A PCM OBD1 não separa códigos pendentes. */
    override suspend fun readPendingDtcCodes(): List<DtcCode> = emptyList()

    /** `17` apaga os códigos E o trim aprendido (volta a 128): o motor reaprende na estrada. */
    override suspend fun clearDtcCodes(): Boolean = ioMutex.withLock {
        sendCommandLocked(byteArrayOf(P.CMD_ERASE_CODES.toByte()))
        readCodesLocked().isEmpty()
    }

    // ---- extras (chamadas explícitas) ---------------------------------------------------

    /** Liga um atuador da PCM (`13 id`). Só com o carro parado; pare com [stopActuatorTest]. */
    suspend fun startActuatorTest(testId: Int): List<Int> = ioMutex.withLock {
        require(testId in P.ACTUATOR_TESTS) { "Unknown actuator test ${testId.toString(16)}" }
        sendCommandLocked(byteArrayOf(P.CMD_ACTUATOR.toByte(), testId.toByte()))
    }

    suspend fun stopActuatorTest(): List<Int> = ioMutex.withLock {
        sendCommandLocked(byteArrayOf(P.CMD_ACTUATOR.toByte(), P.STOP_BYTE.toByte()))
    }

    /** Zera o trim de longo prazo aprendido (`23 02`, resposta esperada F0). */
    suspend fun resetLearnedFuelTrim(): Boolean = ioMutex.withLock {
        sendCommandLocked(byteArrayOf(P.CMD_RESET_FUEL_TRIM.toByte(), 0x02)).contains(0xF0)
    }

    /** Lê um byte da memória da PCM (`15 hi lo`), somente leitura. */
    suspend fun readMemoryByte(address: Int): Int = ioMutex.withLock { readMemoryByteLocked(address) }

    /** Lê o valor cru de um parâmetro (`14 id`). */
    suspend fun readRawParam(pid: Int): Int = ioMutex.withLock { readRawLocked(pid) }

    // ---- live data ---------------------------------------------------------------------

    private suspend fun pollLiveDataOnce() {
        data class Raw(val rpm: Int, val coolant: Int, val iat: Int, val tps: Int, val map: Int, val o2: Int,
                       val batt: Int, val adv: Int, val speed: Int, val ltft: Int, val sync: Int)
        val r = ioMutex.withLock {
            Raw(
                rpm = readRawLocked(P.PID_RPM),
                coolant = readRawLocked(P.PID_COOLANT_F),
                iat = readRawLocked(P.PID_IAT_F),
                tps = readRawLocked(P.PID_TPS_V),
                map = readRawLocked(P.PID_MAP_PSI),
                o2 = readRawLocked(P.PID_O2_UPSTREAM_V),
                batt = readRawLocked(P.PID_BATTERY_V),
                adv = readRawLocked(P.PID_SPARK_ADVANCE),
                speed = readRawLocked(P.PID_VEHICLE_SPEED),
                ltft = readRawLocked(P.PID_LTFT),
                sync = readRawLocked(P.PID_SYNC),
            )
        }
        val rpm = P.rpm(r.rpm)
        val tpsPct = ((P.volts(r.tps) - TPS_CLOSED_V) / (TPS_WOT_V - TPS_CLOSED_V) * 100.0).coerceIn(0.0, 100.0)
        val running = rpm > 0
        onDataReceived(
            EcuLiveData(
                secl = ((MonotonicClock.nowMillis() - startedAtMs) / 1000L).toInt(),
                rpm = rpm,
                coolantTemp = P.tempC(r.coolant).toInt(),
                intakeTemp = P.tempC(r.iat).toInt(),
                mapPressure = (P.psi(r.map) * PSI_TO_KPA).toInt(),
                tps = tpsPct.toInt(),
                batteryVoltage = P.batteryVolts(r.batt),
                advance = P.sparkAdvanceDeg(r.adv).toInt(),
                o2 = 0,
                engineStatus = if (running) 0x01 else 0x00,
                sparkStatus = if (P.inSync(r.sync)) 0x01 else 0x00,
                candidateSpeedKph = (r.speed * vehicleSpeedFactor * MPH_TO_KPH).toInt(),
                longFuelTrimPct = P.fuelTrimPercent(r.ltft),
                o2UpstreamVolts = P.volts(r.o2),
                // TPS em % é aproximação (faixa 0,5-4,5 V assumida); O2 é sonda narrowband em volts.
                unavailableChannels = setOf("o2"),
            ),
        )
    }

    // ---- ponte e eco (chamar com ioMutex preso) -----------------------------------------

    private suspend fun openBridgeLocked() {
        if (waitForLine(BANNER_TIMEOUT_MS) { BANNER in it } == null) {
            // Abrir a porta costuma resetar o Arduino; se não resetou, pede de novo o prompt.
            connection.send("\n".encodeToByteArray())
            waitForLine(BANNER_TIMEOUT_MS) { BANNER in it }
                ?: throw IllegalStateException("Chrysler SCI: bridge did not answer (is xj_bridge uploaded?)")
        }
        connection.send("PROBE\n".encodeToByteArray())
        val probe = waitForLine(LINE_TIMEOUT_MS) { "IDLE" in it }
        lastProbeHighPercent = probe?.trim()?.substringAfterLast(' ')?.toIntOrNull()
        if ((lastProbeHighPercent ?: 0) > 90) {
            throw IllegalStateException(
                "Chrysler SCI: line idles HIGH ($lastProbeHighPercent%): key off, SCI wires swapped or not connected",
            )
        }
        connection.send("GO I\n".encodeToByteArray())
        waitForLine(LINE_TIMEOUT_MS) { it.startsWith("OK") }
            ?: throw IllegalStateException("Chrysler SCI: bridge did not enter transparent mode")
        rx.clear()
        connection.clearInputBuffer()
    }

    /** Algumas PCMs mandam os nibbles trocados; confere pela tensão da bateria (9-16,5 V). */
    private suspend fun detectByteOrderLocked() {
        for (swap in listOf(false, true)) {
            nibbleSwap = swap
            val v = runCatching { P.batteryVolts(readRawLocked(P.PID_BATTERY_V)) }.getOrNull() ?: continue
            if (v in 9.0..16.5) return
        }
        nibbleSwap = false
        throw IllegalStateException("Chrysler SCI: PCM did not answer (no echo / implausible battery voltage)")
    }

    private suspend fun readPartNumberLocked(): String =
        P.partNumber((0 until P.PART_NUMBER_LENGTH).map { readMemoryByteLocked(P.PART_NUMBER_ADDRESS + it) })

    private suspend fun readRawLocked(pid: Int): Int {
        rx.clear(); connection.clearInputBuffer()
        sendWithEchoLocked(P.CMD_READ_PARAM)
        sendWithEchoLocked(pid)
        val value = getByteLocked(BYTE_TIMEOUT_MS) ?: throw IllegalStateException("Chrysler SCI: no value for PID ${pid.toString(16)}")
        putByteLocked(P.STOP_BYTE) // a PCM repete o valor até receber 00
        collectLocked(30L, 30L, 300L)
        return value
    }

    private suspend fun readMemoryByteLocked(address: Int): Int {
        rx.clear(); connection.clearInputBuffer()
        sendWithEchoLocked(P.CMD_READ_MEMORY)
        sendWithEchoLocked((address shr 8) and 0xFF)
        sendWithEchoLocked(address and 0xFF)
        return getByteLocked(BYTE_TIMEOUT_MS) ?: throw IllegalStateException("Chrysler SCI: no data at ${address.toString(16)}")
    }

    /** `10` -> eco `10`, um byte por código, marcadores FE/FD e checksum (soma dos anteriores). */
    private suspend fun readCodesLocked(): List<Int> {
        rx.clear(); connection.clearInputBuffer()
        putByteLocked(P.CMD_READ_CODES)
        val data = collectLocked(BYTE_TIMEOUT_MS, QUIET_MS, MAX_COLLECT_MS)
        if (data.isEmpty() || data[0] != P.CMD_READ_CODES) {
            throw IllegalStateException("Chrysler SCI: unexpected fault code response: ${data.joinToString(" ") { it.toString(16) }.ifEmpty { "nothing" }}")
        }
        if (data.size < 3) throw IllegalStateException("Chrysler SCI: truncated fault code response")
        val checksumOk = (data.dropLast(1).sum() and 0xFF) == data.last()
        if (!checksumOk) throw IllegalStateException("Chrysler SCI: fault code checksum mismatch")
        return data.subList(1, data.size - 1).filter { it != P.CODES_END_NONE && it != P.CODES_END_MARK }
    }

    /** Envia bytes um a um (cada um espera o eco) e devolve tudo o que a PCM respondeu depois. */
    private suspend fun sendCommandLocked(bytes: ByteArray): List<Int> {
        rx.clear(); connection.clearInputBuffer()
        val received = mutableListOf<Int>()
        for (b in bytes) {
            putByteLocked(b.toInt() and 0xFF)
            received += collectLocked(BYTE_TIMEOUT_MS, 30L, 300L)
        }
        return received
    }

    private suspend fun sendWithEchoLocked(b: Int) {
        putByteLocked(b)
        val deadline = MonotonicClock.nowMillis() + BYTE_TIMEOUT_MS
        val other = mutableListOf<Int>()
        while (true) {
            val got = getByteLocked((deadline - MonotonicClock.nowMillis()).coerceAtLeast(0L))
            if (got == b) return
            if (got == null) {
                throw IllegalStateException(
                    if (other.isEmpty()) "Chrysler SCI: no echo for byte ${b.toString(16)}"
                    else "Chrysler SCI: echo mismatch, sent ${b.toString(16)} got ${other.joinToString(" ") { it.toString(16) }}",
                )
            }
            other += got
        }
    }

    private fun putByteLocked(b: Int) {
        val raw = if (nibbleSwap) P.swapNibbles(b) else b
        connection.send(byteArrayOf(raw.toByte()))
    }

    private suspend fun getByteLocked(timeoutMs: Long): Int? {
        rx.removeFirstOrNull()?.let { return decode(it) }
        val deadline = MonotonicClock.nowMillis() + timeoutMs
        do {
            val remaining = (deadline - MonotonicClock.nowMillis()).coerceAtLeast(1L).toInt()
            val chunk = runCatching { connection.readAvailable(maxBytes = 64, timeoutMs = remaining) }.getOrNull()
            if (chunk != null && chunk.isNotEmpty()) {
                chunk.forEach { rx.addLast(it.toInt() and 0xFF) }
                return decode(rx.removeFirst())
            }
        } while (MonotonicClock.nowMillis() < deadline)
        return null
    }

    private fun decode(raw: Int): Int = if (nibbleSwap) P.swapNibbles(raw) else raw

    /** Bytes até a linha ficar [quietMs] em silêncio (ou [maxMs]: PCM repetindo nunca silencia). */
    private suspend fun collectLocked(firstTimeoutMs: Long, quietMs: Long, maxMs: Long): List<Int> {
        val out = mutableListOf<Int>()
        val stopAt = MonotonicClock.nowMillis() + maxMs
        var b = getByteLocked(firstTimeoutMs)
        while (b != null) {
            out += b
            if (MonotonicClock.nowMillis() >= stopAt) break
            b = getByteLocked(quietMs)
        }
        return out
    }

    /** Lê texto da ponte (modo comando, sem inversão de nibble) até uma linha aceita por [match]. */
    private suspend fun waitForLine(timeoutMs: Long, match: (String) -> Boolean): String? {
        val deadline = MonotonicClock.nowMillis() + timeoutMs
        val sb = StringBuilder()
        val saved = nibbleSwap
        nibbleSwap = false
        try {
            while (MonotonicClock.nowMillis() < deadline) {
                val b = getByteLocked((deadline - MonotonicClock.nowMillis()).coerceAtLeast(1L)) ?: break
                if (b == '\n'.code) {
                    val line = sb.toString().trim(); sb.clear()
                    if (line.isNotEmpty() && match(line)) return line
                } else sb.append(b.toChar())
            }
        } finally { nibbleSwap = saved }
        return null
    }
}
