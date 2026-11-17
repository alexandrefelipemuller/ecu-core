package io.ecucore.transport

import io.ecucore.shared.Logger
import kotlin.concurrent.Volatile
import io.ecucore.shared.MonotonicClock
import io.ecucore.EcuLiveData
import io.ecucore.connection.IEcuConnection
import io.ecucore.ecu.FirmwareInfo
import io.ecucore.model.EcuCapabilities
import io.ecucore.model.EcuFamily
import io.ecucore.model.FirmwareEra
import io.ecucore.model.TableDefinitions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.ecucore.transport.EcuTransport

/**
 * Transporte para a Siemens Sirius 32 (Renault Clio K4M).
 *
 * Fala o dialeto KWP2000 "cru" reconstruído por engenharia reversa do firmware
 * (ver `~/Projects/centrais_oem/Sirius32/core/kline/README.md`): serviços proprietários
 *
 *   10 29        StartDiagnosticSession (única sessão aceita)
 *   23 6B        entra no modo estendido
 *   2A <id 16b>  lê um dos grupos do diretório mestre de diagnóstico
 *   14 20 / 14 6F  limpa DTC (handshake de 2 passos)
 *
 * ATENÇÃO - evidências conflitantes: a captura de UM Clio K4M
 * (`EcuManager/logs/device_obd2_investigation/obd2_investigation_2026-04-07_07-22-47_clio_k4m`)
 * mostra a central respondendo SAE J1979 modo 01 e o ELM327 detectando
 * "ISO 14230-4 (KWP 5BAUD)" (ATDPN = A4); mas relatos de usuários dizem que o OBD2
 * genérico só mostra a tensão da bateria (que vem do próprio ELM, não da ECU). Por isso
 * este transporte continua ANTES do OBD2 genérico na cadeia. Os serviços proprietários
 * ainda NÃO foram validados em carro (a captura não tem nenhum SID 0x29/0x14/0x2A).
 *
 * Só o grupo `0x0FAA` (RPM/carga/temperaturas) tem mapeamento de campo confirmado com
 * "alta confiança" na engenharia reversa.
 *
 * Concorrência: todo acesso ao adaptador passa por [ioMutex]; sequências de vários
 * comandos (abrir sessão, limpar DTC) seguram o lock do começo ao fim.
 */
