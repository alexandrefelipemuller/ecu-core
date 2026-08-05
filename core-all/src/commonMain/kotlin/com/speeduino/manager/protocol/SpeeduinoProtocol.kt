package com.speeduino.manager.protocol

import kotlin.concurrent.Volatile

import com.speeduino.manager.connection.ConnectionTrace
import com.speeduino.manager.formatPageId
import com.speeduino.manager.ecu.FirmwareHandshakeDomain
import com.speeduino.manager.shared.Logger
import com.speeduino.manager.connection.ISpeeduinoConnection
import com.speeduino.manager.model.EcuFamily
import kotlinx.coroutines.delay
import com.speeduino.manager.shared.Crc32Table
import com.speeduino.manager.shared.MonotonicClock
import com.speeduino.manager.shared.toHex02
import com.speeduino.manager.shared.JvmSynchronized

/**
 * Implementação do protocolo de comunicação Speeduino
 *
 * Independente do transporte - funciona com qualquer implementação de ISpeeduinoConnection.
 * Suporta comandos Legacy (single-byte) e Modern (CRC32-based).
 *
 * Based on Speeduino firmware 202402
 */
class SpeeduinoProtocol(
    private val connection: ISpeeduinoConnection
) {

    companion object {
        // Master switch apenas para debug local. Manter false em produção:
        // USB/BT já forçam legado via supportsModernProtocol() = false
        // e TCP/Wi-Fi precisa de modern protocol (simulador e firmwares novos).
        private const val FORCE_LEGACY_PROTOCOL = false
        private const val VERBOSE_MODERN_FRAME_LOGS = false

        // Response codes
        const val SERIAL_RC_OK = 0x00.toByte()
        const val SERIAL_RC_BURN_OK = 0x04.toByte()
        const val SERIAL_RC_TIMEOUT = 0x80.toByte()
        const val SERIAL_RC_CRC_ERR = 0x82.toByte()
        const val SERIAL_RC_UKWN_ERR = 0x83.toByte()
        const val SERIAL_RC_RANGE_ERR = 0x84.toByte()  // Value out of range (bins não crescentes, etc)
        const val SERIAL_RC_BUSY_ERR = 0x85.toByte()

        // Commands
        const val CMD_LIVE_DATA = 'A'
        const val CMD_PROTOCOL_VERSION = 'F'
        const val CMD_FIRMWARE_VERSION = 'Q'
        const val CMD_PRODUCT_STRING = 'S'
        const val CMD_CAN_ID = 'I'
        const val CMD_CAPABILITY = 'f'
        const val CMD_PAGE_CRC = 'd'
        const val CMD_PAGE_READ = 'p'
        const val CMD_PAGE_WRITE = 'M'
        const val CMD_PAGE_SET = 'P'
        const val CMD_PAGE_WRITE_LEGACY = 'W'
        const val CMD_BURN = 'B'
        const val CMD_OUTPUT_CHANNELS = 'r'

        const val LIVE_DATA_SIZE = 128
        private const val STREAM_READ_SLICE_MS = 250

        // MSnS-Extra hr_10/MS1 real firmware: 'P' selects a page by a raw byte 0-8 (not the
        // ASCII '0'-'9'/'A'-'F' char Speeduino's legacy 'P' expects), and page contents are
        // always read/written whole via 'V'/'W'/'X' - there is no offset/length 'p' command.
        private const val MSEXTRA_HR10_SCHEMA_ID = "msextra-hr10"
    }

    private var lastLegacyWrittenPage: Byte? = null
    @Volatile
    private var sessionLegacyPreferred = false
    @Volatile
    private var sessionEcuFamily: EcuFamily? = null
    @Volatile
    private var sessionSchemaId: String? = null
    @Volatile
    private var legacyPageReadUnsupported = false
    @Volatile
    private var legacyHandshakeUnsupported = false

    /**
     * Enquadramento serial confirmado pela era do firmware, definido depois do handshake.
     *
     * `null` = ainda desconhecido (handshake em andamento) → decide pelos flags do transporte.
     * Quando definido, ele **vence** os flags do transporte: qual enquadramento a ECU entende é
     * propriedade do firmware, não do meio físico. Ver [FirmwareEra.usesModernEnvelope].
     */
    @Volatile
    private var sessionModernEnvelope: Boolean? = null

    fun setSessionLegacyPreferred(preferLegacy: Boolean) {
        sessionLegacyPreferred = preferLegacy
        legacyPageReadUnsupported = false
        legacyHandshakeUnsupported = false
    }

    fun setSessionModernEnvelope(usesModernEnvelope: Boolean?) {
        sessionModernEnvelope = usesModernEnvelope
    }

    fun setSessionEcuFamily(family: EcuFamily?) {
        sessionEcuFamily = family
    }

    fun setSessionSchemaId(schemaId: String?) {
        sessionSchemaId = schemaId
    }

    private fun isModernEnabled(ignoreSessionLegacyPreferred: Boolean = false): Boolean {
        if (FORCE_LEGACY_PROTOCOL) return false
        sessionModernEnvelope?.let { return it }
        return (ignoreSessionLegacyPreferred || !sessionLegacyPreferred) &&
            connection.supportsModernProtocol()
    }

    private fun canAttemptModernFallback(ignoreSessionLegacyPreferred: Boolean = false): Boolean {
        if (FORCE_LEGACY_PROTOCOL) return false
        sessionModernEnvelope?.let { return it }
        return (ignoreSessionLegacyPreferred || !sessionLegacyPreferred) &&
            (connection.supportsModernProtocol() || connection.supportsModernProtocolFallback())
    }

    /**
     * O frame modern coloca no fio `[length 2B] + payload + [CRC32 4B]`. Se o firmware estiver
     * interpretando o stream em modo legacy ASCII, cada byte de length/CRC é consumido como um
     * comando legacy — inclusive 'E' (output test, que aciona injetores/bobinas), 'W'/'M'
     * (escrita na config em RAM) e 'B' (burn na EEPROM). Os bytes de CRC mudam a cada
     * requisição, então basta repetir o suficiente para acertar um deles.
     *
     * No handshake esse risco é aceito de propósito (acontece uma vez, na conexão, e é o único
     * caminho de recuperação para devices que falham no handshake legacy estrito). No live data
     * ele se repetiria a cada tick com o motor em funcionamento, então em transportes
     * legacy-first (Bluetooth/USB) o fallback modern fica bloqueado.
     */
    private fun canAttemptModernLiveData(): Boolean {
        if (FORCE_LEGACY_PROTOCOL) return false
        sessionModernEnvelope?.let { return it }
        return canAttemptModernFallback() &&
            (connection.supportsModernProtocol() || !connection.prefersLegacyProtocol())
    }

    private fun canAttemptModernConfigRead(ignoreSessionLegacyPreferred: Boolean = false): Boolean {
        if (FORCE_LEGACY_PROTOCOL) return false
        sessionModernEnvelope?.let { return it }
        return (ignoreSessionLegacyPreferred || !sessionLegacyPreferred) &&
            connection.supportsModernConfigReads()
    }

    private fun requireModernCommandSupport(operation: String, ignoreSessionLegacyPreferred: Boolean = false) {
        if (!canAttemptModernFallback(ignoreSessionLegacyPreferred)) {
            throw Exception("Modern protocol unavailable for $operation on this connection")
        }
    }

    /**
     * Obtém informações do firmware (comando 'Q')
     */
    suspend fun getFirmwareInfo(): String {
        return queryFirmwareInfoCandidates(
            commands = listOf('Q'.code.toByte(), 'S'.code.toByte()),
            label = "firmware info",
        )
    }

    /**
     * Consulta estrita legacy para assinatura de firmware (somente comando 'Q').
     * Usado no caminho de compatibilidade USB/serial para manter comportamento antigo.
     */
    suspend fun getFirmwareInfoLegacyStrict(): String {
        val response = sendLegacyCommand('Q'.code.toByte())
        if (response.isEmpty()) {
            throw Exception("Legacy firmware info returned empty response")
        }
        val parsed = parseLegacyStringResponse(response, "firmware info")
        if (parsed.isBlank()) {
            throw Exception("Legacy firmware info returned blank response")
        }
        return parsed
    }

    /**
     * Consulta legacy resiliente para assinatura de firmware.
     * Alguns ECUs/transportes devolvem melhor resposta em 'S' do que em 'Q',
     * ou misturam bytes espúrios no mesmo burst.
     */
    suspend fun getFirmwareInfoLegacyCandidates(): List<String> {
        val candidates = mutableListOf<String>()
        listOf('Q'.code.toByte(), 'S'.code.toByte()).forEach { cmd ->
            val parsed = runCatching { queryLegacyFirmwareCandidate(cmd, "firmware info") }
                .onFailure {
                    Logger.w(
                        "SpeeduinoProtocol",
                        "Legacy firmware candidate cmd=${cmd.toInt().toChar()} failed: ${it.message}"
                    )
                }
                .getOrNull()
            if (!parsed.isNullOrBlank()) {
                candidates += parsed
            }
        }
        return candidates
    }

    /**
     * Obtém string do produto (comando 'S')
     */
    suspend fun getProductString(): String {
        // O simulador rusEFI aceita o mesmo fluxo S/V antes dos reads de tabela; serve para os demais ECUs também.
        val commands = listOf('S'.code.toByte(), 'V'.code.toByte())
        return queryStringCandidates(commands, "product string")
    }

    /**
     * Obtém capacidades seriais (comando 'f')
     */
    suspend fun getSerialCapability(): SerialCapability {
        if (!isModernEnabled()) {
            Logger.w("SpeeduinoProtocol", "Modern protocol disabled, skipping serial capability query")
            return SerialCapability(0, 0, 0)
        }

        return try {
            val response = sendModernCommand('f'.code.toByte(), byteArrayOf(0x00))

            if (response.size >= 6 && response[0] == SERIAL_RC_OK) {
                val protocolVersion = response[1].toInt() and 0xFF
                val blockingFactor = ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF)
                val tableBlockingFactor = ((response[4].toInt() and 0xFF) shl 8) or (response[5].toInt() and 0xFF)
                SerialCapability(protocolVersion, blockingFactor, tableBlockingFactor)
            } else {
                fallbackProtocolCapability()
            }
        } catch (e: Exception) {
            Logger.w("SpeeduinoProtocol", "Modern serial capability query failed, trying protocol-version fallback: ${e.message}")
            fallbackProtocolCapability()
        }
    }

    /**
     * Lê CRC32 de uma página (comando 'd')
     */
    suspend fun getPageCRC(pageNum: Byte): Long {
        if (!isModernEnabled()) {
            val response = sendLegacyCommand(
                'd'.code.toByte(),
                payload = byteArrayOf(0x00, pageNum),
                responseSize = 4
            )
            return if (response.size == 4) {
                readU32BE(response, 0)
            } else {
                0L
            }
        }

        val response = sendModernCommand('d'.code.toByte(), byteArrayOf(pageNum))

        return if (response.size >= 5 && response[0] == SERIAL_RC_OK) {
            readU32BE(response, 1)
        } else {
            0L
        }
    }

    /**
     * Lê uma página completa (comando 'p')
     * @param pageNum Número da página (0-15)
     * @param offset Offset inicial
     * @param length Tamanho a ler
     */
    suspend fun readPage(
        pageNum: Byte,
        offset: Int,
        length: Int,
        allowPartial: Boolean = false,
        allowModernTransportFallback: Boolean = false,
    ): ByteArray {
        val pageLabel = formatPageId(pageNum)
        return try {
            val useModernPath = if (allowModernTransportFallback) {
                canAttemptModernConfigRead(ignoreSessionLegacyPreferred = true)
            } else {
                isModernEnabled() ||
                    (legacyPageReadUnsupported && connection.supportsModernProtocolFallback())
            }
            if (!useModernPath) {
                Logger.d("SpeeduinoProtocol", "readPage (LEGACY): pageNum=$pageLabel, offset=$offset, length=$length")
                val data = if (sessionSchemaId == MSEXTRA_HR10_SCHEMA_ID) {
                    readPageMs1Legacy(pageNum, pageLabel)
                } else {
                    val payload = ByteArray(6)
                    payload[0] = 0x00
                    payload[1] = pageNum
                    payload[2] = (offset and 0xFF).toByte()
                    payload[3] = ((offset shr 8) and 0xFF).toByte()
                    payload[4] = (length and 0xFF).toByte()
                    payload[5] = ((length shr 8) and 0xFF).toByte()
                    sendLegacyCommand('p'.code.toByte(), payload = payload, expectResponse = false)
                    readLegacyPageData(pageLabel)
                }.let { fullOrRequested ->
                    if (sessionSchemaId == MSEXTRA_HR10_SCHEMA_ID) {
                        if (offset + length > fullOrRequested.size) {
                            throw Exception(
                                "MS1 page response too short: expected offset+length=${offset + length} received=${fullOrRequested.size}"
                            )
                        }
                        fullOrRequested.copyOfRange(offset, offset + length)
                    } else {
                        fullOrRequested
                    }
                }
                if (!allowPartial && data.size < length) {
                    throw Exception("short legacy page response: expected=$length received=${data.size}")
                }
                if (data.size > length) {
                    Logger.w(
                        "SpeeduinoProtocol",
                        "legacy page response larger than requested; trimming page=$pageLabel expected=$length received=${data.size}"
                    )
                    data.copyOf(length)
                } else {
                    data
                }
            } else {
                readPageModern(pageNum, offset, length, allowConfigReadFallback = allowModernTransportFallback)
            }
        } catch (e: Exception) {
            if (!isModernEnabled() && connection.supportsModernProtocolFallback()) {
                Logger.w(
                    "SpeeduinoProtocol",
                    "Legacy page read failed, trying modern fallback page=$pageLabel offset=$offset length=$length: ${e.message}"
                )
                return try {
                    readPageModern(pageNum, offset, length).also {
                        legacyPageReadUnsupported = true
                    }
                } catch (fallbackError: Exception) {
                    throw Exception(
                        "Page read failed page=$pageLabel offset=$offset length=$length detail=${fallbackError.message ?: e.message ?: "unknown"}",
                        fallbackError
                    )
                }
            }
            throw Exception(
                "Page read failed page=$pageLabel offset=$offset length=$length detail=${e.message ?: "unknown"}",
                e
            )
        }
    }

    private suspend fun readPageModern(
        pageNum: Byte,
        offset: Int,
        length: Int,
        allowConfigReadFallback: Boolean = false,
    ): ByteArray {
        val pageLabel = formatPageId(pageNum)
        val ecuFamily = sessionEcuFamily
        val useTableEnvelope = ecuFamily == EcuFamily.MS2 || ecuFamily == EcuFamily.MEGASPEED || ecuFamily == EcuFamily.MS3
        val response = if (useTableEnvelope) {
            val payload = ByteArray(6)
            payload[0] = 0x00
            payload[1] = pageNum
            payload[2] = ((offset shr 8) and 0xFF).toByte()
            payload[3] = (offset and 0xFF).toByte()
            payload[4] = ((length shr 8) and 0xFF).toByte()
            payload[5] = (length and 0xFF).toByte()

            Logger.d("SpeeduinoProtocol", "readPage (TABLE): pageNum=$pageLabel, offset=$offset, length=$length, family=${ecuFamily.name}")
            Logger.d("SpeeduinoProtocol", "Payload bytes: ${payload.joinToString(" ") { "0x${it.toHex02()}" }}")

            sendModernCommand(
                'r'.code.toByte(),
                payload,
                ignoreSessionLegacyPreferred = allowConfigReadFallback,
                allowConfigReadFallback = allowConfigReadFallback,
            )
        } else {
            val payload = ByteArray(6)
            payload[0] = 0x00  // Padding byte
            payload[1] = pageNum

            // Offset (2 bytes, little-endian)
            payload[2] = (offset and 0xFF).toByte()           // LSB first
            payload[3] = ((offset shr 8) and 0xFF).toByte()   // MSB second

            // Length (2 bytes, little-endian)
            payload[4] = (length and 0xFF).toByte()           // LSB first
            payload[5] = ((length shr 8) and 0xFF).toByte()   // MSB second

            Logger.d("SpeeduinoProtocol", "readPage: pageNum=$pageLabel, offset=$offset, length=$length")
            Logger.d("SpeeduinoProtocol", "Payload bytes: ${payload.joinToString(" ") { "0x${it.toHex02()}" }}")

            sendModernCommand(
                'p'.code.toByte(),
                payload,
                ignoreSessionLegacyPreferred = allowConfigReadFallback,
                allowConfigReadFallback = allowConfigReadFallback,
            )
        }

        Logger.d("SpeeduinoProtocol", "Response size: ${response.size} bytes")
        Logger.d("SpeeduinoProtocol", "Response first bytes: ${response.take(10).joinToString(" ") { "0x${it.toHex02()}" }}")

        if (response.isEmpty() || response[0] != SERIAL_RC_OK) {
            throw Exception("unexpected response code=${response.getOrNull(0)?.toUByte()?.toString(16) ?: "null"}")
        }

        val data = response.copyOfRange(1, response.size)
        if (data.size != length) {
            throw Exception("short modern page response: expected=$length received=${data.size}")
        }
        return data
    }

    /**
     * Lê uma tabela/genpage via protocolo moderno ('r' + table id).
     * Usado por ECUs MS3/newserial.
     */
    suspend fun readTable(tableId: Byte, offset: Int, length: Int): ByteArray {
        return readTable(tableId.toInt() and 0xFF, offset, length, EcuFamily.MS3)
    }

    suspend fun readTable(
        tableId: Int,
        offset: Int,
        length: Int,
        family: EcuFamily,
        allowPartial: Boolean = false,
        allowModernTransportFallback: Boolean = false,
    ): ByteArray {
        if (allowModernTransportFallback) {
            if (!canAttemptModernConfigRead(ignoreSessionLegacyPreferred = true)) {
                throw Exception("Modern config read unavailable for table read on this connection")
            }
        } else {
            requireModernCommandSupport("table read", ignoreSessionLegacyPreferred = family == EcuFamily.RUSEFI)
        }
        val tableLabel = formatPageId(tableId)
        return try {
            val response = if (family == EcuFamily.RUSEFI) {
                val payload = byteArrayOf(
                    (tableId and 0xFF).toByte(),
                    ((tableId shr 8) and 0xFF).toByte(),
                    (offset and 0xFF).toByte(),
                    ((offset shr 8) and 0xFF).toByte(),
                    (length and 0xFF).toByte(),
                    ((length shr 8) and 0xFF).toByte(),
                )
                sendModernCommand(
                    'R'.code.toByte(),
                    payload,
                    ignoreSessionLegacyPreferred = true,
                    allowConfigReadFallback = allowModernTransportFallback,
                )
            } else {
                val payload = ByteArray(6)
                payload[0] = 0x00
                payload[1] = (tableId and 0xFF).toByte()
                payload[2] = ((offset shr 8) and 0xFF).toByte()
                payload[3] = (offset and 0xFF).toByte()
                payload[4] = ((length shr 8) and 0xFF).toByte()
                payload[5] = (length and 0xFF).toByte()
                sendModernCommand('r'.code.toByte(), payload, allowConfigReadFallback = allowModernTransportFallback)
            }

            if (response.isEmpty() || response[0] != SERIAL_RC_OK) {
                throw Exception("unexpected response code=${response.getOrNull(0)?.toUByte()?.toString(16) ?: "null"}")
            }

            val data = response.copyOfRange(1, response.size)
            if (!allowPartial && data.size != length) {
                throw Exception("short table response: expected=$length received=${data.size}")
            }
            if (allowPartial && data.size > length) data.copyOf(length) else data
        } catch (e: Exception) {
            throw Exception(
                "Table read failed table=$tableLabel family=${family.name} offset=$offset length=$length detail=${e.message ?: "unknown"}",
                e
            )
        }
    }

    /**
     * Lê dados em tempo real (comando 'A' legacy)
     * ⚠️ DEPRECATED: Speeduino modernas (202402+) podem ter isso desabilitado
     */
    suspend fun readLiveData(expectedSize: Int = LIVE_DATA_SIZE): ByteArray {
        require(expectedSize > 0) { "Tamanho inválido de live data: $expectedSize" }
        val response = try {
            sendLegacyCommand('A'.code.toByte(), responseSize = expectedSize)
        } catch (e: Exception) {
            if (!isZeroByteTimeout(e, expectedSize)) {
                throw e
            }

            Logger.w(
                "SpeeduinoProtocol",
                "LiveData timeout 0 bytes (expected=$expectedSize), flushing input and retrying once"
            )
            connection.clearInputBuffer()
            delay(25)
            sendLegacyCommand('A'.code.toByte(), responseSize = expectedSize)
        }

        return if (response.size >= expectedSize) {
            response
        } else {
            throw Exception("Resposta de live data incompleta: ${response.size}/$expectedSize bytes")
        }
    }

    private fun isZeroByteTimeout(error: Exception, expectedSize: Int): Boolean {
        val message = error.message ?: return false
        return message.contains("Timeout: expected $expectedSize bytes, received 0")
    }

    /**
     * Lê dados em tempo real usando Modern Protocol (comando 'r' + Output Channels)
     * ✅ RECOMENDADO: Comando usado pelo TunerStudio
     *
     * Formato: 'r' + CAN_ID + Subcmd + Offset(LSB/MSB) + Length(LSB/MSB)
     *          0x72  0x00    0x30    0x0000        0x007F (127 bytes)
     */
    suspend fun readLiveDataModern(length: Int): ByteArray {
        if (!canAttemptModernLiveData()) {
            throw Exception("Modern protocol unavailable for modern live data on this connection")
        }
        require(length > 0) { "Live data length inválido: $length" }

        val lengthLsb = (length and 0xFF).toByte()
        val lengthMsb = ((length shr 8) and 0xFF).toByte()

        // Payload: 'r' + CAN_ID(0x00) + Subcmd(0x30) + Offset(0x0000) + Length
        val payload = byteArrayOf(
            0x00,        // CAN ID (always 0 for standard Speeduino)
            0x30.toByte(), // Subcmd: Output Channels (48 decimal)
            0x00,        // Offset LSB (start at byte 0)
            0x00,        // Offset MSB
            lengthLsb,   // Length LSB
            lengthMsb    // Length MSB
        )

        val response = sendModernCommand('r'.code.toByte(), payload)

        return if (response.isNotEmpty() && response[0] == SERIAL_RC_OK) {
            // Retorna dados sem o response code
            response.sliceArray(1 until response.size)
        } else {
            val code = response.getOrNull(0)?.toInt()?.and(0xFF)
            throw Exception("Erro ao ler live data modern: response code = ${code?.let { "0x${it.toString(16)}" } ?: "null"}")
        }
    }

    suspend fun readRusefiOutputChannels(length: Int, offset: Int = 0): ByteArray {
        requireModernCommandSupport("rusEFI output channels", ignoreSessionLegacyPreferred = true)
        require(length > 0) { "rusEFI output length inválido: $length" }

        val payload = byteArrayOf(
            (offset and 0xFF).toByte(),
            ((offset shr 8) and 0xFF).toByte(),
            (length and 0xFF).toByte(),
            ((length shr 8) and 0xFF).toByte(),
        )

        val response = sendModernCommand(
            'O'.code.toByte(),
            payload,
            maxResponseSize = length + 1,
            ignoreSessionLegacyPreferred = true
        )
        return if (response.isNotEmpty() && response[0] == SERIAL_RC_OK) {
            response.copyOfRange(1, response.size)
        } else {
            val code = response.getOrNull(0)?.toInt()?.and(0xFF)
            throw Exception("Erro ao ler output channels rusEFI: ${code?.let { "0x${it.toString(16)}" } ?: "null"}")
        }
    }

    suspend fun sendLegacyPassthrough(
        command: ByteArray,
        expectResponse: Boolean = false,
        responseSize: Int? = null,
    ): ByteArray {
        require(command.isNotEmpty()) { "Comando legacy vazio" }
        val payload = if (command.size > 1) {
            command.copyOfRange(1, command.size)
        } else {
            byteArrayOf()
        }
        return sendLegacyCommand(
            cmd = command[0],
            payload = payload,
            responseSize = responseSize,
            expectResponse = expectResponse,
        )
    }

    /**
     * Envia comando legacy (single-byte)
     */
    // Base de conhecimento (USB serial/OTG): sem serialização global, chamadas paralelas
    // (ex.: stream + write/refresh) podem misturar TX/RX no mesmo canal e corromper frame.
    @JvmSynchronized
    private fun sendLegacyCommand(
        cmd: Byte,
        payload: ByteArray = byteArrayOf(),
        responseSize: Int? = null,
        expectResponse: Boolean = true
    ): ByteArray {
        if (!connection.isConnected()) {
            throw Exception("Não conectado")
        }

        val packet = if (payload.isEmpty()) {
            byteArrayOf(cmd)
        } else {
            byteArrayOf(cmd) + payload
        }

        connection.send(packet)
        if (!expectResponse) {
            return ByteArray(0)
        }

        return if (responseSize != null) {
            connection.receive(responseSize)
        } else {
            connection.receive()
        }
    }

    private fun fallbackLegacyString(cmd: Byte, label: String): String {
        val response = sendLegacyCommand(cmd)
        if (response.isEmpty()) {
            Logger.w("SpeeduinoProtocol", "Legacy $label returned empty response")
            return "Unknown"
        }

        val cleaned = parseLegacyStringResponse(response, label)

        Logger.w("SpeeduinoProtocol", "Legacy $label response: ${response.joinToString(" ") { "0x${it.toHex02()}" }}")
        return cleaned.ifBlank { "Unknown" }
    }

    private fun parseLegacyStringResponse(response: ByteArray, label: String): String {
        val framedString = parseModernFrameString(response, label)
        if (framedString != null) {
            return framedString
        }

        val directText = extractAsciiPrefix(response)
        val printableText = extractPrintableAsciiText(response)
        val knownFirmwareText = extractKnownFirmwareText(printableText)

        return when {
            !knownFirmwareText.isNullOrBlank() -> knownFirmwareText
            isMeaningfulLegacyText(directText) -> directText
            isMeaningfulLegacyText(printableText) -> printableText
            else -> directText.ifBlank { printableText }
        }
    }

    private fun isKnownErrorResponseCode(code: Byte): Boolean = code == SERIAL_RC_TIMEOUT ||
        code == SERIAL_RC_CRC_ERR ||
        code == SERIAL_RC_UKWN_ERR ||
        code == SERIAL_RC_RANGE_ERR ||
        code == SERIAL_RC_BUSY_ERR

    private fun parseModernFrameString(response: ByteArray, label: String): String? {
        if (response.size < 7) {
            return null
        }

        val length = ((response[0].toInt() and 0xFF) shl 8) or (response[1].toInt() and 0xFF)
        if (length <= 0 || length > 2048) {
            return null
        }

        val expectedSize = 2 + length + 4
        if (response.size < expectedSize) {
            Logger.w("SpeeduinoProtocol", "Legacy $label frame incomplete: ${response.size}/$expectedSize bytes")
            return null
        }

        val payload = response.copyOfRange(2, 2 + length)
        val crcBytes = response.copyOfRange(2 + length, 2 + length + 4)
        val receivedCrc = readU32BE(crcBytes, 0)
        val calculatedCrc = calculateCRC32(payload)

        if (receivedCrc != 0L && receivedCrc != calculatedCrc) {
            Logger.w(
                "SpeeduinoProtocol",
                "Legacy $label frame CRC mismatch (received=0x${receivedCrc.toString(16)}, calculated=0x${calculatedCrc.toString(16)})"
            )
        }

        if (payload.size == 1 && isKnownErrorResponseCode(payload[0])) {
            legacyHandshakeUnsupported = true
            Logger.w(
                "SpeeduinoProtocol",
                "Legacy $label frame carries modern error code 0x${(payload[0].toInt() and 0xFF).toString(16)}; " +
                    "not a firmware signature, marking legacy handshake unsupported"
            )
            return ""
        }

        val payloadText = if (payload.isNotEmpty() && payload[0] == SERIAL_RC_OK) {
            payload.copyOfRange(1, payload.size)
        } else {
            payload
        }

        val zeroIndex = payloadText.indexOf(0)
        val lengthText = if (zeroIndex >= 0) zeroIndex else payloadText.size
        return payloadText.decodeToString(0, lengthText).trim()
    }

    private fun safeLegacyString(cmd: Byte, label: String): String {
        return try {
            fallbackLegacyString(cmd, label)
        } catch (e: Exception) {
            Logger.w("SpeeduinoProtocol", "Legacy $label failed: ${e.message}")
            "Unknown"
        }
    }

    private fun queryStringCandidates(commands: List<Byte>, label: String): String {
        val legacyFirst = connection.prefersLegacyProtocol()

        commands.forEach { cmd ->
            if (legacyFirst) {
                val legacyValue = runCatching { queryLegacyString(cmd, label) }
                    .onFailure { Logger.w("SpeeduinoProtocol", "Legacy $label cmd=${cmd.toInt().toChar()} failed: ${it.message}") }
                    .getOrNull()
                if (!legacyValue.isNullOrBlank() && !legacyValue.equals("Unknown", ignoreCase = true)) {
                    return legacyValue
                }

                val modernValue = runCatching { queryModernString(cmd) }
                    .onFailure {
                        Logger.w("SpeeduinoProtocol", "Modern $label cmd=${cmd.toInt().toChar()} failed: ${it.message}")
                        delayAfterLegacyAsciiModernResponse()
                    }
                    .getOrNull()
                if (!modernValue.isNullOrBlank()) {
                    return modernValue
                }
            } else {
                val modernValue = runCatching { queryModernString(cmd) }
                    .onFailure {
                        Logger.w("SpeeduinoProtocol", "Modern $label cmd=${cmd.toInt().toChar()} failed: ${it.message}")
                        delayAfterLegacyAsciiModernResponse()
                    }
                    .getOrNull()
                if (!modernValue.isNullOrBlank()) {
                    return modernValue
                }

                val legacyValue = runCatching { queryLegacyString(cmd, label) }
                    .onFailure { Logger.w("SpeeduinoProtocol", "Legacy $label cmd=${cmd.toInt().toChar()} failed: ${it.message}") }
                    .getOrNull()
                if (!legacyValue.isNullOrBlank() && !legacyValue.equals("Unknown", ignoreCase = true)) {
                    return legacyValue
                }
            }
        }

        return "Unknown"
    }

    private fun queryModernString(cmd: Byte): String? {
        if (!canAttemptModernFallback()) return null

        clearInputBufferBeforeAsciiQuery()
        var response = sendModernCommand(cmd, byteArrayOf())
        if (response.size == 1 && isKnownErrorResponseCode(response[0])) {
            // Possible stale error frame queued from an earlier legacy attempt; drain and retry once.
            Logger.w(
                "SpeeduinoProtocol",
                "Modern string query cmd=${cmd.toInt().toChar()} got stale error frame 0x${(response[0].toInt() and 0xFF).toString(16)}, retrying after drain"
            )
            clearInputBufferBeforeAsciiQuery()
            response = sendModernCommand(cmd, byteArrayOf())
        }
        if (response.isEmpty() || response[0] != SERIAL_RC_OK || response.size <= 1) {
            return null
        }

        val payload = response.copyOfRange(1, response.size)
        val directText = extractAsciiPrefix(payload)
        val printableText = extractPrintableAsciiText(payload)
        val knownFirmwareText = extractKnownFirmwareText(printableText)

        return when {
            !knownFirmwareText.isNullOrBlank() -> knownFirmwareText
            isMeaningfulLegacyText(directText) -> directText
            isMeaningfulLegacyText(printableText) -> printableText
            else -> directText.ifBlank { printableText }
        }.takeIf { it.isNotBlank() }
    }

    private fun queryFirmwareInfoCandidates(commands: List<Byte>, label: String): String {
        val legacyFirst = connection.prefersLegacyProtocol()

        commands.forEach { cmd ->
            if (legacyFirst) {
                val legacyValue = runCatching { queryLegacyFirmwareCandidate(cmd, label) }
                    .onFailure { Logger.w("SpeeduinoProtocol", "Legacy $label cmd=${cmd.toInt().toChar()} failed: ${it.message}") }
                    .getOrNull()
                if (!legacyValue.isNullOrBlank() && !legacyValue.equals("Unknown", ignoreCase = true)) {
                    return legacyValue
                }

                val modernValue = runCatching { queryFirmwareModernCandidate(cmd) }
                    .onFailure { Logger.w("SpeeduinoProtocol", "Modern $label cmd=${cmd.toInt().toChar()} failed: ${it.message}") }
                    .getOrNull()
                if (!modernValue.isNullOrBlank()) {
                    return modernValue
                }
            } else {
                val modernValue = runCatching { queryFirmwareModernCandidate(cmd) }
                    .recoverCatching { error ->
                        if (error is LegacyAsciiModernResponseException) {
                            sessionLegacyPreferred = true
                            Logger.w(
                                "SpeeduinoProtocol",
                                "Modern $label cmd=${cmd.toInt().toChar()} received legacy ASCII, retrying legacy"
                            )
                            delayAfterLegacyAsciiModernResponse()
                            return@recoverCatching queryLegacyFirmwareCandidate(cmd, label)
                        }
                        delayAfterLegacyAsciiModernResponse()
                        throw error
                    }
                    .onFailure {
                        Logger.w("SpeeduinoProtocol", "Modern $label cmd=${cmd.toInt().toChar()} failed: ${it.message}")
                        delayAfterLegacyAsciiModernResponse()
                    }
                    .getOrNull()
                if (!modernValue.isNullOrBlank()) {
                    return modernValue
                }

                val legacyValue = runCatching { queryLegacyFirmwareCandidate(cmd, label) }
                    .onFailure { Logger.w("SpeeduinoProtocol", "Legacy $label cmd=${cmd.toInt().toChar()} failed: ${it.message}") }
                    .getOrNull()
                if (!legacyValue.isNullOrBlank() && !legacyValue.equals("Unknown", ignoreCase = true)) {
                    return legacyValue
                }
            }
        }

        return "Unknown"
    }

    private fun queryFirmwareModernCandidate(cmd: Byte): String? {
        if (!canAttemptModernFallback()) return null

        clearInputBufferBeforeAsciiQuery()
        val response = sendModernCommand(cmd, byteArrayOf())
        if (response.isEmpty() || response[0] != SERIAL_RC_OK || response.size <= 1) {
            return null
        }

        val payload = response.copyOfRange(1, response.size)
        val directText = extractAsciiPrefix(payload)
        val printableText = extractPrintableAsciiText(payload)
        val knownFirmwareText = extractKnownFirmwareText(printableText)

        val candidate = when {
            !knownFirmwareText.isNullOrBlank() -> knownFirmwareText
            FirmwareHandshakeDomain.normalizeSignature(directText) != null -> FirmwareHandshakeDomain.normalizeSignature(directText)
            FirmwareHandshakeDomain.normalizeSignature(printableText) != null -> FirmwareHandshakeDomain.normalizeSignature(printableText)
            else -> null
        }

        return candidate?.takeIf { it.isNotBlank() }
    }

    private fun delayAfterLegacyAsciiModernResponse() {
        connection.clearInputBuffer()
        com.speeduino.manager.shared.sleepMillis(30)
        connection.clearInputBuffer()
    }

    private fun clearInputBufferBeforeAsciiQuery() {
        runCatching { connection.clearInputBuffer() }
    }

    private fun queryLegacyString(cmd: Byte, label: String): String? {
        if (legacyHandshakeUnsupported) {
            Logger.w("SpeeduinoProtocol", "Skipping legacy $label query, firmware already proved modern-only")
            return null
        }
        clearInputBufferBeforeAsciiQuery()
        val response = sendLegacyCommand(cmd)
        if (response.isEmpty()) {
            Logger.w("SpeeduinoProtocol", "Legacy $label returned empty response")
            traceLegacyHandshake(
                cmd = cmd,
                label = label,
                response = response,
                parsed = null,
            )
            return null
        }

        val parsed = parseLegacyStringResponse(response, label).takeIf { it.isNotBlank() }
        traceLegacyHandshake(
            cmd = cmd,
            label = label,
            response = response,
            parsed = parsed,
        )
        return parsed
    }

    private fun queryLegacyFirmwareCandidate(cmd: Byte, label: String): String? {
        if (legacyHandshakeUnsupported) {
            Logger.w("SpeeduinoProtocol", "Skipping legacy $label candidate, firmware already proved modern-only")
            return null
        }
        clearInputBufferBeforeAsciiQuery()
        val response = sendLegacyCommand(cmd)
        if (response.isEmpty()) {
            Logger.w("SpeeduinoProtocol", "Legacy $label returned empty response")
            traceLegacyHandshake(
                cmd = cmd,
                label = label,
                response = response,
                parsed = null,
            )
            return null
        }

        val parsed = parseLegacyStringResponse(response, label)
        val normalized = FirmwareHandshakeDomain.normalizeSignature(parsed)
        val knownFirmware = extractKnownFirmwareText(parsed)
        val candidate = normalized ?: knownFirmware?.let(FirmwareHandshakeDomain::normalizeSignature)

        traceLegacyHandshake(
            cmd = cmd,
            label = label,
            response = response,
            parsed = candidate,
        )
        return candidate
    }

    private fun traceLegacyHandshake(
        cmd: Byte,
        label: String,
        response: ByteArray,
        parsed: String?,
    ) {
        val transport = inferTraceTransport()
        val rawHex = response.joinToString(" ") { it.toHex02() }.ifBlank { "<empty>" }
        val sanitized = parsed?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        ConnectionTrace.info(
            transport,
            "legacy_handshake cmd=${cmd.toInt().toChar()} label=$label len=${response.size} raw=$rawHex parsed=${if (sanitized.isBlank()) "<blank>" else sanitized.take(120)}"
        )
    }

    private fun inferTraceTransport(): String {
        val info = connection.getConnectionInfo().lowercase()
        return when {
            info.startsWith("bluetooth:") -> "bluetooth"
            info.startsWith("tcp:") -> "tcp"
            info.startsWith("usb") -> "usb"
            else -> "protocol"
        }
    }

    private fun extractAsciiPrefix(response: ByteArray): String {
        val zeroIndex = response.indexOf(0)
        val length = if (zeroIndex >= 0) zeroIndex else response.size
        return response.decodeToString(0, length).trim()
    }

    private fun extractPrintableAsciiText(response: ByteArray): String {
        return response.map { byte ->
            when (val value = byte.toInt() and 0xFF) {
                0x09, 0x0A, 0x0D, 0x00 -> ' '
                in 0x20..0x7E -> value.toChar()
                else -> ' '
            }
        }.joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractKnownFirmwareText(text: String): String? {
        val patterns = listOf(
            Regex("""(?i)\b[a-z]*duino\s*[_-]?\s*20\d{4}(?:[._-]?[a-z0-9]+)?"""),
            Regex("""(?i)\b[a-z]*duino\s*[_-]?\s*20\d{2}\.\d{2}(?:[._-]?[a-z0-9]+)?"""),
            Regex("""(?i)\bMS2Extra\s+MegaSpeed\b"""),
            Regex("""(?i)\bMS2Extra\s+comms[0-9a-z]+\b"""),
            Regex("""(?i)\bMS\/Extra\s+format\s+hr_10\b(?:\s+\*+)?"""),
            Regex("""(?i)\bMS\/Extra\s+format\s+hr_11d\b(?:\s+\*+)?"""),
            Regex("""(?i)\bMS3\s+Format\s+[0-9]{4}\.[0-9]{2}[a-z]?\b"""),
            Regex("""(?i)\brusEFI\s+[A-Za-z0-9._-]+\b(?:\.[A-Za-z0-9._-]+)*"""),
        )

        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(text)?.value?.trim()
        }
    }

    private fun isMeaningfulLegacyText(text: String): Boolean {
        return text.length >= 4 && text.any(Char::isLetterOrDigit)
    }

    private fun fallbackProtocolCapability(): SerialCapability {
        Logger.w(
            "SpeeduinoProtocol",
            "Skipping protocol-version fallback probe; using default serial capability for legacy-compatible connection"
        )
        return SerialCapability(
            protocolVersion = 1,
            blockingFactor = 256,
            tableBlockingFactor = 256,
        )
    }

    /**
     * Envia comando modern (CRC32-based)
     */
    // Mantém atomicidade send+receive também no caminho modern.
    @JvmSynchronized
    private fun sendModernCommand(
        cmd: Byte,
        extraPayload: ByteArray,
        maxResponseSize: Int = 2048,
        ignoreSessionLegacyPreferred: Boolean = false,
        allowConfigReadFallback: Boolean = false,
    ): ByteArray {
        val modernAllowed = if (allowConfigReadFallback) {
            canAttemptModernConfigRead(ignoreSessionLegacyPreferred)
        } else {
            canAttemptModernFallback(ignoreSessionLegacyPreferred)
        }
        if (!modernAllowed) {
            throw Exception("Modern protocol disabled for this connection")
        }
        if (!connection.isConnected()) {
            throw Exception("Não conectado")
        }

        val payload = byteArrayOf(cmd) + extraPayload
        val crc = calculateCRC32(payload)

        // Length (2 bytes, big-endian)
        val lengthBytes = writeU16BE(payload.size)

        // CRC32 (4 bytes, big-endian)
        val crcBytes = writeU32BE(crc)

        // Send: length + payload + crc
        val packet = lengthBytes + payload + crcBytes
        connection.send(packet)

        return readModernResponse(cmd, maxResponseSize)
    }

    /**
     * Lê resposta modern (length + payload + crc32)
     */
    private fun readModernResponse(cmd: Byte, maxResponseSize: Int = 2048): ByteArray {
        // Read length (2 bytes, big-endian)
        val lengthBytes = try {
            readExactly(2, "modern length cmd=0x${cmd.toInt().and(0xFF).toString(16)}")
        } catch (e: Exception) {
            throw ModernResponseReadException("length", cmd, 2, e)
        }
        if (lengthBytes.size < 2) {
            throw IncompleteResponseException("length", 2, lengthBytes.size, cmd)
        }
        if (VERBOSE_MODERN_FRAME_LOGS) {
            Logger.d("SpeeduinoProtocol", "Length bytes: ${lengthBytes.joinToString(" ") { "0x${it.toHex02()}" }}")
        }

        val length = readU16BE(lengthBytes, 0)

        if (VERBOSE_MODERN_FRAME_LOGS) {
            Logger.d("SpeeduinoProtocol", "Payload length: $length bytes")
        }

        if (length > maxResponseSize) {
            if (looksLikeLegacyAscii(lengthBytes)) {
                connection.clearInputBuffer()
                throw LegacyAsciiModernResponseException(lengthBytes)
            }
            connection.clearInputBuffer()
            throw Exception("Resposta muito grande: $length bytes")
        }

        // Read payload
        val payload = try {
            readExactly(length, "modern payload cmd=0x${cmd.toInt().and(0xFF).toString(16)}")
        } catch (e: Exception) {
            throw ModernResponseReadException("payload", cmd, length, e)
        }
        if (payload.size < length) {
            throw IncompleteResponseException("payload", length, payload.size, cmd)
        }
        if (VERBOSE_MODERN_FRAME_LOGS) {
            Logger.d("SpeeduinoProtocol", "Payload bytes: ${payload.joinToString(" ") { "0x${it.toHex02()}" }}")
        }

        // Read CRC32 (4 bytes, big-endian)
        val crcBytes = try {
            readExactly(4, "modern crc cmd=0x${cmd.toInt().and(0xFF).toString(16)}")
        } catch (e: Exception) {
            throw ModernResponseReadException("crc", cmd, 4, e)
        }
        if (crcBytes.size < 4) {
            throw IncompleteResponseException("crc", 4, crcBytes.size, cmd)
        }
        if (VERBOSE_MODERN_FRAME_LOGS) {
            Logger.d("SpeeduinoProtocol", "CRC bytes: ${crcBytes.joinToString(" ") { "0x${it.toHex02()}" }}")
        }

        val receivedCrc = readU32BE(crcBytes, 0)

        val calculatedCrc = calculateCRC32(payload)

        if (VERBOSE_MODERN_FRAME_LOGS) {
            Logger.d("SpeeduinoProtocol", "CRC received: 0x${receivedCrc.toString(16)}, calculated: 0x${calculatedCrc.toString(16)}")
        }

        // ⚠️ IMPORTANTE: Ignorar validação se CRC = 0 (Speeduino não envia CRC em alguns comandos)
        if (receivedCrc != 0L && receivedCrc != calculatedCrc) {
            Logger.w("SpeeduinoProtocol", "CRC mismatch, mas continuando (received=0x${receivedCrc.toString(16)}, calculated=0x${calculatedCrc.toString(16)})")
            // throw Exception("CRC error: received=0x${receivedCrc.toString(16)}, calculated=0x${calculatedCrc.toString(16)}")
        }

        return payload
    }

    private fun readExactly(size: Int, context: String, totalTimeoutMs: Int = 5000): ByteArray {
        if (size <= 0) {
            return ByteArray(0)
        }

        val data = ByteArray(size)
        var copied = 0
        val deadline = MonotonicClock.nowMillis() + totalTimeoutMs.toLong()

        while (copied < size) {
            val remaining = size - copied
            val remainingMs = (deadline - MonotonicClock.nowMillis()).coerceAtLeast(1L)
            val chunk = connection.readAvailable(
                maxBytes = remaining,
                timeoutMs = minOf(remainingMs.toInt(), STREAM_READ_SLICE_MS),
            )
            if (chunk.isNotEmpty()) {
                val bytesToCopy = minOf(chunk.size, remaining)
                chunk.copyInto(data, copied, 0, bytesToCopy)
                copied += bytesToCopy
                continue
            }
            if (MonotonicClock.nowMillis() >= deadline) {
                throw Exception("Timeout: expected $size bytes, received $copied ($context)")
            }
        }

        return data
    }

    private fun readLegacyPageData(
        pageLabel: String,
        totalTimeoutMs: Int = 5000,
        idleTimeoutMs: Int = 150,
        idlePollLimit: Int = 3,
    ): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        var sawData = false
        var idlePolls = 0
        val deadline = MonotonicClock.nowMillis() + totalTimeoutMs.toLong()

        while (MonotonicClock.nowMillis() < deadline) {
            val remainingMs = (deadline - MonotonicClock.nowMillis()).coerceAtLeast(1L)
            val pollTimeoutMs = if (sawData) {
                minOf(idleTimeoutMs, remainingMs.toInt())
            } else {
                minOf(maxOf(idleTimeoutMs, 500), remainingMs.toInt())
            }
            val chunk = connection.readAvailable(
                maxBytes = 1024,
                timeoutMs = pollTimeoutMs,
            )
            if (chunk.isNotEmpty()) {
                chunks += chunk
                sawData = true
                idlePolls = 0
                continue
            }

            if (!sawData) {
                continue
            }

            idlePolls++
            if (idlePolls >= idlePollLimit) {
                break
            }
        }

        val pageData = concatChunks(chunks)
        if (pageData.isEmpty()) {
            throw Exception("Timeout: no data received for legacy page $pageLabel")
        }
        return pageData
    }

    private fun looksLikeLegacyAscii(bytes: ByteArray): Boolean {
        return bytes.isNotEmpty() && bytes.all { byte ->
            (byte.toInt() and 0xFF) in 0x20..0x7E
        }
    }

    class IncompleteResponseException(
        private val stage: String,
        private val expected: Int,
        private val received: Int,
        private val cmd: Byte
    ) : Exception(
        "Incomplete modern response ($stage) for cmd=0x${cmd.toInt().and(0xFF).toString(16)}: expected $expected bytes, received $received"
    )

    class ModernResponseReadException(
        private val stage: String,
        private val cmd: Byte,
        private val expected: Int,
        cause: Exception
    ) : Exception(
        "Modern response read failed ($stage) for cmd=0x${cmd.toInt().and(0xFF).toString(16)}: expected $expected bytes; ${cause.message ?: "unknown"}",
        cause
    )

    /**
     * Grava dados em uma página (comando 'M' - MODERN PROTOCOL)
     *
     * ⚠️ BREAKING CHANGE: Speeduino 202501+ EXIGE Modern Protocol (com CRC wrapper)!
     *
     * Formato: M + pageId(2 bytes) + offset(2 LSB) + length(2 LSB) + [dados]
     * Enviado via Modern Protocol: length + payload + CRC32
     *
     * @param pageNum Número da página (1-15)
     * @param offset Offset inicial
     * @param data Dados a gravar
     */
    suspend fun writePage(pageNum: Byte, offset: Int, data: ByteArray) {
        val useLegacyWrite = !isModernEnabled() &&
            !(legacyPageReadUnsupported && connection.supportsModernProtocolFallback())
        if (useLegacyWrite) {
            writePageLegacy(pageNum, offset, data)
            return
        }

        val ecuFamily = sessionEcuFamily
        val useTableEnvelope = ecuFamily == EcuFamily.MS2 || ecuFamily == EcuFamily.MEGASPEED || ecuFamily == EcuFamily.MS3

        // Criar payload (M + pageId(2 bytes) + offset + length + data)
        val extraPayload = ByteArray(6 + data.size)
        extraPayload[0] = 0x00 // Page identifier high byte (always 0)
        extraPayload[1] = pageNum

        // Offset (2 bytes, little-endian)
        extraPayload[2] = (offset and 0xFF).toByte()           // LSB first
        extraPayload[3] = ((offset shr 8) and 0xFF).toByte()   // MSB second

        // Length (2 bytes, little-endian)
        val length = data.size
        extraPayload[4] = (length and 0xFF).toByte()           // LSB first
        extraPayload[5] = ((length shr 8) and 0xFF).toByte()   // MSB second

        // Data bytes
        data.copyInto(extraPayload, 6)

        Logger.d(
            "SpeeduinoProtocol",
            "writePage (${if (useTableEnvelope) "TABLE" else "MODERN"}): pageNum=${formatPageId(pageNum)}, offset=$offset, length=$length, family=${ecuFamily?.name ?: "unknown"}"
        )
        Logger.d("SpeeduinoProtocol", "Payload bytes: ${extraPayload.take(10).joinToString(" ") { "0x${it.toHex02()}" }}... (${extraPayload.size} total)")

        // Enviar via Modern Protocol (com CRC wrapper)
        val response = if (useTableEnvelope) {
            sendModernCommand('w'.code.toByte(), extraPayload)
        } else {
            sendModernCommand('M'.code.toByte(), extraPayload)
        }

        // Verificar resposta (Speeduino 202501+ retorna resposta)
        if (response.isEmpty()) {
            Logger.w("SpeeduinoProtocol", "⚠️  Write page não retornou resposta (compatibilidade legacy)")
        } else if (response[0] != SERIAL_RC_OK) {
            val responseCode = response[0].toInt() and 0xFF
            val errorMsg = when (response[0]) {
                SERIAL_RC_RANGE_ERR -> "RANGE_ERR (0x84): Valor fora do range ou bins não estão em ordem crescente. Verifique RPM/Load bins!"
                SERIAL_RC_CRC_ERR -> "CRC_ERR (0x82): Erro de CRC"
                SERIAL_RC_UKWN_ERR -> "UKWN_ERR (0x83): Comando desconhecido"
                SERIAL_RC_BUSY_ERR -> "BUSY_ERR (0x85): ECU ocupada"
                SERIAL_RC_TIMEOUT -> "TIMEOUT (0x80): ECU não confirmou a escrita; tratando como ACK ausente"
                else -> "response code = 0x${responseCode.toString(16)}"
            }
            if (responseCode == (SERIAL_RC_TIMEOUT.toInt() and 0xFF)) {
                Logger.w(
                    "SpeeduinoProtocol",
                    "⚠️  Page ${formatPageId(pageNum)} respondeu 0x80; seguindo como escrita sem ACK explícito: $errorMsg"
                )
            } else {
                throw Exception("Erro ao gravar página ${formatPageId(pageNum)}: $errorMsg")
            }
        } else {
            val responseCode = response[0].toInt() and 0xFF
            Logger.d("SpeeduinoProtocol", "✅ Page ${formatPageId(pageNum)} gravada com resposta (code: 0x${responseCode.toString(16)})")
        }
    }

    /**
     * Grava uma tabela/newserial page via protocolo MS3 ('w').
     */
    suspend fun writeTable(tableId: Byte, offset: Int, data: ByteArray) {
        writeTable(tableId.toInt() and 0xFF, offset, data, EcuFamily.MS3)
    }

    suspend fun writeTable(tableId: Int, offset: Int, data: ByteArray, family: EcuFamily) {
        requireModernCommandSupport("table write", ignoreSessionLegacyPreferred = family == EcuFamily.RUSEFI)

        val response = if (family == EcuFamily.RUSEFI) {
            val payload = ByteArray(6 + data.size)
            payload[0] = (tableId and 0xFF).toByte()
            payload[1] = ((tableId shr 8) and 0xFF).toByte()
            payload[2] = (offset and 0xFF).toByte()
            payload[3] = ((offset shr 8) and 0xFF).toByte()
            payload[4] = (data.size and 0xFF).toByte()
            payload[5] = ((data.size shr 8) and 0xFF).toByte()
            data.copyInto(payload, 6)
            sendModernCommand('C'.code.toByte(), payload, ignoreSessionLegacyPreferred = true)
        } else {
            val payload = ByteArray(6 + data.size)
            payload[0] = 0x00
            payload[1] = (tableId and 0xFF).toByte()
            payload[2] = ((offset shr 8) and 0xFF).toByte()
            payload[3] = (offset and 0xFF).toByte()
            payload[4] = ((data.size shr 8) and 0xFF).toByte()
            payload[5] = (data.size and 0xFF).toByte()
            data.copyInto(payload, 6)
            sendModernCommand('w'.code.toByte(), payload)
        }

        if (response.isEmpty() || response[0] != SERIAL_RC_OK) {
            val responseCode = response.getOrNull(0)?.toInt()?.and(0xFF)
            if (responseCode == (SERIAL_RC_TIMEOUT.toInt() and 0xFF)) {
                Logger.w(
                    "SpeeduinoProtocol",
                    "⚠️  Tabela ${formatPageId(tableId)} respondeu 0x80; seguindo como escrita sem ACK explícito."
                )
            } else {
                throw Exception(
                    "Erro ao gravar tabela ${formatPageId(tableId)}: " +
                        (responseCode?.let { "response code=0x${it.toString(16)}" } ?: "sem resposta")
                )
            }
        }
    }

    /**
     * Executa burn de uma tabela MS3/newserial ('b').
     */
    suspend fun burnTable(tableId: Byte) {
        burnTable(tableId.toInt() and 0xFF, EcuFamily.MS3)
    }

    suspend fun burnTable(tableId: Int, family: EcuFamily) {
        requireModernCommandSupport("table burn", ignoreSessionLegacyPreferred = family == EcuFamily.RUSEFI)

        val response = if (family == EcuFamily.RUSEFI) {
            val payload = byteArrayOf(
                (tableId and 0xFF).toByte(),
                ((tableId shr 8) and 0xFF).toByte(),
            )
            sendModernCommand('B'.code.toByte(), payload, ignoreSessionLegacyPreferred = true)
        } else {
            val payload = byteArrayOf(0x00, (tableId and 0xFF).toByte())
            sendModernCommand('b'.code.toByte(), payload)
        }
        if (response.isEmpty()) {
            throw Exception("Burn da tabela ${formatPageId(tableId)} sem resposta")
        }

        val responseCode = response[0].toInt() and 0xFF
        if (responseCode != (SERIAL_RC_OK.toInt() and 0xFF) &&
            responseCode != (SERIAL_RC_BURN_OK.toInt() and 0xFF) &&
            responseCode != 0x80) {
            throw Exception(
                "Erro no burn da tabela ${formatPageId(tableId)}: code=0x${responseCode.toString(16)}"
            )
        }
    }

    /**
     * Grava (burn) configurações na EEPROM (comando 'B')
     */
    suspend fun burnConfig() {
        val useLegacyBurn = !isModernEnabled() &&
            !(legacyPageReadUnsupported && connection.supportsModernProtocolFallback())
        if (useLegacyBurn) {
            val pageToBurn = lastLegacyWrittenPage
                ?: throw Exception("Burn legacy requer a página gravada anteriormente")
            sendLegacyCommand(
                'B'.code.toByte(),
                expectResponse = false
            )
            Logger.d("SpeeduinoProtocol", "Legacy burn solicitado para page $pageToBurn")
            return
        }

        val response = sendModernCommand('B'.code.toByte(), byteArrayOf())

        // Speeduino pode retornar diferentes códigos de sucesso:
        // - 0x00 (OK) em versões antigas
        // - 0x04 (BURN_OK) em versões recentes
        // - 0x80 (undocumented) em algumas implementações
        if (response.isEmpty()) {
            throw Exception("Burn: nenhuma resposta recebida")
        }

        val responseCode = response[0].toInt() and 0xFF
        Logger.d("SpeeduinoProtocol", "Burn response code: 0x${responseCode.toString(16)}")

        // Aceitar 0x00, 0x04 ou 0x80 como sucesso
        if (responseCode != (SERIAL_RC_OK.toInt() and 0xFF) &&
            responseCode != (SERIAL_RC_BURN_OK.toInt() and 0xFF) &&
            responseCode != 0x80) {
            val errorMsg = when (response[0]) {
                SERIAL_RC_RANGE_ERR -> "RANGE_ERR (0x84): Valor fora do range ou bins nÇœo estÇœo em ordem crescente."
                SERIAL_RC_CRC_ERR -> "CRC_ERR (0x82): Erro de CRC"
                SERIAL_RC_UKWN_ERR -> "UKWN_ERR (0x83): Comando desconhecido"
                SERIAL_RC_BUSY_ERR -> "BUSY_ERR (0x85): ECU ocupada"
                SERIAL_RC_TIMEOUT -> "TIMEOUT (0x80): ECU nÇœo respondeu"
                else -> "response code = 0x${responseCode.toString(16)}"
            }
            throw Exception("Erro ao fazer burn: $errorMsg")
        }

        Logger.d("SpeeduinoProtocol", "✅ Burn executado com sucesso (code: 0x${responseCode.toString(16)})")
    }

    private fun writePageLegacy(pageNum: Byte, offset: Int, data: ByteArray) {
        val isMs1 = sessionSchemaId == MSEXTRA_HR10_SCHEMA_ID
        val pageSelector = if (isMs1) ms1PageSelector(pageNum) else legacyPageChar(pageNum)
        sendLegacyCommand(CMD_PAGE_SET.code.toByte(), payload = byteArrayOf(pageSelector), expectResponse = false)

        val useExtendedOffset = !isMs1 && (offset + data.size) > 0xFF
        for (i in data.indices) {
            val valueOffset = offset + i
            val payload = if (useExtendedOffset) {
                byteArrayOf(
                    (valueOffset and 0xFF).toByte(),
                    ((valueOffset shr 8) and 0xFF).toByte(),
                    data[i]
                )
            } else {
                byteArrayOf(
                    (valueOffset and 0xFF).toByte(),
                    data[i]
                )
            }
            sendLegacyCommand(CMD_PAGE_WRITE_LEGACY.code.toByte(), payload = payload, expectResponse = false)
        }

        lastLegacyWrittenPage = pageNum
        Logger.d("SpeeduinoProtocol", "writePage (LEGACY): pageNum=${formatPageId(pageNum)}, offset=$offset, length=${data.size}")
    }

    private fun legacyPageChar(pageNum: Byte): Byte {
        val value = pageNum.toInt() and 0xFF
        return when {
            value in 0..9 -> ('0'.code + value).toByte()
            value in 10..15 -> ('A'.code + (value - 10)).toByte()
            else -> '0'.code.toByte()
        }
    }

    /**
     * MSnS-Extra hr_10/MS1 real firmware's 'P' selector is the raw page index minus one
     * (msns-extra.asm TXMODE_C: "sta page; add #$E0" builds the table address straight off the
     * incoming byte, no permutation) - our page IDs are the 1-indexed logical page number.
     */
    private fun ms1PageSelector(pageNum: Byte): Byte {
        return ((pageNum.toInt() and 0xFF) - 1).toByte()
    }

    /**
     * MSnS-Extra hr_10/MS1 real firmware has no offset/length 'p' read command - 'P' selects the
     * page and 'V' always returns the whole page. Callers slice the requested [offset, length)
     * window out of the full page afterwards.
     */
    private fun readPageMs1Legacy(pageNum: Byte, pageLabel: String): ByteArray {
        sendLegacyCommand(CMD_PAGE_SET.code.toByte(), payload = byteArrayOf(ms1PageSelector(pageNum)), expectResponse = false)
        sendLegacyCommand('V'.code.toByte(), expectResponse = false)
        return readLegacyPageData(pageLabel)
    }

    /**
     * Calcula CRC32 checksum
     */
    private fun calculateCRC32(data: ByteArray): Long = Crc32Table.compute(data)

    private fun readU16BE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun readU32BE(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private fun writeU16BE(value: Int): ByteArray = byteArrayOf(
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private fun writeU32BE(value: Long): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private fun concatChunks(chunks: List<ByteArray>): ByteArray {
        val out = ByteArray(chunks.sumOf { it.size })
        var pos = 0
        for (c in chunks) {
            c.copyInto(out, pos)
            pos += c.size
        }
        return out
    }

    class LegacyAsciiModernResponseException(
        private val prefix: ByteArray,
    ) : Exception("Modern response was legacy ASCII: ${prefix.toAsciiPreview()}") {
        companion object {
            private fun ByteArray.toAsciiPreview(): String {
                return map { byte ->
                    val value = byte.toInt() and 0xFF
                    if (value in 0x20..0x7E) value.toChar() else '.'
                }.joinToString("")
            }
        }
    }
}
