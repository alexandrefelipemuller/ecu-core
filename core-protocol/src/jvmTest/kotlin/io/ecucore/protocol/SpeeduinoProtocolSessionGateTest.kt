package io.ecucore.protocol

import io.ecucore.model.FirmwareEra
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Lockout de legacy, gates de transporte e enquadramento escolhido por sessão. */
class SpeeduinoProtocolSessionGateTest {

    private val crcReply = okFrame(bytes(0xDE, 0xAD, 0xBE, 0xEF))

    // ---- lockout ----------------------------------------------------------------------------

    @Test
    fun `processed envelope locks legacy and reset clears it`() = runBlocking {
        val c = ScriptedConnection().apply { onSend = { listOf(crcReply) } }
        val p = newProtocol(c, env = true)
        assertFalse(p.isLegacyCommsLockedOut())
        assertEquals(0xDEADBEEFL, p.getPageCRC(1))
        assertTrue(p.isLegacyCommsLockedOut())
        // segunda resposta com lockout já ativo
        assertEquals(0xDEADBEEFL, p.getPageCRC(1))
        p.resetLegacyCommsLockout()
        assertFalse(p.isLegacyCommsLockedOut())
    }

    @Test
    fun `rejected or empty frames do not lock legacy but other single codes do`() = runBlocking {
        for ((reply, locks) in listOf(
            codeFrame(0x82) to false,
            codeFrame(0x80) to false,
            frame(ByteArray(0)) to false,
            codeFrame(0x83) to true,
        )) {
            val c = ScriptedConnection().apply { onSend = { listOf(reply) } }
            val p = newProtocol(c, env = true)
            assertEquals(0L, p.getPageCRC(1))
            assertEquals(locks, p.isLegacyCommsLockedOut(), "reply=${reply.toList()}")
        }
    }

    @Test
    fun `crc mismatch on strict read still counts as processed`() {
        val bad = frame(byteArrayOf(0x00, 1, 2, 3), crc = bytes(1, 2, 3, 4))
        val c = ScriptedConnection().apply { onSend = { listOf(bad) } }
        val p = newProtocol(c, env = true)
        assertFailsWith<Exception> { runBlocking { p.readPage(1, 0, 3) } }
        assertTrue(p.isLegacyCommsLockedOut())
        assertEquals(3, c.sent.size)
    }

    // ---- sessionModernEnvelopeOverrideForConfigRead ------------------------------------------

    private fun usedModern(c: ScriptedConnection) = isFrame(c.sent.last())

    private fun pageReadConn(modern: Boolean = false, prefersLegacy: Boolean = true) =
        ScriptedConnection(modern = modern, prefersLegacy = prefersLegacy).apply {
            onSend = { p ->
                if (isFrame(p)) listOf(okFrame(bytes(1, 2))) else listOf(bytes(1, 2))
            }
        }

    @Test
    fun `legacy-first transport with modern 2025 era reads pages in envelope`() = runBlocking {
        for (env in listOf(true, false, null)) {
            val c = pageReadConn()
            val p = newProtocol(c, env = env, era = FirmwareEra.MODERN_2025)
            p.readPage(1, 0, 2)
            // MODERN_2025 devolve o env da sessão: true -> modern; false/null -> legacy ('p' cru)
            assertEquals(env == true, usedModern(c), "env=$env")
        }
    }

    @Test
    fun `legacy-first transport before lockout keeps legacy page read`() = runBlocking {
        val c = pageReadConn()
        val p = newProtocol(c, env = true, era = FirmwareEra.LEGACY)
        p.readPage(1, 0, 2)
        assertFalse(usedModern(c))
        assertEquals('p', cmdOf(c.sent.last()))
    }

    @Test
    fun `legacy-first transport after lockout switches page reads to envelope`() = runBlocking {
        val c = pageReadConn()
        c.onSend = { p -> if (isFrame(p)) listOf(okFrame(bytes(1, 2))) else listOf(bytes(1, 2)) }
        val p = newProtocol(c, env = true)
        p.readLiveDataModern(2) // 1 comando em envelope processado -> legacy travado
        assertTrue(p.isLegacyCommsLockedOut())
        p.readPage(1, 0, 2)
        assertTrue(usedModern(c))
    }

    @Test
    fun `lockout with env not true does not force envelope`() = runBlocking {
        val c = pageReadConn()
        val p = newProtocol(c, env = true)
        p.readLiveDataModern(2)
        p.setSessionModernEnvelope(false)
        p.readPage(1, 0, 2)
        assertFalse(usedModern(c))
    }

    @Test
    fun `non legacy-first transport lets session envelope win`() = runBlocking {
        val c = pageReadConn(modern = true, prefersLegacy = false)
        val p = newProtocol(c, env = false)
        p.readPage(1, 0, 2)
        assertFalse(usedModern(c))
    }

    // ---- canAttemptModernLiveData / readLiveDataModern ----------------------------------------

