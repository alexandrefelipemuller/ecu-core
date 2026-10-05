package io.ecucore.protocol

import io.ecucore.model.EcuFamily
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** readPage (legacy, MS1, modern, fallback) e readTable. */
class SpeeduinoProtocolPageReadTest {

    private val page = bytes(1, 2, 3, 4)

    private fun legacyConn(reply: ByteArray?) =
        ScriptedConnection(modern = false).apply { onSend = { reply?.let { listOf(it) } } }

    private fun modernConn(reply: ByteArray?) =
        ScriptedConnection().apply { onSend = { reply?.let { listOf(it) } } }

    private fun msgOf(block: suspend () -> Unit) = assertFailsWith<Exception> { runBlocking { block() } }.message!!

    // ---- legacy ----------------------------------------------------------------------------

    @Test
    fun `legacy page read sends p with little endian offset and length`() = runBlocking {
        val c = legacyConn(page)
        assertContentEquals(page, newProtocol(c).readPage(3, 0x0102, 0x0004))
        assertContentEquals(bytes('p'.code, 0, 3, 0x02, 0x01, 0x04, 0x00), c.sent.single())
    }

    @Test
    fun `legacy page read handles short and long responses`() = runBlocking {
        assertTrue(msgOf { newProtocol(legacyConn(bytes(1, 2))).readPage(3, 0, 4) }.contains("short legacy page"))
        assertContentEquals(bytes(1, 2), newProtocol(legacyConn(bytes(1, 2))).readPage(3, 0, 4, allowPartial = true))
        assertContentEquals(page, newProtocol(legacyConn(page + bytes(9, 9))).readPage(3, 0, 4))
    }

    @Test
    fun `legacy page read wraps failures even without a message`() {
        val c = legacyConn(null).apply { receiveErrors += Exception() }
        assertTrue(msgOf { newProtocol(c).readPage(3, 0, 4) }.endsWith("detail=unknown"))
        val c2 = legacyConn(null).apply { receiveErrors += Exception("link down") }
        assertTrue(msgOf { newProtocol(c2).readPage(3, 0, 4) }.endsWith("detail=link down"))
    }

    // ---- MS1 (hr_10) ---------------------------------------------------------------------------

    private val ms1 = "msextra-hr10"

    private fun ms1Conn(fullPage: ByteArray?) = ScriptedConnection(modern = false).apply {
        silenceWhenEmpty = true
        onSend = { p -> if (p[0] == 'V'.code.toByte() && fullPage != null) listOf(fullPage) else null }
    }

    @Test
    fun `ms1 page read selects page then dumps and slices the window`() = runBlocking {
        val full = ByteArray(20) { it.toByte() }
        val c = ms1Conn(full)
        assertContentEquals(bytes(2, 3, 4, 5), newProtocol(c, schema = ms1).readPage(4, 2, 4))
        assertContentEquals(bytes('P'.code, 3), c.sent[0]) // seletor = página - 1
        assertContentEquals(bytes('V'.code), c.sent[1])
    }

    @Test
    fun `ms1 page read rejects dumps that are too short`() {
        val c = ms1Conn(ByteArray(5))
        assertTrue(msgOf { newProtocol(c, schema = ms1).readPage(4, 2, 10) }.contains("too short"))
    }

    @Test
    fun `ms1 page read times out when ecu stays silent`() {
        val c = ms1Conn(null)
        assertTrue(msgOf { newProtocol(c, schema = ms1).readPage(4, 0, 4) }.contains("no data received"))
    }

    // ---- modern -----------------------------------------------------------------------------------

    @Test
    fun `modern page read uses little endian p for speeduino and big endian r for ms families`() = runBlocking {
        for (family in listOf(null, EcuFamily.SPEEDUINO, EcuFamily.RUSEFI, EcuFamily.UNKNOWN)) {
            val c = modernConn(okFrame(page))
            assertContentEquals(page, newProtocol(c, env = true, family = family).readPage(3, 0x0102, 4))
            assertContentEquals(bytes('p'.code, 0, 3, 0x02, 0x01, 0x04, 0x00), c.sent.single().copyOfRange(2, 9))
        }
        for (family in listOf(EcuFamily.MS2, EcuFamily.MEGASPEED, EcuFamily.MS3)) {
            val c = modernConn(okFrame(page))
            assertContentEquals(page, newProtocol(c, env = true, family = family).readPage(3, 0x0102, 4))
            assertContentEquals(bytes('r'.code, 0, 3, 0x01, 0x02, 0x00, 0x04), c.sent.single().copyOfRange(2, 9))
        }
    }

