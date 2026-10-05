package io.ecucore.protocol

import io.ecucore.model.EcuFamily
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** writePage / writeTable / burnConfig / burnTable. */
class SpeeduinoProtocolWriteBurnTest {

    private val data = bytes(10, 20, 30)

    private fun conn(reply: ByteArray?, modern: Boolean = true) =
        ScriptedConnection(modern = modern).apply { onSend = { reply?.let { listOf(it) } } }

    private fun msgOf(block: suspend () -> Unit) = assertFailsWith<Exception> { runBlocking { block() } }.message!!

    // ---- writePage modern -------------------------------------------------------------------------

    @Test
    fun `modern write uses M for speeduino and w for ms families`() = runBlocking {
        for (family in listOf(null, EcuFamily.SPEEDUINO)) {
            val c = conn(okFrame())
            newProtocol(c, env = true, family = family).writePage(5, 0x0102, data)
            assertContentEquals(bytes('M'.code, 0, 5, 0x02, 0x01, 3, 0, 10, 20, 30), c.sent.single().copyOfRange(2, 12))
        }
        for (family in listOf(EcuFamily.MS2, EcuFamily.MEGASPEED, EcuFamily.MS3)) {
            val c = conn(okFrame())
            newProtocol(c, env = true, family = family).writePage(5, 0, data)
            assertEquals('w', cmdOf(c.sent.single()))
        }
    }

    @Test
    fun `modern write tolerates empty reply and missing ack`() = runBlocking {
        newProtocol(conn(frame(ByteArray(0))), env = true).writePage(5, 0, data)
        newProtocol(conn(codeFrame(0x80)), env = true).writePage(5, 0, data)
    }

    @Test
    fun `modern write rejection carries code and message`() {
        val expected = mapOf(
            0x84 to "RANGE_ERR",
            0x82 to "CRC_ERR",
            0x83 to "UKWN_ERR",
            0x85 to "BUSY_ERR",
            0x01 to "response code = 0x1",
        )
        for ((code, text) in expected) {
            val e = assertFailsWith<SpeeduinoProtocol.PageWriteRejectedException> {
                runBlocking { newProtocol(conn(codeFrame(code)), env = true).writePage(5, 0, data) }
            }
            assertTrue(e.message!!.contains(text), "code=$code msg=${e.message}")
            assertEquals(code, e.responseCode)
            assertEquals(5, e.pageId)
        }
    }

    // ---- writePage legacy -------------------------------------------------------------------------

    @Test
    fun `legacy write selects page then writes byte by byte`() = runBlocking {
        val c = conn(null, modern = false)
        newProtocol(c).writePage(3, 4, data)
        assertContentEquals(bytes('P'.code, '3'.code), c.sent[0])
        assertContentEquals(bytes('W'.code, 4, 10), c.sent[1])
        assertContentEquals(bytes('W'.code, 6, 30), c.sent[3])
        assertEquals(4, c.sent.size)
    }

    @Test
    fun `legacy write page selector and extended offsets`() = runBlocking {
        for ((page, selector) in listOf(0 to '0', 9 to '9', 10 to 'A', 15 to 'F', 16 to '0')) {
            val c = conn(null, modern = false)
            newProtocol(c).writePage(page.toByte(), 0, data)
            assertEquals(selector.code.toByte(), c.sent[0][1], "page=$page")
        }
        val ext = conn(null, modern = false)
        newProtocol(ext).writePage(3, 0xFE, data) // offset+size > 0xFF => offset de 2 bytes
        assertContentEquals(bytes('W'.code, 0xFE, 0x00, 10), ext.sent[1])
        // MS1: seletor cru página-1 e offset sempre de 1 byte
        val ms1 = conn(null, modern = false)
        newProtocol(ms1, schema = "msextra-hr10").writePage(4, 0xFE, data)
        assertContentEquals(bytes('P'.code, 3), ms1.sent[0])
        assertContentEquals(bytes('W'.code, 0xFE, 10), ms1.sent[1])
    }

    // ---- burnConfig -------------------------------------------------------------------------------

    @Test
    fun `legacy burn requires a previous write`() = runBlocking {
        val c = conn(null, modern = false)
        val p = newProtocol(c)
        assertTrue(msgOf { p.burnConfig() }.contains("requer a página"))
        p.writePage(3, 0, data)
        c.sent.clear()
        p.burnConfig()
        assertContentEquals(bytes('B'.code), c.sent.single())
    }

    @Test
    fun `modern burn accepts ok burn_ok and undocumented ack`() = runBlocking {
        for (code in listOf(0x00, 0x04, 0x80)) {
            val c = conn(codeFrame(code))
            newProtocol(c, env = true).burnConfig()
            assertEquals('B', cmdOf(c.sent.single()))
        }
    }