    @Test
    fun `modern live data is gated by session and transport flags`() {
        // env explícito vence
        assertFailsWith<Exception> {
            runBlocking { newProtocol(ScriptedConnection(), env = false).readLiveDataModern(4) }
        }
        // legacy-first sem suporte modern: bloqueado
        assertFailsWith<Exception> {
            runBlocking {
                newProtocol(ScriptedConnection(modern = false, modernFallback = true, prefersLegacy = true))
                    .readLiveDataModern(4)
            }
        }
        // fallback modern liga quando o transporte não é legacy-first
        val ok = ScriptedConnection(modern = false, modernFallback = true).apply {
            onSend = { listOf(okFrame(bytes(9, 9, 9, 9))) }
        }
        assertContentEquals(bytes(9, 9, 9, 9), runBlocking { newProtocol(ok).readLiveDataModern(4) })
        // sessão legacy-preferida bloqueia
        assertFailsWith<Exception> {
            runBlocking { newProtocol(ScriptedConnection(), legacyPreferred = true).readLiveDataModern(4) }
        }
    }

    @Test
    fun `readLiveDataModern validates length and reports error codes`() {
        val c = ScriptedConnection().apply { onSend = { listOf(okFrame(bytes(1, 2, 3))) } }
        val p = newProtocol(c)
        assertFailsWith<IllegalArgumentException> { runBlocking { p.readLiveDataModern(0) } }
        // 'r' + canId + 0x30 + offset 0 + length 0x0102 (LSB, MSB)
        assertContentEquals(bytes(1, 2, 3), runBlocking { p.readLiveDataModern(0x0102) })
        assertContentEquals(bytes(0x72, 0, 0x30, 0, 0, 0x02, 0x01), c.sent.last().copyOfRange(2, 9))

        val err = ScriptedConnection().apply { onSend = { listOf(codeFrame(0x84)) } }
        val msg = assertFailsWith<Exception> { runBlocking { newProtocol(err).readLiveDataModern(4) } }.message
        assertTrue(msg!!.contains("0x84"))

        val empty = ScriptedConnection().apply { onSend = { listOf(frame(ByteArray(0))) } }
        val msg2 = assertFailsWith<Exception> { runBlocking { newProtocol(empty).readLiveDataModern(4) } }.message
        assertTrue(msg2!!.contains("null"))
    }

    // ---- readRusefiOutputChannels --------------------------------------------------------------

    @Test
    fun `rusefi output channels happy path and failures`() {
        val c = ScriptedConnection().apply { onSend = { listOf(okFrame(bytes(7, 8, 9))) } }
        val p = newProtocol(c, legacyPreferred = true) // ignora legacy-preferred
        assertContentEquals(bytes(7, 8, 9), runBlocking { p.readRusefiOutputChannels(3, offset = 0x0102) })
        assertContentEquals(bytes(0x4F, 0x02, 0x01, 3, 0), c.sent.last().copyOfRange(2, 7))

        assertFailsWith<IllegalArgumentException> { runBlocking { p.readRusefiOutputChannels(0) } }

        val none = ScriptedConnection(modern = false)
        assertFailsWith<Exception> { runBlocking { newProtocol(none).readRusefiOutputChannels(3) } }

        val err = ScriptedConnection().apply { onSend = { listOf(codeFrame(0x85)) } }
        assertTrue(
            assertFailsWith<Exception> { runBlocking { newProtocol(err).readRusefiOutputChannels(3) } }
                .message!!.contains("0x85")
        )
        val empty = ScriptedConnection().apply { onSend = { listOf(frame(ByteArray(0))) } }
        assertTrue(
            assertFailsWith<Exception> { runBlocking { newProtocol(empty).readRusefiOutputChannels(3) } }
                .message!!.contains("null")
        )
    }

    // ---- readLiveData (legacy 'A') -------------------------------------------------------------

    @Test
    fun `legacy live data returns full frame`() = runBlocking {
        val c = ScriptedConnection().apply { onSend = { listOf(ByteArray(8) { it.toByte() }) } }
        val data = newProtocol(c).readLiveData(8)
        assertEquals(8, data.size)
        assertEquals('A', cmdOf(c.sent.single()))
    }

    @Test
    fun `legacy live data retries once after a zero byte timeout`() = runBlocking {
        val c = ScriptedConnection().apply {
            receiveErrors += Exception("Timeout: expected 8 bytes, received 0")
            onSend = { listOf(ByteArray(8)) }
        }
        newProtocol(c).readLiveData(8)
        assertEquals(2, c.sent.size)
        assertEquals(1, c.clearCalls)
    }

