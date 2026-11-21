package io.ecucore.transport.llm

/**
 * Tradução [ProbeRequest] → comandos ELM327 e resposta ELM → [ProbeStatus]/bytes.
 * Assume `ATE0 ATS1 ATH0 ATL0` (sem eco, com espaços, sem headers na resposta).
 */
object ElmProbeCodec {

    /** Comandos de preparo enviados uma vez no início da sessão. */
    val SETUP_COMMANDS = listOf("ATE0", "ATS1", "ATH0", "ATL0")

    /** Comandos para trocar o header de requisição (já validado). */
    fun headerCommands(header: String): List<String> = when (header.length) {
        // 29-bit: prioridade via ATCP, os 3 bytes restantes via ATSH.
        8 -> listOf("ATCP${header.substring(0, 2)}", "ATSH${header.substring(2)}")
        else -> listOf("ATSH$header")
    }

    fun protocolCommand(protocol: String) = "ATSP$protocol"

    data class Decoded(val status: ProbeStatus, val data: List<Int> = emptyList(), val nrc: Int? = null)

    private val NO_RESPONSE_MARKERS = listOf("NO DATA", "UNABLE TO CONNECT", "STOPPED", "TIMEOUT")
    private val ERROR_MARKERS = listOf("?", "ERROR", "BUFFER FULL", "BUS BUSY", "FB ERROR", "DATA ERROR", "<RX")
    private val FRAME_INDEX = Regex("^[0-9A-F]:")
    private val HEX_PAIR = Regex("^[0-9A-F]{2}$")
    private val ISO_TP_LENGTH = Regex("^[0-9A-F]{3}$")

    fun decode(request: ProbeRequest, response: String): Decoded {
        val upper = response.uppercase()
        val tokens = upper
            .replace(">", "")
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !ISO_TP_LENGTH.matches(it) && !it.startsWith("SEARCHING") }
            .flatMap { line -> line.replace(FRAME_INDEX, "").trim().split(' ') }
            .filter { HEX_PAIR.matches(it) }
            .map { it.toInt(16) }

        val service = request.service.serviceByte
        negativeCode(tokens, service)?.let { return Decoded(ProbeStatus.NEGATIVE, nrc = it) }

        val echo = positiveEcho(request)
        val start = indexOf(tokens, echo)
        if (start >= 0) return Decoded(ProbeStatus.POSITIVE, data = tokens.drop(start + echo.size))

        val silent = tokens.isEmpty() &&
            (NO_RESPONSE_MARKERS.any { it in upper } || ERROR_MARKERS.none { it in upper })
        return Decoded(if (silent) ProbeStatus.NO_RESPONSE else ProbeStatus.ERROR)
    }

    /** Bytes esperados no início da resposta positiva: (serviço + 0x40) + eco do id. */
    fun positiveEcho(request: ProbeRequest): List<Int> {
        val head = request.service.serviceByte + 0x40
        return when (request.service) {
            ReadService.UDS_19_DTC_BY_STATUS -> listOf(head, 0x02)
            else -> listOf(head) + request.id.chunked(2).map { it.toInt(16) }
        }
    }

    private fun negativeCode(tokens: List<Int>, service: Int): Int? {
        for (i in 0 until tokens.size - 2) {
            if (tokens[i] == 0x7F && tokens[i + 1] == service) return tokens[i + 2]
        }
        return null
    }

    private fun indexOf(tokens: List<Int>, pattern: List<Int>): Int {
        if (pattern.isEmpty() || tokens.size < pattern.size) return -1
        for (i in 0..tokens.size - pattern.size) {
            if (pattern.indices.all { tokens[i + it] == pattern[it] }) return i
        }
        return -1
    }
}
