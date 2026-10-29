package io.ecucore.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MegaSpeedAntiTheftTest {

    @Test
    fun `trava guarda o original e volta igual nos extremos`() {
        assertEquals(-1, MegaSpeedAntiTheft.encodeLocked(0))
        assertEquals(-1001, MegaSpeedAntiTheft.encodeLocked(1000))
        for (original in listOf(0, 1, 710, 1000)) {
            val locked = MegaSpeedAntiTheft.encodeLocked(original)
            assertTrue(MegaSpeedAntiTheft.isLocked(locked))
            assertEquals(original, MegaSpeedAntiTheft.originalFrom(locked))
        }
    }

    @Test
    fun `original fora de 0 a 100 por cento nao trava`() {
        assertFailsWith<IllegalArgumentException> { MegaSpeedAntiTheft.encodeLocked(-1) }
        assertFailsWith<IllegalArgumentException> { MegaSpeedAntiTheft.encodeLocked(1001) }
        assertFailsWith<IllegalArgumentException> { MegaSpeedAntiTheft.originalFrom(0) }
    }

    @Test
    fun `trava que nao veio do app destrava em 100 por cento`() {
        assertEquals(1000, MegaSpeedAntiTheft.originalFrom(-20000))
    }

    @Test
    fun `bytes S16 big endian com sinal`() {
        assertContentEquals(byteArrayOf(0xFC.toByte(), 0x39), MegaSpeedAntiTheft.encode(-967))
        assertEquals(-967, MegaSpeedAntiTheft.decode(byteArrayOf(0xFC.toByte(), 0x39)))
        assertEquals(700, MegaSpeedAntiTheft.decode(MegaSpeedAntiTheft.encode(700)))
    }

    @Test
    fun `backup travado volta com o valor original e o resto intacto`() {
        val page = ByteArray(1024) { it.toByte() }
        MegaSpeedAntiTheft.encode(MegaSpeedAntiTheft.encodeLocked(700)).copyInto(page, MegaSpeedAntiTheft.OFFSET)

        val clean = MegaSpeedAntiTheft.sanitizePage(MegaSpeedAntiTheft.TABLE_ID, page)

        assertEquals(700, MegaSpeedAntiTheft.decode(clean, MegaSpeedAntiTheft.OFFSET))
        assertFalse(clean === page, "não altera a página de entrada")
        for (i in page.indices) if (i !in MegaSpeedAntiTheft.OFFSET until MegaSpeedAntiTheft.OFFSET + 2) {
            assertEquals(page[i], clean[i])
        }
    }

    @Test
    fun `pagina sem trava ou de outra tabela fica igual`() {
        val page = ByteArray(1024)
        MegaSpeedAntiTheft.encode(700).copyInto(page, MegaSpeedAntiTheft.OFFSET)
        assertSame(page, MegaSpeedAntiTheft.sanitizePage(MegaSpeedAntiTheft.TABLE_ID, page))

        val other = ByteArray(1024) { 0xFF.toByte() }
        assertSame(other, MegaSpeedAntiTheft.sanitizePage(0x05, other))
    }
}
