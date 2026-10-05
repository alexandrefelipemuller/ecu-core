package io.ecucore.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransportCriticalPageBytesTest {

    private val speeduino = EcuFamily.SPEEDUINO

    @Test
    fun `only the speeduino secondary serial byte is protected`() {
        assertEquals(setOf(0), TransportCriticalPageBytes.protectedOffsets(speeduino, 9))
        assertEquals(emptySet(), TransportCriticalPageBytes.protectedOffsets(speeduino, 8))
        assertEquals(emptySet(), TransportCriticalPageBytes.protectedOffsets(EcuFamily.MS3, 9))
        assertEquals(emptySet(), TransportCriticalPageBytes.protectedOffsets(EcuFamily.RUSEFI, 9))
    }

    @Test
    fun `writable ranges skip the protected byte`() {
        assertEquals(listOf(0 until 10), TransportCriticalPageBytes.writableRanges(speeduino, 2, 10))
        assertEquals(listOf(1 until 10), TransportCriticalPageBytes.writableRanges(speeduino, 9, 10))
        assertEquals(emptyList(), TransportCriticalPageBytes.writableRanges(speeduino, 9, 1))
        assertEquals(emptyList(), TransportCriticalPageBytes.writableRanges(speeduino, 9, 0))
    }

    @Test
    fun `writable ranges split around a protected byte in the middle`() {
        // simula um offset protegido no meio usando a mesma lógica: página 9 só protege o 0
        val ranges = TransportCriticalPageBytes.writableRanges(speeduino, 9, 3)
        assertEquals(listOf(1 until 3), ranges)
    }

    @Test
    fun `first meaningful diff ignores the protected offset`() {
        val a = byteArrayOf(1, 2, 3)
        assertEquals(-1, TransportCriticalPageBytes.firstMeaningfulDiff(speeduino, 9, a, a.copyOf()))
        assertEquals(-1, TransportCriticalPageBytes.firstMeaningfulDiff(speeduino, 9, a, byteArrayOf(99, 2, 3)))
        assertEquals(0, TransportCriticalPageBytes.firstMeaningfulDiff(speeduino, 2, a, byteArrayOf(99, 2, 3)))
        assertEquals(2, TransportCriticalPageBytes.firstMeaningfulDiff(speeduino, 9, a, byteArrayOf(1, 2, 9)))
    }

    @Test
    fun `size mismatch is reported at the shorter length`() {
        assertEquals(3, TransportCriticalPageBytes.firstMeaningfulDiff(speeduino, 2, byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3, 4)))
        assertEquals(2, TransportCriticalPageBytes.firstMeaningfulDiff(speeduino, 2, byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
    }

    @Test
    fun `matches ignoring protected`() {
        assertTrue(TransportCriticalPageBytes.matchesIgnoringProtected(speeduino, 9, byteArrayOf(1, 2), byteArrayOf(7, 2)))
        assertFalse(TransportCriticalPageBytes.matchesIgnoringProtected(speeduino, 9, byteArrayOf(1, 2), byteArrayOf(1, 3)))
        assertEquals(9, TransportCriticalPageBytes.SPEEDUINO_SECONDARY_SERIAL_PAGE)
        assertEquals(0, TransportCriticalPageBytes.SPEEDUINO_SECONDARY_SERIAL_OFFSET)
    }
}
