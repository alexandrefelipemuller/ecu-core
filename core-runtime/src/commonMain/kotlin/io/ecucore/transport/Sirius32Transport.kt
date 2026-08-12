package io.ecucore.transport

import io.ecucore.shared.Logger
import kotlin.concurrent.Volatile
import io.ecucore.shared.MonotonicClock
import io.ecucore.SpeeduinoLiveData
import io.ecucore.connection.ISpeeduinoConnection
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
import io.ecucore.transport.EcuTransport

/**
 * Transporte para a Siemens Sirius 32 (Renault Clio K4M).
 *
 * Fala o dialeto KWP2000 "cru" reconstruído por engenharia reversa do firmware
 * (ver `~/Downloads/siemens_sir32/diagnostico_obd.md` seção 6h): a central NÃO
 * implementa SAE J1979 (modo 01 é sempre rejeitado com `7F 01 12`), então este
 * transporte não pode delegar para [Obd2Transport] — ele fala diretamente os
 * serviços proprietários confirmados:
 *
 *   10 29        StartDiagnosticSession (única sessão aceita)
 *   23 6B        entra no modo estendido (leitura de memória/grupos)
 *   2A <id 16b>  lê um dos grupos do diretório mestre de diagnóstico
 *
 * Só o grupo `0x0FAA` (RPM/carga/temperaturas) tem mapeamento de campo
 * confirmado com "alta confiança" na engenharia reversa; os demais 27 grupos
 * existem no binário mas o parâmetro exato do serviço 0x2A para acessá-los
 * ainda está em aberto.
 */
class Sirius32Transport(
    private val connection: ISpeeduinoConnection,
    private val onDataReceived: (SpeeduinoLiveData) -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val investigationRecorder: Obd2InvestigationSink? = null,
    private val diagnosticsSink: ConnectionDiagnosticsSink = NoopConnectionDiagnosticsSink,
) : EcuTransport, Obd2DiagnosticsCapable {
    companion object {
        private const val TAG = "Sirius32Transport"
        private const val COMMAND_TIMEOUT_MS = 1200L
        private const val DEFAULT_BATTERY_VOLTAGE = 12.0
        private const val LIVE_DATA_TABLE_ID = "0FAA"
        private const val DTC_STATUS_TABLE_ID = "0893"

        private val INIT_SEQUENCE = listOf(
            "ATZ", "ATE0", "ATL0", "ATH0", "ATSP5",
        )

        /**
         * SID 0x2A, local ID 0x0893: única leitura de grupo confirmada por engenharia
         * reversa que expõe os registradores de status de DTC (0xFDD8=confirmado /
         * 0xFD36=pendente) - mas só a palavra 0 dos 5 pares de 16 bits reais. Ver
         * SIRIUS32_DTC_TABLE em simulator/obd2_tcp_simulator.py para a tabela completa
         * de 60 índices e o porquê só estes 3 têm grupo de leitura conhecido.
         */
        private data class DtcBit(val mask: Int, val code: String, val description: String)

        private val WORD0_DTCS = listOf(
            DtcBit(0x0400, "DF003", "Circuito do sensor de temperatura do ar (IAT)"),
            DtcBit(0x0040, "DF045", "Circuito do sensor de pressão do coletor (MAP)"),
            DtcBit(0x0002, "DF025", "Circuito do sensor de rotação/virabrequim (CKP)"),
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

            INIT_SEQUENCE.forEach { sendRaw(it) }

            val sessionResponse = sendRaw("1029").uppercase()
            if (!sessionResponse.contains("5029") && !sessionResponse.contains("50 29")) {
                throw IllegalStateException("Sirius 32: sessão KWP2000 não abriu (10 29 -> $sessionResponse)")
            }

            val extendedResponse = sendRaw("236B").uppercase()
            if (!extendedResponse.contains("636B") && !extendedResponse.contains("63 6B")) {
                throw IllegalStateException("Sirius 32: modo estendido não abriu (23 6B -> $extendedResponse)")
            }

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
                        onError(err.message ?: "Sirius 32: falha ao ler dados ao vivo")
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
        stopLiveDataStream()
        runCatching { connection.clearInputBuffer() }
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
     */
    override suspend fun clearDtcCodes(): Boolean {
        val armed = sendRaw("1420").uppercase().replace(" ", "")
        if (!armed.contains("5420")) return false
        val executed = sendRaw("146F").uppercase().replace(" ", "")
        return executed.contains("54") && !executed.contains("7F14")
    }

    private suspend fun readDtcCodesForStatus(status: DtcStatus): List<DtcCode> {
        val words = readDtcStatusWords() ?: return emptyList()
        val word = if (status == DtcStatus.ACTIVE) words.first else words.second
        return WORD0_DTCS
            .filter { (word and it.mask) != 0 }
            .map { DtcCode(code = it.code, description = it.description, status = status) }
    }

    private suspend fun readDtcStatusWords(): Pair<Int, Int>? {
        val response = sendRaw("2A$DTC_STATUS_TABLE_ID")
        val payload = parseGroupResponse(DTC_STATUS_TABLE_ID, response) ?: return null
        if (payload.size < 4) return null
        val confirmedWord = (payload[0] shl 8) or payload[1]
        val pendingWord = (payload[2] shl 8) or payload[3]
        return confirmedWord to pendingWord
    }

    private suspend fun pollLiveDataOnce() {
        val response = sendRaw("2A$LIVE_DATA_TABLE_ID")
        val bytes = parseGroupResponse(LIVE_DATA_TABLE_ID, response) ?: return
        if (bytes.size < 6) return

        val rpm = ((bytes[0] shl 8) or bytes[1]).coerceIn(0, 9000)
        val loadPct = bytes[2].coerceIn(0, 255) * 100 / 255
        val coolantC = bytes[4] - 40
        val intakeC = bytes[5] - 40

        val sample = SpeeduinoLiveData(
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

    private suspend fun sendRaw(command: String): String {
        val startedAt = MonotonicClock.nowMillis()
        return runCatching {
            connection.clearInputBuffer()
            connection.send("$command\r".encodeToByteArray())
            val deadline = MonotonicClock.nowMillis() + COMMAND_TIMEOUT_MS
            val builder = StringBuilder()
            while (MonotonicClock.nowMillis() < deadline) {
                val chunk = connection.receive(0)
                if (chunk.isEmpty()) continue
                builder.append(chunk.decodeToString())
                if (builder.contains(">")) break
            }
            builder.toString().trim()
        }.onSuccess { response ->
            investigationRecorder?.recordCommand(
                transport = "sirius32",
                command = command,
                response = response,
                timeoutMs = COMMAND_TIMEOUT_MS,
                elapsedMs = MonotonicClock.nowMillis() - startedAt,
            )
        }.onFailure { error ->
            Logger.d(TAG, "raw command failed cmd=$command err=${error.message}")
            investigationRecorder?.info("command_failure", "cmd=$command reason=${error.message ?: error::class.simpleName}")
        }.getOrThrow()
    }
}
