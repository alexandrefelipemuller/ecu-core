package io.ecucore.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MegaSpeedAntiTheftTest {

    private val original = MegaSpeedAntiTheft.Original(revLimit = 7200, cltBased = 1, fuelProg = 0, fuelCut = 0)

    @Test
    fun `original guarda limite e modos e volta igual`() {
        for (o in listOf(
            original,
            MegaSpeedAntiTheft.Original(1, 0, 0, 0),
            MegaSpeedAntiTheft.Original(15000, 1, 1, 1),
        )) {
            assertEquals(o, MegaSpeedAntiTheft.Original.fromRaw(o.toRaw()))
            assertEquals(o, MegaSpeedAntiTheft.Original.fromStored(o.markerByte(), o.revLimit))
            assertTrue(MegaSpeedAntiTheft.isLocked(o.markerByte()))
        }
    }

    @Test
    fun `assinatura nao bate com byte livre nunca gravado`() {
        assertFalse(MegaSpeedAntiTheft.isLocked(0x00))
        assertFalse(MegaSpeedAntiTheft.isLocked(0xFF))
        assertTrue(MegaSpeedAntiTheft.isLocked(0xC7))
    }

    @Test
    fun `original invalido nao trava`() {
        assertFailsWith<IllegalArgumentException> { MegaSpeedAntiTheft.Original(0, 0, 0, 0) }
        assertFailsWith<IllegalArgumentException> { MegaSpeedAntiTheft.Original(15001, 0, 0, 0) }
        assertFailsWith<IllegalArgumentException> { MegaSpeedAntiTheft.Original(7000, 2, 0, 0) }
        assertFailsWith<IllegalArgumentException> { MegaSpeedAntiTheft.Original.fromRaw(-1) }
    }

    @Test
    fun `bytes S16 big endian`() {
        assertContentEquals(byteArrayOf(0x1C, 0x20), MegaSpeedAntiTheft.encode(7200))
        assertEquals(7200, MegaSpeedAntiTheft.decode(MegaSpeedAntiTheft.encode(7200)))
    }

    private fun lockedPage(): ByteArray {
        val page = ByteArray(1024) { (it % 7).toByte() }
        MegaSpeedAntiTheft.encode(MegaSpeedAntiTheft.LOCKED_REV_LIMIT).copyInto(page, MegaSpeedAntiTheft.REV_LIMIT.offset)
        // opções travadas: fuelcut (bit 5) ligado; os demais bits do byte são do usuário (bit 0 e 2 ligados)
        page[MegaSpeedAntiTheft.OPTIONS_OFFSET] = 0b0010_0101
        page[MegaSpeedAntiTheft.MARKER_OFFSET] = original.markerByte().toByte()
        MegaSpeedAntiTheft.encode(original.revLimit).copyInto(page, MegaSpeedAntiTheft.REV_LIMIT_ORIGINAL_OFFSET)
        return page
    }

    @Test
    fun `backup travado volta com os originais e o resto intacto`() {
        val page = lockedPage()

        val clean = MegaSpeedAntiTheft.sanitizePage(MegaSpeedAntiTheft.TABLE_ID, page)

        assertEquals(7200, MegaSpeedAntiTheft.decode(clean, MegaSpeedAntiTheft.REV_LIMIT.offset))
        // CLTbased (bit 3) volta ligado, fuelcut (bit 5) volta desligado; bits 0 e 2 do usuário ficam.
        assertEquals(0b0000_1101, clean[MegaSpeedAntiTheft.OPTIONS_OFFSET].toInt() and 0xFF)
        for (i in MegaSpeedAntiTheft.MARKER_OFFSET until MegaSpeedAntiTheft.MARKER_OFFSET + MegaSpeedAntiTheft.STASH_LENGTH) {
            assertEquals(0, clean[i].toInt())
        }
        assertFalse(clean === page, "não altera a página de entrada")
        val changed = setOf(MegaSpeedAntiTheft.REV_LIMIT.offset, MegaSpeedAntiTheft.REV_LIMIT.offset + 1, MegaSpeedAntiTheft.OPTIONS_OFFSET) +
            (MegaSpeedAntiTheft.MARKER_OFFSET until MegaSpeedAntiTheft.MARKER_OFFSET + MegaSpeedAntiTheft.STASH_LENGTH)
        for (i in page.indices) if (i !in changed) assertEquals(page[i], clean[i])
    }

    @Test
    fun `pagina sem trava ou de outra tabela fica igual`() {
        val page = ByteArray(1024)
        assertSame(page, MegaSpeedAntiTheft.sanitizePage(MegaSpeedAntiTheft.TABLE_ID, page))

        val other = lockedPage()
        assertSame(other, MegaSpeedAntiTheft.sanitizePage(0x05, other))
    }

    @Test
    fun `trava com original invalido fica como esta`() {
        val page = lockedPage()
        MegaSpeedAntiTheft.encode(0).copyInto(page, MegaSpeedAntiTheft.REV_LIMIT_ORIGINAL_OFFSET)
        assertSame(page, MegaSpeedAntiTheft.sanitizePage(MegaSpeedAntiTheft.TABLE_ID, page))
    }
}
