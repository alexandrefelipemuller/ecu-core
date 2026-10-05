package io.ecucore.protocol

import io.ecucore.connection.ISpeeduinoConnection
import io.ecucore.model.EcuFamily
import io.ecucore.model.FirmwareEra
import io.ecucore.shared.Crc32Table

/**
 * Conexão scriptável para cobrir o [SpeeduinoProtocol] sem hardware.
 *
 * Cada `send()` é gravado em [sent] e passa por [onSend], que devolve os pedaços de resposta a
 * enfileirar (ou `null` = ECU muda). `receive()` entrega um pedaço inteiro por chamada;
 * `readAvailable()` respeita `maxBytes` (devolve o resto na próxima leitura).
 */
internal class ScriptedConnection(
    var modern: Boolean = true,
    var modernFallback: Boolean = false,
    var prefersLegacy: Boolean = false,
    var info: String = "fake",
) : ISpeeduinoConnection {
    val sent = mutableListOf<ByteArray>()
    private val inbox = ArrayDeque<ByteArray>()

    var connected = true
    var configReads: Boolean? = null
    var onSend: (ByteArray) -> List<ByteArray>? = { null }
    var clearCalls = 0

    /** Erros entregues, um por chamada, antes de qualquer dado. */
    val receiveErrors = ArrayDeque<Throwable>()
    val readAvailableErrors = ArrayDeque<Throwable>()
    var sendError: Throwable? = null

    /** `true` = inbox vazio devolve `ByteArray(0)` (silêncio); `false` = lança erro rápido. */
    var silenceWhenEmpty = false

    fun queue(vararg chunks: ByteArray) = chunks.forEach { inbox.addLast(it) }

    override suspend fun connect() { connected = true }
    override fun disconnect() { connected = false }

    override fun send(data: ByteArray) {
        sendError?.let { throw it }
        sent += data.copyOf()
        onSend(data)?.forEach { inbox.addLast(it) }
    }

    override fun receive(size: Int): ByteArray {
        receiveErrors.removeFirstOrNull()?.let { throw it }
        return inbox.removeFirstOrNull() ?: ByteArray(0)
    }

    override fun readAvailable(maxBytes: Int, timeoutMs: Int?): ByteArray {
        readAvailableErrors.removeFirstOrNull()?.let { throw it }
        val chunk = inbox.removeFirstOrNull()
            ?: if (silenceWhenEmpty) return ByteArray(0) else throw RuntimeException("fake: sem dados")
        if (maxBytes > 0 && chunk.size > maxBytes) {
            inbox.addFirst(chunk.copyOfRange(maxBytes, chunk.size))
            return chunk.copyOfRange(0, maxBytes)
        }
        return chunk
    }

    override fun isConnected(): Boolean = connected
    override fun getConnectionInfo(): String = info
    override fun supportsModernProtocol(): Boolean = modern
    override fun supportsModernProtocolFallback(): Boolean = modernFallback
    override fun supportsModernConfigReads(): Boolean = configReads ?: (modern || modernFallback)
    override fun prefersLegacyProtocol(): Boolean = prefersLegacy
    override fun setOnConnectionStateChanged(callback: (Boolean) -> Unit) = Unit
    override fun setOnError(callback: (String) -> Unit) = Unit

    override fun clearInputBuffer() {
        clearCalls++
        inbox.clear()
    }
}

internal fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

internal fun crcOf(payload: ByteArray): ByteArray {
    val c = Crc32Table.compute(payload)
    return byteArrayOf((c ushr 24).toByte(), (c ushr 16).toByte(), (c ushr 8).toByte(), c.toByte())
}

/** Envelope `[len 2B BE] + payload + [CRC32 4B BE]` (CRC correto, salvo se [crc] for informado). */
internal fun frame(payload: ByteArray, crc: ByteArray = crcOf(payload)): ByteArray =
    u16(payload.size) + payload + crc

internal fun okFrame(data: ByteArray = ByteArray(0)) = frame(byteArrayOf(0x00) + data)
internal fun codeFrame(code: Int) = frame(byteArrayOf(code.toByte()))
internal fun ascii(s: String) = s.encodeToByteArray()
internal fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

internal fun isFrame(p: ByteArray): Boolean =
    p.size >= 7 && (((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)) == p.size - 6

/** Comando do pacote: char do envelope modern (payload[0]) ou o byte cru legacy. */
internal fun cmdOf(p: ByteArray): Char =
    if (isFrame(p)) p[2].toInt().toChar() else p[0].toInt().toChar()

internal fun newProtocol(
    conn: ScriptedConnection,
    env: Boolean? = null,
    era: FirmwareEra? = null,
    family: EcuFamily? = null,
    schema: String? = null,
    legacyPreferred: Boolean = false,
): SpeeduinoProtocol = SpeeduinoProtocol(conn).apply {
    setSessionModernEnvelope(env)
    setSessionFirmwareEra(era)
    setSessionEcuFamily(family)
    setSessionSchemaId(schema)
    if (legacyPreferred) setSessionLegacyPreferred(true)
}
