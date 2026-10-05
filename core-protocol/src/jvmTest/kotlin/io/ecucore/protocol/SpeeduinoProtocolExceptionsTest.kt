package io.ecucore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tipos de erro públicos do protocolo (consumidores fazem catch por eles). */
class SpeeduinoProtocolExceptionsTest {

    @Test
    fun `incomplete response message names stage command and sizes`() {
        val e = SpeeduinoProtocol.IncompleteResponseException("payload", 10, 4, 0x72)
        assertEquals("Incomplete modern response (payload) for cmd=0x72: expected 10 bytes, received 4", e.message)
    }

    @Test
    fun `page write rejected flags only frame level errors`() {
        fun rejected(code: Int) = SpeeduinoProtocol.PageWriteRejectedException(1, code, "x")
        assertTrue(rejected(0x82).isFrameRejected)
        assertTrue(rejected(0x80).isFrameRejected)
        assertFalse(rejected(0x84).isFrameRejected)
        assertEquals(1, rejected(0x84).pageId)
        assertEquals(0x84, rejected(0x84).responseCode)
    }

    @Test
    fun `legacy ascii exception previews printable bytes and masks the rest`() {
        val e = SpeeduinoProtocol.LegacyAsciiModernResponseException(byteArrayOf(0x53, 0x70, 0x01, 0x7F))
        assertEquals("Modern response was legacy ASCII: Sp..", e.message)
    }

    @Test
    fun `read exception keeps cause and handles missing message`() {
        val withMsg = SpeeduinoProtocol.ModernResponseReadException("crc", 0x70, 4, Exception("boom"))
        assertTrue(withMsg.message!!.endsWith("expected 4 bytes; boom"))
        val noMsg = SpeeduinoProtocol.ModernResponseReadException("crc", 0x70, 4, Exception())
        assertTrue(noMsg.message!!.endsWith("; unknown"))
    }

    @Test
    fun `crc mismatch exception carries payload and both checksums`() {
        val e = SpeeduinoProtocol.ModernCrcMismatchException(0x70, 0x1L, 0xFFL, byteArrayOf(1))
        assertTrue(e.message!!.contains("received=0x1, calculated=0xff"))
        assertEquals(1, e.payload.size)
    }
}
