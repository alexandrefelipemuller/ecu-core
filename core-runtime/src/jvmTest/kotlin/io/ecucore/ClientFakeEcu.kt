package io.ecucore

import io.ecucore.connection.ISpeeduinoConnection

/**
 * ECU falsa genérica para exercitar o [SpeeduinoClient] por família.
 *
 * Handshake em legacy ('Q'/'S' crus, como BT/USB), leitura/gravação de páginas em memória e
 * comandos em envelope CRC32 (`p`/`M`/`B` Speeduino, `r`/`w`/`b` MS, `R`/`C`/`B`/`O` rusEFI).
 * [onFrame] deixa um teste sobrescrever a resposta de um comando específico.
 */
internal class ClientFakeEcu(
    val kind: Kind,
    private val modernFallback: Boolean = true,
    private val prefersLegacy: Boolean = true,
    private val info: String = "tcp:10.0.0.5:5555",
) : ISpeeduinoConnection {

    enum class Kind(val signature: String, val product: String) {
        SPEEDUINO_2025("speeduino 202501", "speeduino 202501"),
        SPEEDUINO_2023("speeduino 202310", "speeduino 202310"),
        MS3("MS3 Format 0592.13 ", "MS3 1.5.1 release 20170101 (c)JSM/KC"),
        MS2("MS2Extra comms342h2", "MS2/Extra 3.4.3 release  20191126 15:29BST(c)KC/JSM/JB   MS2"),
        MEGASPEED("MS2Extra MegaSpeed", "MS2Extra MegaSpeed 1.0"),
        RUSEFI("rusEFI master.2025.01.01.proteus_f4.abcdef", "rusEFI master.2025.01.01.proteus_f4.abcdef"),
    }

    class Write(val id: Int, val offset: Int, val data: ByteArray)

    private val tables = HashMap<Int, ByteArray>()
    val writes = mutableListOf<Write>()
    val burns = mutableListOf<Int>()
    val commands = mutableListOf<Char>()

    /** Resposta de `A` legacy / `r 0x30` / `O` (tamanho pedido ou este, o que for definido). */
    var liveBytes: ByteArray? = null

    /** Devolve os pedaços a enfileirar para o comando (payload = argumentos sem o byte de comando), ou `null` para o padrão. */
    var onFrame: ((cmd: Char, payload: ByteArray) -> List<ByteArray>?)? = null

    /** Resposta para comandos legacy crus além de Q/S/V (ex.: 'A'). */
    var onLegacy: ((cmd: Char, data: ByteArray) -> ByteArray?)? = null

    var connected = false
    var clearCalls = 0
    private val pending = ArrayDeque<ByteArray>()

    fun mem(id: Int): ByteArray = tables.getOrPut(id) { ByteArray(70000) }

    fun load(id: Int, offset: Int, data: ByteArray) = data.copyInto(mem(id), offset)

    override suspend fun connect() { connected = true }
    override fun disconnect() { connected = false }

    override fun send(data: ByteArray) {
        pending.clear()
        if (ModernEnvelopeFake.isModernFrame(data)) {
            val cmd = data[2].toInt().toChar()
            val payload = data.copyOfRange(3, data.size - 4) // argumentos, sem length/cmd/CRC
            commands += cmd
            val override = onFrame?.invoke(cmd, payload)
            if (override != null) {
                pending += override
                return
            }
            pending += handleFrame(cmd, payload)
            return
        }
        val c = data.firstOrNull()?.toInt()?.toChar() ?: return
        commands += c
        val custom = onLegacy?.invoke(c, data)
        when {
            custom != null -> pending += custom
            c == 'Q' -> pending += (kind.signature + "\u0000").toByteArray(Charsets.US_ASCII)
            c == 'S' -> pending += kind.product.toByteArray(Charsets.US_ASCII)
            c == 'A' -> pending += (liveBytes ?: ByteArray(128))
            c == 'p' && data.size >= 7 -> {
                val page = data[2].toInt() and 0xFF
                val off = u16le(data, 3)
                val len = u16le(data, 5)
                pending += mem(page).copyOfRange(off, off + len)
            }
            c == 'P' || c == 'W' || c == 'B' -> Unit
        }
    }

    private fun handleFrame(cmd: Char, p: ByteArray): List<ByteArray> {
        fun ok(body: ByteArray = ByteArray(0)) = ModernEnvelopeFake.framedResponse(body)
        fun code(c: Int) = listOf(byteArrayOf(0x00, 0x01), byteArrayOf(c.toByte()), ByteArray(4))
        val speeduino = kind == Kind.SPEEDUINO_2025 || kind == Kind.SPEEDUINO_2023
        return when (cmd) {
            'Q' -> ok((kind.signature + "\u0000").toByteArray(Charsets.US_ASCII))
            'S' -> ok(kind.product.toByteArray(Charsets.US_ASCII))
            'f' -> ok(byteArrayOf(2, 0x01, 0x00, 0x01, 0x00))
            'd' -> ok(byteArrayOf(0x11, 0x22, 0x33, 0x44))
            'p' -> {
                val page = p[1].toInt() and 0xFF
                val off = u16le(p, 2)
                val len = u16le(p, 4)
                ok(mem(page).copyOfRange(off, off + len))
            }
            'M' -> {
                val page = p[1].toInt() and 0xFF
                val off = u16le(p, 2)
                val len = u16le(p, 4)
                val body = p.copyOfRange(6, 6 + len)
                body.copyInto(mem(page), off)
                writes += Write(page, off, body)
                ok()
            }
            'r' -> if (speeduino) {
                val len = u16le(p, 4)
                ok(liveBytes ?: ByteArray(len))
            } else {
                val table = p[1].toInt() and 0xFF
                val off = u16be(p, 2)
                val len = u16be(p, 4)
                ok(mem(table).copyOfRange(off, off + len))
            }
            'w' -> {
                val table = p[1].toInt() and 0xFF
                val off = u16be(p, 2)
                val len = u16be(p, 4)
                val body = p.copyOfRange(6, 6 + len)
                body.copyInto(mem(table), off)
                writes += Write(table, off, body)
                ok()
            }
            'b' -> {
                burns += p[1].toInt() and 0xFF
                ok()
            }
            'R' -> {
                val id = u16le(p, 0)
                val off = u16le(p, 2)
                val len = u16le(p, 4)
                ok(mem(id).copyOfRange(off, off + len))
            }
            'C' -> {
                val id = u16le(p, 0)
                val off = u16le(p, 2)
                val len = u16le(p, 4)
                val body = p.copyOfRange(6, 6 + len)
                body.copyInto(mem(id), off)
                writes += Write(id, off, body)
                ok()
            }
            'B' -> {
                burns += if (p.size >= 2) u16le(p, 0) else -1
                code(0x04)
            }
            'O' -> {
                val len = u16le(p, 2)
                ok(liveBytes ?: ByteArray(len))
            }
            else -> code(0x83)
        }
    }

    override fun receive(size: Int): ByteArray = pending.removeFirstOrNull() ?: ByteArray(0)

    override fun isConnected(): Boolean = connected
    override fun getConnectionInfo(): String = info
    override fun supportsModernProtocol(): Boolean = false
    override fun supportsModernProtocolFallback(): Boolean = modernFallback
    override fun prefersLegacyProtocol(): Boolean = prefersLegacy
    override fun legacyFirmwareHandshakeAttempts(): Int = 2
    override fun setOnConnectionStateChanged(callback: (Boolean) -> Unit) = Unit
    override fun setOnError(callback: (String) -> Unit) = Unit
    override fun clearInputBuffer() { clearCalls++ }

    private fun u16le(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun u16be(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
}