    @Test
    fun `modern burn rejects empty reply and error codes`() {
        assertTrue(msgOf { newProtocol(conn(frame(ByteArray(0))), env = true).burnConfig() }.contains("nenhuma resposta"))
        for ((code, text) in mapOf(
            0x84 to "RANGE_ERR", 0x82 to "CRC_ERR", 0x83 to "UKWN_ERR", 0x85 to "BUSY_ERR", 0x01 to "response code = 0x1",
        )) {
            assertTrue(msgOf { newProtocol(conn(codeFrame(code)), env = true).burnConfig() }.contains(text), "code=$code")
        }
    }

    // ---- writeTable -------------------------------------------------------------------------------

    @Test
    fun `table write for rusefi uses C little endian`() = runBlocking {
        val c = conn(okFrame())
        newProtocol(c, legacyPreferred = true).writeTable(0x0102, 0x0304, data, EcuFamily.RUSEFI)
        assertContentEquals(bytes('C'.code, 0x02, 0x01, 0x04, 0x03, 3, 0, 10, 20, 30), c.sent.single().copyOfRange(2, 12))
    }

    @Test
    fun `table write for ms families uses w big endian and bypasses the gate`() = runBlocking {
        for (family in listOf(EcuFamily.MS2, EcuFamily.MEGASPEED, EcuFamily.MS3)) {
            val c = ScriptedConnection(modern = false, prefersLegacy = true).apply { onSend = { listOf(okFrame()) } }
            newProtocol(c).writeTable(7, 0x0102, data, family)
            assertContentEquals(bytes('w'.code, 0, 7, 0x01, 0x02, 0, 3, 10, 20, 30), c.sent.single().copyOfRange(2, 12))
        }
        val ms3 = conn(okFrame())
        newProtocol(ms3).writeTable(0x87.toByte(), 0, data)
        assertEquals(0x87.toByte(), ms3.sent.single()[4])
    }

    @Test
    fun `table write for other families needs modern support`() {
        assertTrue(
            msgOf { newProtocol(ScriptedConnection(modern = false)).writeTable(1, 0, data, EcuFamily.SPEEDUINO) }
                .contains("Modern protocol unavailable")
        )
        runBlocking { newProtocol(conn(okFrame())).writeTable(1, 0, data, EcuFamily.SPEEDUINO) }
    }

    @Test
    fun `table write replies`() = runBlocking {
        newProtocol(conn(codeFrame(0x80))).writeTable(1, 0, data, EcuFamily.MS3) // sem ACK é tolerado
        assertTrue(msgOf { newProtocol(conn(codeFrame(0x84))).writeTable(1, 0, data, EcuFamily.MS3) }.contains("code=0x84"))
        assertTrue(msgOf { newProtocol(conn(frame(ByteArray(0)))).writeTable(1, 0, data, EcuFamily.MS3) }.contains("sem resposta"))
    }

    // ---- burnTable --------------------------------------------------------------------------------

    @Test
    fun `table burn payloads`() = runBlocking {
        val rusefi = conn(codeFrame(0x04))
        newProtocol(rusefi, legacyPreferred = true).burnTable(0x0102, EcuFamily.RUSEFI)
        assertContentEquals(bytes('B'.code, 0x02, 0x01), rusefi.sent.single().copyOfRange(2, 5))

        for (family in listOf(EcuFamily.MS2, EcuFamily.MEGASPEED, EcuFamily.MS3)) {
            val c = ScriptedConnection(modern = false, prefersLegacy = true).apply { onSend = { listOf(codeFrame(0x00)) } }
            newProtocol(c).burnTable(9, family)
            assertContentEquals(bytes('b'.code, 0, 9), c.sent.single().copyOfRange(2, 5))
        }
        val ms3 = conn(codeFrame(0x80))
        newProtocol(ms3).burnTable(0x85.toByte())
        assertEquals(0x85.toByte(), ms3.sent.single()[4])
        runBlocking { newProtocol(conn(codeFrame(0x00))).burnTable(1, EcuFamily.SPEEDUINO) }
    }

    @Test
    fun `table burn failures`() {
        assertTrue(
            msgOf { newProtocol(ScriptedConnection(modern = false)).burnTable(1, EcuFamily.SPEEDUINO) }
                .contains("Modern protocol unavailable")
        )
        assertTrue(msgOf { newProtocol(conn(frame(ByteArray(0)))).burnTable(1, EcuFamily.MS3) }.contains("sem resposta"))
        assertTrue(msgOf { newProtocol(conn(codeFrame(0x84))).burnTable(1, EcuFamily.MS3) }.contains("code=0x84"))
    }
}