    @Test
    fun `modern page read rejects bad codes and short data`() {
        assertTrue(msgOf { newProtocol(modernConn(codeFrame(0x84)), env = true).readPage(3, 0, 4) }.contains("code=84"))
        assertTrue(msgOf { newProtocol(modernConn(frame(ByteArray(0))), env = true).readPage(3, 0, 4) }.contains("code=null"))
        assertTrue(msgOf { newProtocol(modernConn(okFrame(bytes(1))), env = true).readPage(3, 0, 4) }.contains("short modern"))
    }

    @Test
    fun `modern page read honours transport fallback flag`() = runBlocking {
        // transporte sem leitura modern de config: cai no caminho legacy mesmo pedindo fallback
        val noConfig = ScriptedConnection(modern = true).apply {
            configReads = false
            onSend = { listOf(page) }
        }
        assertContentEquals(page, newProtocol(noConfig, env = null).readPage(3, 0, 4, allowModernTransportFallback = true))
        assertEquals('p', cmdOf(noConfig.sent.single()))

        // ignora legacy-preferred quando permitido
        val c = modernConn(okFrame(page))
        val p = newProtocol(c, legacyPreferred = true)
        assertContentEquals(page, p.readPage(3, 0, 4, allowModernTransportFallback = true))
        assertTrue(isFrame(c.sent.single()))
    }

    // ---- fallback legacy -> modern --------------------------------------------------------------------

    private fun fallbackConn() = ScriptedConnection(modern = false, modernFallback = true).apply {
        onSend = { p -> if (isFrame(p)) listOf(okFrame(if (cmdOf(p) == 'p') page else ByteArray(0))) else null }
    }

    @Test
    fun `failed legacy page read falls back to modern and then stays modern`() = runBlocking {
        val c = fallbackConn()
        val p = newProtocol(c)
        assertContentEquals(page, p.readPage(3, 0, 4))
        assertEquals(listOf('p', 'p'), c.sent.map { cmdOf(it) }) // legacy 'p' falhou, modern 'p' ok
        c.sent.clear()
        // próxima leitura já vai direto em modern; escrita e burn acompanham
        assertContentEquals(page, p.readPage(3, 0, 4))
        p.writePage(3, 0, page)
        p.burnConfig()
        assertTrue(c.sent.all { isFrame(it) })
        assertEquals(listOf('p', 'M', 'B'), c.sent.map { cmdOf(it) })
    }

    @Test
    fun `fallback failure reports the most specific detail`() {
        // modern também falha, com mensagem
        val withMsg = ScriptedConnection(modern = false, modernFallback = true)
        assertTrue(msgOf { newProtocol(withMsg).readPage(3, 0, 4) }.contains("Modern response read failed"))

        // modern falha sem mensagem -> usa a do legacy
        val noFallbackMsg = ScriptedConnection(modern = false, modernFallback = true).apply {
            sendErrors += null
            sendErrors += Exception()
        }
        assertTrue(msgOf { newProtocol(noFallbackMsg).readPage(3, 0, 4) }.contains("short legacy page response"))

        // os dois sem mensagem -> "unknown"
        val bothNull = ScriptedConnection(modern = false, modernFallback = true).apply {
            receiveErrors += Exception()
            sendErrors += null
            sendErrors += Exception()
        }
        assertTrue(msgOf { newProtocol(bothNull).readPage(3, 0, 4) }.endsWith("detail=unknown"))
    }

    @Test
    fun `legacy-first transport never falls back to modern`() {
        val c = ScriptedConnection(modern = false, modernFallback = true, prefersLegacy = true)
        assertTrue(msgOf { newProtocol(c).readPage(3, 0, 4) }.contains("short legacy page"))
        assertTrue(c.sent.none { isFrame(it) })
    }

    // ---- readTable --------------------------------------------------------------------------------------

