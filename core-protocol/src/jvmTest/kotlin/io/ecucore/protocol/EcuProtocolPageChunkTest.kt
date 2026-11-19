package io.ecucore.protocol

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Leitura de página maior que o buffer do firmware AVR é dividida em blocos (ex.: VE = 288 bytes). */
class EcuProtocolPageChunkTest {

    /** Firmware simulado: responde a cada 'p' com os bytes certos do offset/length pedidos. */
    private fun firmwareConn(pageData: ByteArray) = ScriptedConnection(modern = false).apply {
        onSend = { p ->
            val offset = (p[3].toInt() and 0xFF) or ((p[4].toInt() and 0xFF) shl 8)
            val length = (p[5].toInt() and 0xFF) or ((p[6].toInt() and 0xFF) shl 8)
            listOf(pageData.copyOfRange(offset, offset + length))
        }
    }

    @Test
    fun `page larger than firmware buffer is read in blocks of 251 and reassembled`() = runBlocking {
        val page = ByteArray(288) { (it * 7 + 3).toByte() }
        val c = firmwareConn(page)
        assertContentEquals(page, newProtocol(c).readPage(3, 0, 288))

        // dois pedidos: 251 bytes em offset 0 e 37 bytes em offset 251
        assertEquals(2, c.sent.size)
        assertContentEquals(bytes('p'.code, 0, 3, 0x00, 0x00, 0xFB, 0x00), c.sent[0])
        assertContentEquals(bytes('p'.code, 0, 3, 0xFB, 0x00, 0x25, 0x00), c.sent[1])
    }

    @Test
    fun `page within the block limit is still a single request`() = runBlocking {
        val page = ByteArray(251) { it.toByte() }
        val c = firmwareConn(page)
        assertContentEquals(page, newProtocol(c).readPage(3, 0, 251))
        assertEquals(1, c.sent.size)
    }
}