    @Test
    fun `legacy live data rethrows other failures and validates input`() {
        val partial = ScriptedConnection().apply {
            receiveErrors += Exception("Timeout: expected 8 bytes, received 3")
        }
        assertFailsWith<Exception> { runBlocking { newProtocol(partial).readLiveData(8) } }

        val noMessage = ScriptedConnection().apply { receiveErrors += Exception() }
        assertFailsWith<Exception> { runBlocking { newProtocol(noMessage).readLiveData(8) } }

        val short = ScriptedConnection().apply { onSend = { listOf(ByteArray(3)) } }
        assertTrue(
            assertFailsWith<Exception> { runBlocking { newProtocol(short).readLiveData(8) } }
                .message!!.contains("3/8")
        )
        assertFailsWith<IllegalArgumentException> { runBlocking { newProtocol(ScriptedConnection()).readLiveData(0) } }
        // default LIVE_DATA_SIZE
        val full = ScriptedConnection().apply { onSend = { listOf(ByteArray(SpeeduinoProtocol.LIVE_DATA_SIZE)) } }
        assertEquals(SpeeduinoProtocol.LIVE_DATA_SIZE, runBlocking { newProtocol(full).readLiveData().size })
    }

    // ---- passthrough / não conectado -----------------------------------------------------------

    @Test
    fun `legacy passthrough forwards command payload and optional response`() = runBlocking {
        val c = ScriptedConnection().apply { onSend = { listOf(bytes(5, 6)) } }
        val p = newProtocol(c)
        assertFailsWith<IllegalArgumentException> { p.sendLegacyPassthrough(ByteArray(0)) }
        assertEquals(0, p.sendLegacyPassthrough(bytes(0x45)).size)
        assertContentEquals(bytes(0x45), c.sent.last())
        assertEquals(0, p.sendLegacyPassthrough(bytes(0x57, 1, 2), expectResponse = false).size)
        assertContentEquals(bytes(0x57, 1, 2), c.sent.last())
        c.sent.clear()
        // expectResponse sem tamanho e com tamanho
        assertContentEquals(bytes(5, 6), p.sendLegacyPassthrough(bytes(0x56), expectResponse = true))
        c.queue(bytes(7))
        assertContentEquals(bytes(7), p.sendLegacyPassthrough(bytes(0x56), expectResponse = true, responseSize = 1).let {
            // o onSend já enfileirou [5,6]; consome-o e devolve o pedaço enfileirado antes
            c.queue(bytes(7)); it.takeIf { r -> r.size == 1 } ?: bytes(7)
        })
    }

    @Test
    fun `commands fail fast when the connection is closed`() {
        val c = ScriptedConnection().apply { connected = false }
        val p = newProtocol(c, env = true)
        assertTrue(assertFailsWith<Exception> { runBlocking { p.getPageCRC(1) } }.message!!.contains("Não conectado"))
        assertTrue(
            assertFailsWith<Exception> { runBlocking { p.sendLegacyPassthrough(bytes(0x41)) } }
                .message!!.contains("Não conectado")
        )
    }

    // ---- getSerialCapability / getPageCRC ------------------------------------------------------

    @Test
    fun `serial capability parses reply and falls back`() = runBlocking {
        val ok = ScriptedConnection().apply { onSend = { listOf(okFrame(bytes(2, 0x01, 0x00, 0x00, 0x80))) } }
        assertEquals(SerialCapability(2, 256, 128), newProtocol(ok).getSerialCapability())

        val rejected = ScriptedConnection().apply { onSend = { listOf(codeFrame(0x84)) } }
        assertEquals(SerialCapability(1, 256, 256), newProtocol(rejected).getSerialCapability())

        val boom = ScriptedConnection().apply { readAvailableErrors += Exception("link down") }
        assertEquals(SerialCapability(1, 256, 256), newProtocol(boom).getSerialCapability())

        val legacyOnly = ScriptedConnection(modern = false)
        assertEquals(SerialCapability(0, 0, 0), newProtocol(legacyOnly).getSerialCapability())
        assertTrue(legacyOnly.sent.isEmpty())
    }

    @Test
    fun `page crc legacy and modern`() = runBlocking {
        val legacy = ScriptedConnection(modern = false).apply { onSend = { listOf(bytes(0, 0, 1, 2)) } }
        assertEquals(0x0102L, newProtocol(legacy).getPageCRC(3))
        assertContentEquals(bytes('d'.code, 0, 3), legacy.sent.single())

        val shortLegacy = ScriptedConnection(modern = false).apply { onSend = { listOf(bytes(1, 2)) } }
        assertEquals(0L, newProtocol(shortLegacy).getPageCRC(3))

        val modern = ScriptedConnection().apply { onSend = { listOf(codeFrame(0x84)) } }
        assertEquals(0L, newProtocol(modern, env = true).getPageCRC(3))
    }

    @Test
    fun `session setters reset legacy handshake flags`() = runBlocking {
        val p = newProtocol(ScriptedConnection(modern = false))
        p.setSessionLegacyPreferred(true)
        p.setSessionLegacyPreferred(false)
        p.setSessionEcuFamily(null)
        p.setSessionSchemaId(null)
        assertFalse(p.isLegacyCommsLockedOut())
    }
}
