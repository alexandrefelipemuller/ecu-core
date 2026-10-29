package io.ecucore.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SpeeduinoAntiTheftTest {

    private fun pages(flood: Int, marker: Int, stored: Int) = mapOf(
        SpeeduinoAntiTheft.FLOOD_PAGE to ByteArray(128).also { it[SpeeduinoAntiTheft.FLOOD_OFFSET] = flood.toByte() },
        SpeeduinoAntiTheft.MARKER_PAGE to ByteArray(192).also {
            it[SpeeduinoAntiTheft.MARKER_OFFSET] = marker.toByte()
            it[SpeeduinoAntiTheft.ORIGINAL_OFFSET] = stored.toByte()
        },
    )

    @Test
    fun `trava exige desafogador em 0 e a marca`() {
        assertTrue(SpeeduinoAntiTheft.isLocked(0, SpeeduinoAntiTheft.MARKER))
        assertFalse(SpeeduinoAntiTheft.isLocked(0, 0))
        assertFalse(SpeeduinoAntiTheft.isLocked(180, SpeeduinoAntiTheft.MARKER))
    }

    @Test
    fun `original invalido vira 100 por cento`() {
        assertEquals(180, SpeeduinoAntiTheft.originalFrom(180))
        assertEquals(SpeeduinoAntiTheft.DEFAULT_ORIGINAL_RAW, SpeeduinoAntiTheft.originalFrom(0))
    }

    @Test
    fun `backup travado volta com o original e sem a marca`() {
        val clean = EcuAntiTheft.sanitizePages(EcuFamily.SPEEDUINO, pages(0, SpeeduinoAntiTheft.MARKER, 180))
        assertEquals(180, clean.getValue(SpeeduinoAntiTheft.FLOOD_PAGE)[SpeeduinoAntiTheft.FLOOD_OFFSET].toInt() and 0xFF)
        assertEquals(0, clean.getValue(SpeeduinoAntiTheft.MARKER_PAGE)[SpeeduinoAntiTheft.MARKER_OFFSET].toInt())
        assertEquals(0, clean.getValue(SpeeduinoAntiTheft.MARKER_PAGE)[SpeeduinoAntiTheft.ORIGINAL_OFFSET].toInt())
    }

    @Test
    fun `sem trava ou sem as duas paginas fica igual`() {
        val unlocked = pages(180, 0, 0)
        assertSame(unlocked, SpeeduinoAntiTheft.sanitizePages(unlocked))
        val onlyFlood = mapOf(SpeeduinoAntiTheft.FLOOD_PAGE to ByteArray(128))
        assertSame(onlyFlood, SpeeduinoAntiTheft.sanitizePages(onlyFlood))
        // Outras famílias não mexem no mapa.
        val locked = pages(0, SpeeduinoAntiTheft.MARKER, 180)
        assertSame(locked, EcuAntiTheft.sanitizePages(EcuFamily.MS2, locked))
    }
}