    @Test
    fun `table read for ms families bypasses the transport gate`() = runBlocking {
        for (family in listOf(EcuFamily.MS2, EcuFamily.MEGASPEED, EcuFamily.MS3)) {
            val c = ScriptedConnection(modern = false, prefersLegacy = true).apply { onSend = { listOf(okFrame(page)) } }
            assertContentEquals(page, newProtocol(c).readTable(0x04, 0x0102, 4, family))
            assertContentEquals(bytes('r'.code, 0, 4, 0x01, 0x02, 0x00, 0x04), c.sent.single().copyOfRange(2, 9))
        }
        val ms3 = ScriptedConnection().apply { onSend = { listOf(okFrame(page)) } }
        assertContentEquals(page, newProtocol(ms3).readTable(0x84.toByte(), 0, 4))
        assertEquals(0x84.toByte(), ms3.sent.single()[4])
    }

    @Test
    fun `table read for rusefi uses R with little endian fields even if legacy preferred`() = runBlocking {
        val c = modernConn(okFrame(page))
        val p = newProtocol(c, legacyPreferred = true)
        assertContentEquals(page, p.readTable(0x0102, 0x0304, 4, EcuFamily.RUSEFI))
        assertContentEquals(bytes('R'.code, 0x02, 0x01, 0x04, 0x03, 0x04, 0x00), c.sent.single().copyOfRange(2, 9))
        // com fallback de transporte
        val c2 = modernConn(okFrame(page))
        assertContentEquals(page, newProtocol(c2).readTable(1, 0, 4, EcuFamily.RUSEFI, allowModernTransportFallback = true))
    }

    @Test
    fun `table read for other families needs modern support`() = runBlocking {
        val none = ScriptedConnection(modern = false)
        assertTrue(msgOf { newProtocol(none).readTable(1, 0, 4, EcuFamily.SPEEDUINO) }.contains("Modern protocol unavailable"))
        val noConfig = ScriptedConnection(modern = false)
        assertTrue(
            msgOf { newProtocol(noConfig).readTable(1, 0, 4, EcuFamily.SPEEDUINO, allowModernTransportFallback = true) }
                .contains("Modern config read unavailable")
        )
        val ok = modernConn(okFrame(page))
        assertContentEquals(page, newProtocol(ok).readTable(1, 0, 4, EcuFamily.SPEEDUINO))
        val okFallback = modernConn(okFrame(page))
        assertContentEquals(
            page,
            newProtocol(okFallback).readTable(1, 0, 4, EcuFamily.SPEEDUINO, allowModernTransportFallback = true)
        )
        // legacy-preferred bloqueia leitura de config modern sem ignore
        val blocked = modernConn(okFrame(page))
        assertTrue(
            msgOf { newProtocol(blocked, legacyPreferred = true).readTable(1, 0, 4, EcuFamily.SPEEDUINO, allowModernTransportFallback = true) }
                .isNotEmpty()
        )
    }

    @Test
    fun `table read validates response and supports partial reads`() = runBlocking {
        assertTrue(msgOf { newProtocol(modernConn(codeFrame(0x85))).readTable(1, 0, 4, EcuFamily.MS3) }.contains("code=85"))
        assertTrue(msgOf { newProtocol(modernConn(frame(ByteArray(0)))).readTable(1, 0, 4, EcuFamily.MS3) }.contains("code=null"))
        assertTrue(msgOf { newProtocol(modernConn(okFrame(bytes(1)))).readTable(1, 0, 4, EcuFamily.MS3) }.contains("short table"))
        assertContentEquals(bytes(1), newProtocol(modernConn(okFrame(bytes(1)))).readTable(1, 0, 4, EcuFamily.MS3, allowPartial = true))
        assertContentEquals(
            bytes(1, 2),
            newProtocol(modernConn(okFrame(bytes(1, 2, 3)))).readTable(1, 0, 2, EcuFamily.MS3, allowPartial = true)
        )
        val noMsg = ScriptedConnection().apply { sendErrors += Exception() }
        assertTrue(msgOf { newProtocol(noMsg).readTable(1, 0, 4, EcuFamily.MS3) }.endsWith("detail=unknown"))
        assertFalse(msgOf { newProtocol(ScriptedConnection()).readTable(1, 0, 4, EcuFamily.MS3) }.isEmpty())
    }
}
