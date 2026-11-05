package io.ecucore.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SpeeduinoAntiTheftTest {

    private val original = SpeeduinoAntiTheft.Original(revLimit = 70, protectMinRpm = 10, protectType = 0, revMode = 2, cutType = 1)

    private fun u8(pages: Map<Int, ByteArray>, f: SpeeduinoAntiTheft.Field) = pages.getValue(f.page)[f.offset].toInt() and 0xFF

    /** Páginas travadas: campos nos valores da trava (com bits vizinhos ligados) e originais na página 9. */
    private fun lockedPages(marker: Int = original.markerByte()) = mapOf(
        1 to ByteArray(128).also { it[26] = 0b1111_0111.toByte() }, // hardCutType Full, demais bits ligados
        4 to ByteArray(128).also {
            it[22] = 0
            it[120] = 0
        },
        6 to ByteArray(128).also { it[0] = 0b1111_1111.toByte() }, // engineProtectType Both
        9 to ByteArray(192).also {
            it[68] = original.protectMinRpm.toByte()
            it[110] = marker.toByte()
            it[111] = original.revLimit.toByte()
            it[166] = 0b1111_1101.toByte() // hardRevMode Fixed
        },
    )

    @Test
    fun `trava e a assinatura nos bits altos`() {
        assertTrue(SpeeduinoAntiTheft.isLocked(SpeeduinoAntiTheft.MARKER))
        assertTrue(SpeeduinoAntiTheft.isLocked(original.markerByte()))
        assertFalse(SpeeduinoAntiTheft.isLocked(0))
        assertFalse(SpeeduinoAntiTheft.isLocked(0xFF)) // byte livre nunca gravado
        assertFalse(SpeeduinoAntiTheft.isLocked(0xA7)) // marca da trava antiga pelo desafogador
    }

    @Test
    fun `originais vao e voltam pelo valor guardado no celular e pelos bytes da ECU`() {
        assertEquals(original, SpeeduinoAntiTheft.Original.fromRaw(original.toRaw()))
        assertEquals(original, SpeeduinoAntiTheft.Original.fromStored(original.markerByte(), 70, 10))
        assertEquals(0b1_00_10, original.packModes())
        assertEquals(0xC0 or 0b1_00_10, original.markerByte())
        assertFailsWith<IllegalArgumentException> { SpeeduinoAntiTheft.Original.fromRaw(0) } // hardRevLim 0
        assertFailsWith<IllegalArgumentException> { SpeeduinoAntiTheft.Original.fromRaw(-1) }
    }

    @Test
    fun `bits gravam sem mexer nos vizinhos`() {
        assertEquals(0b1011_1111, SpeeduinoAntiTheft.PROTECT_TYPE.write(0b1111_1111, 2))
        assertEquals(2, SpeeduinoAntiTheft.PROTECT_TYPE.read(0b1011_1111))
        assertEquals(0b1111_0111, SpeeduinoAntiTheft.CUT_TYPE.write(0xFF, 0))
        assertEquals(42, SpeeduinoAntiTheft.REV_LIMIT.write(0xFF, 42))
    }

    @Test
    fun `backup travado volta com os originais e sem a assinatura`() {
        val clean = EcuAntiTheft.sanitizePages(EcuFamily.SPEEDUINO, lockedPages())
        assertEquals(70, u8(clean, SpeeduinoAntiTheft.REV_LIMIT))
        assertEquals(10, u8(clean, SpeeduinoAntiTheft.PROTECT_MIN_RPM))
        assertEquals(0b0011_1111, u8(clean, SpeeduinoAntiTheft.PROTECT_TYPE)) // Off
        assertEquals(0b1111_1110, u8(clean, SpeeduinoAntiTheft.REV_MODE)) // Coolant based
        assertEquals(0b1111_1111, u8(clean, SpeeduinoAntiTheft.CUT_TYPE)) // Rolling
        assertEquals(0, u8(clean, SpeeduinoAntiTheft.MARKER_FIELD))
        assertEquals(0, u8(clean, SpeeduinoAntiTheft.REV_LIMIT_ORIGINAL_FIELD))
        assertEquals(0, u8(clean, SpeeduinoAntiTheft.PROTECT_MIN_RPM_ORIGINAL_FIELD))
    }

    @Test
    fun `sem trava, sem as quatro paginas ou com original invalido fica igual`() {
        val unlocked = lockedPages(marker = 0)
        assertSame(unlocked, SpeeduinoAntiTheft.sanitizePages(unlocked))
        val withoutPage1 = lockedPages() - 1
        assertSame(withoutPage1, SpeeduinoAntiTheft.sanitizePages(withoutPage1))
        val badOriginal = lockedPages().also { it.getValue(9)[111] = 0 }
        assertSame(badOriginal, SpeeduinoAntiTheft.sanitizePages(badOriginal))
        // Outras famílias não mexem no mapa.
        val locked = lockedPages()
        assertSame(locked, EcuAntiTheft.sanitizePages(EcuFamily.MS2, locked))
    }
}