class Sirius32Transport(
    private val connection: IEcuConnection,
    private val onDataReceived: (EcuLiveData) -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val investigationRecorder: Obd2InvestigationSink? = null,
    private val diagnosticsSink: ConnectionDiagnosticsSink = NoopConnectionDiagnosticsSink,
) : EcuTransport, Obd2DiagnosticsCapable {
    companion object {
        private const val TAG = "Sirius32Transport"
        private const val COMMAND_TIMEOUT_MS = 1200L

        /** O primeiro comando após ATSP4 dispara o init 5-baud (~4,3 s na captura real). */
        private const val BUS_INIT_TIMEOUT_MS = 6000L
        private const val ATZ_TIMEOUT_MS = 1800L

        /** Falhas seguidas de leitura antes de considerar a sessão perdida. */
        private const val SESSION_LOSS_THRESHOLD = 3
        private const val DEFAULT_BATTERY_VOLTAGE = 12.0
        private const val LIVE_DATA_TABLE_ID = "0FAA"
        private const val DTC_STATUS_TABLE_ID = "0893"

        /**
         * ATSP4 = ISO 14230-4 (KWP, init 5-baud): é o protocolo que o ELM327 detectou
         * (ATDPN = A4) na captura real do K4M. Antes era ATSP5 (fast init), sem evidência.
         * ATAT1/ATST20 espelham a configuração que funcionou na captura.
         */
        private val INIT_SEQUENCE = listOf(
            "ATE0", "ATL0", "ATH0", "ATS0", "ATSP4", "ATAT1", "ATST20",
        )
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var streamJob: Job? = null
    @Volatile
    private var streaming = false
    @Volatile
    private var connected = false
    @Volatile
    private var cachedFirmwareInfo: FirmwareInfo? = null
    private val ioMutex = Mutex()
    @Volatile
    private var sessionOpen = false
    private var consecutiveReadFailures = 0

    override suspend fun connect() {
        try {
            if (!connection.isConnected()) {
                connection.connect()
            }
            investigationRecorder?.startSession(
                transport = "sirius32",
                metadataExtras = mapOf(
                    "connection" to connection.getConnectionInfo().trim().ifBlank { "unknown" },
                ),
            )

            val atzResponse = ioMutex.withLock {
                val atz = sendRawLocked("ATZ", ATZ_TIMEOUT_MS)
                INIT_SEQUENCE.forEach { sendRawLocked(it) }
                atz
            }
            // ATZ já devolve a versão do ELM ("ELM327 v1.5"/"v2.1"); só registramos.
            investigationRecorder?.info("adapter", "atz=${atzResponse.replace('\r', ' ').trim()}")

            val (sessionResponse, extendedResponse) = ioMutex.withLock { openSessionLocked() }

            cachedFirmwareInfo = FirmwareInfo(
                signature = "Siemens Sirius 32 (Renault Clio K4M) KWP2000",
                productString = "SIRIUS32",
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
            connected = true
            consecutiveReadFailures = 0
            onConnectionStateChanged(true)
            diagnosticsSink.log("sirius32", "connect", "session and extended mode confirmed")
            investigationRecorder?.info("connect", "sirius32 session=$sessionResponse extended=$extendedResponse")
        } catch (t: Throwable) {
            investigationRecorder?.info("connect_failure", "reason=${t.message ?: t::class.simpleName}")
            connected = false
            cachedFirmwareInfo = null
            runCatching { connection.disconnect() }
            throw t
        }
    }

    override fun disconnect() {
        stopLiveDataStream()
        connected = false
        sessionOpen = false
        cachedFirmwareInfo = null
        runCatching { connection.disconnect() }
        onConnectionStateChanged(false)
        investigationRecorder?.closeSession(summary = mapOf("transport" to "sirius32"))
    }

    override fun isConnected(): Boolean = connected && connection.isConnected()

    override fun isStreaming(): Boolean = streaming

    override fun startLiveDataStream(intervalMs: Long) {
        if (streaming) return
        streaming = true
        streamJob = scope.launch {
            while (isActive && streaming && isConnected()) {
                runCatching { pollLiveDataOnce() }
                    .onFailure { err ->
                        Logger.d(TAG, "poll cycle failed: ${err.message}")
                        onError(err.message ?: "Sirius 32: live data read failed")
                    }
                delay(intervalMs.coerceAtLeast(200L))
            }
        }
    }

    override fun stopLiveDataStream() {
        streaming = false
        streamJob?.cancel()
        streamJob = null
    }

    override suspend fun pauseLiveDataStream(timeoutMs: Long) {
        // Não reabre sessão aqui: um 10 29 repetido com a sessão já aberta é penalizado
        // pelo firmware (soma no contador de erros 0xF739). A sessão só é reaberta quando
        // [SESSION_LOSS_THRESHOLD] leituras seguidas falham.
        stopLiveDataStream()
        ioMutex.withLock { runCatching { connection.clearInputBuffer() } }
    }

    override suspend fun getFirmwareInfo(): String =
        cachedFirmwareInfo?.signature ?: "Siemens Sirius 32 (Renault Clio K4M) KWP2000"

    override fun getFirmwareInfoCached(): FirmwareInfo? = cachedFirmwareInfo

    override fun getEcuFamily(): EcuFamily = cachedFirmwareInfo?.family ?: EcuFamily.UNKNOWN

    override fun getEcuCapabilities(): EcuCapabilities? = cachedFirmwareInfo?.capabilities

    override fun getTableDefinitions(): TableDefinitions? = null

    override fun isDiagnosticsAvailable(): Boolean = isConnected()

    override suspend fun readDtcCodes(): List<DtcCode> = readDtcCodesForStatus(DtcStatus.ACTIVE)

    override suspend fun readPendingDtcCodes(): List<DtcCode> = readDtcCodesForStatus(DtcStatus.PENDING)

    /**
     * SID 0x14: ClearDiagnosticInformation, handshake de 2 passos - 0x20 "arma" a
     * limpeza, só depois 0x6F executa (proteção contra apagar DTC por ruído na linha).
     * Sucesso confirma com "54" (0x6F) / "54 20" (arme); "7F 14 xx" é resposta negativa.
     * Os dois passos seguram o lock juntos: um poll intercalado entre eles desarmaria.
     */
    override suspend fun clearDtcCodes(): Boolean = ioMutex.withLock {
        ensureSessionLocked()
        val armed = sendRawLocked("1420").uppercase().replace(" ", "")
        if (!armed.contains("5420")) {
            if (armed.contains("7F1422")) sessionOpen = false
            return@withLock false
        }
        val executed = sendRawLocked("146F").uppercase().replace(" ", "")
        executed.contains("54") && !executed.contains("7F14")
    }

    private suspend fun readDtcCodesForStatus(status: DtcStatus): List<DtcCode> {
        val words = readDtcStatusWords()
        val word = if (status == DtcStatus.ACTIVE) words.first else words.second
        return Sirius32Dtc.decodeWord0(word, status)
    }

    /**
     * Lê os registradores de status de DTC. Falha de leitura LANÇA exceção em vez de
     * devolver lista vazia: "sem resposta" não pode ser exibido como "sem falhas".
     * NRC 0x22 no 2A significa sessão fechada (firmware: sem 10 29 os SIDs respondem 22),
     * então a sessão é marcada como perdida e reaberta na próxima operação.
     */
    private suspend fun readDtcStatusWords(): Pair<Int, Int> = ioMutex.withLock {
        ensureSessionLocked()
        val response = sendRawLocked("2A$DTC_STATUS_TABLE_ID")
        val compact = response.uppercase().replace(" ", "")
        val payload = parseGroupResponse(DTC_STATUS_TABLE_ID, response)
        if (payload == null || payload.size < 4) {
            if (compact.contains("7F2A22")) sessionOpen = false
            val reason = when {
                compact.contains("7F2A") -> "resposta negativa (${response.trim()})"
                compact.isBlank() -> "sem resposta"
                else -> "resposta inesperada (${response.trim()})"
            }
            throw IllegalStateException("Sirius 32: DTC read (2A $DTC_STATUS_TABLE_ID) failed: $reason")
        }
        ((payload[0] shl 8) or payload[1]) to ((payload[2] shl 8) or payload[3])
    }

    private suspend fun pollLiveDataOnce() {
        val bytes = ioMutex.withLock {
            ensureSessionLocked()
            val response = sendRawLocked("2A$LIVE_DATA_TABLE_ID")
            val parsed = parseGroupResponse(LIVE_DATA_TABLE_ID, response)
            if (parsed == null || parsed.size < 6) {
                registerReadFailureLocked()
                null
            } else {
                consecutiveReadFailures = 0
                parsed
            }
        } ?: return

        val rpm = ((bytes[0] shl 8) or bytes[1]).coerceIn(0, 9000)
        val loadPct = bytes[2].coerceIn(0, 255) * 100 / 255
        val coolantC = bytes[4] - 40
        val intakeC = bytes[5] - 40

        val sample = EcuLiveData(
            secl = 0,
            rpm = rpm,
            coolantTemp = coolantC,
            intakeTemp = intakeC,
            mapPressure = 100,
            tps = loadPct,
            batteryVoltage = DEFAULT_BATTERY_VOLTAGE,
            advance = 0,
            o2 = 0,
            engineStatus = if (rpm > 0) 0x01 else 0x00,
            sparkStatus = if (rpm > 0) 0x01 else 0x00,
        )
        onDataReceived(sample)
    }

    /** Abre sessão (10 29) e modo estendido (23 6B). Chamar com [ioMutex] preso. */
    private suspend fun openSessionLocked(): Pair<String, String> {
        val sessionResponse = sendRawLocked("1029", BUS_INIT_TIMEOUT_MS).uppercase()
        if (!sessionResponse.contains("5029") && !sessionResponse.contains("50 29")) {
            throw IllegalStateException("Sirius 32: KWP2000 session did not open (10 29 -> $sessionResponse)")
        }
        val extendedResponse = sendRawLocked("236B").uppercase()
        if (!extendedResponse.contains("636B") && !extendedResponse.contains("63 6B")) {
            throw IllegalStateException("Sirius 32: extended mode did not open (23 6B -> $extendedResponse)")
        }
        sessionOpen = true
        consecutiveReadFailures = 0
        return sessionResponse to extendedResponse
    }

    /** Reabre a sessão só se ela foi dada como perdida. Chamar com [ioMutex] preso. */
    private suspend fun ensureSessionLocked() {
        if (!sessionOpen) {
            investigationRecorder?.info("session", "reabrindo sessão Sirius 32")
            openSessionLocked()
        }
    }

    private fun registerReadFailureLocked() {
        consecutiveReadFailures++
        if (consecutiveReadFailures >= SESSION_LOSS_THRESHOLD && sessionOpen) {
            sessionOpen = false
            investigationRecorder?.info("session", "sessão considerada perdida após $consecutiveReadFailures falhas")
        }
    }

    /**
     * Parseia a resposta positiva do serviço 0x2A: `6A <idHi> <idLo> <dados...>`.
     */
    private fun parseGroupResponse(tableId: String, response: String): List<Int>? {
        val clean = response.uppercase()
        val bytes = Regex("[0-9A-F]{2}")
            .findAll(clean)
            .map { it.value.toInt(16) }
            .toList()
        if (bytes.size < 3) return null
        val idHi = tableId.substring(0, 2).toInt(16)
        val idLo = tableId.substring(2, 4).toInt(16)
        val idx = bytes.windowed(3, 1).indexOfFirst { it[0] == 0x6A && it[1] == idHi && it[2] == idLo }
        if (idx < 0) return null
        return bytes.drop(idx + 3)
    }

    /**
     * Envia um comando ao ELM327 e espera o prompt `>`. Chamar com [ioMutex] preso.
     * Cada leitura respeita o que resta do deadline do comando (receive(0) bloquearia
     * até o timeout global da conexão, congelando o polling).
     */
    private suspend fun sendRawLocked(command: String, timeoutMs: Long = COMMAND_TIMEOUT_MS): String {
        val startedAt = MonotonicClock.nowMillis()
        return runCatching {
            connection.clearInputBuffer()
            connection.send("$command\r".encodeToByteArray())
            val deadline = startedAt + timeoutMs
            val builder = StringBuilder()
            while (MonotonicClock.nowMillis() < deadline) {
                val remainingMs = (deadline - MonotonicClock.nowMillis()).coerceAtLeast(1L)
                val chunk = runCatching {
                    connection.readAvailable(maxBytes = 0, timeoutMs = remainingMs.toInt())
                }.getOrNull()
                if (chunk == null || chunk.isEmpty()) break
                builder.append(chunk.decodeToString())
                if (builder.contains(">")) break
            }
            builder.toString().replace('\u0000', ' ').trim()
        }.onSuccess { response ->
            investigationRecorder?.recordCommand(
                transport = "sirius32",
                command = command,
                response = response,
                timeoutMs = timeoutMs,
                elapsedMs = MonotonicClock.nowMillis() - startedAt,
            )
        }.onFailure { error ->
            Logger.d(TAG, "raw command failed cmd=$command err=${error.message}")
            investigationRecorder?.info("command_failure", "cmd=$command reason=${error.message ?: error::class.simpleName}")
        }.getOrThrow()
    }
}
