package io.ecucore.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestoreTablePageValidatorTest {

    private fun u(page: ByteArray, i: Int) = page[i].toInt() and 0xFF

    private fun table(value: Int): ByteArray {
        val d = ByteArray(288)
        for (i in 0 until 256) d[i] = value.toByte()
        for (i in 0 until 16) d[256 + i] = (10 + i).toByte() // rpm crescente
        for (i in 0 until 16) d[272 + i] = (5 + i).toByte() // carga crescente
        return d
    }

    @Test
    fun `only the table pages are sanitized`() {
        assertNull(RestoreTablePageValidator.sanitize(1, ByteArray(288)))
        assertNull(RestoreTablePageValidator.sanitize(4, ByteArray(288)))
        assertNull(RestoreTablePageValidator.sanitize(12, ByteArray(288)))
        for (page in listOf(2, 3, 5)) assertNotNull(RestoreTablePageValidator.sanitize(page, table(100)), "page $page")
    }

    @Test
    fun `a valid table is returned unchanged and not modified in place`() {
        val data = table(100)
        val copy = data.copyOf()
        val result = RestoreTablePageValidator.sanitize(2, data)!!
        assertFalse(result.changed)
        assertContentEquals(copy, result.data)
        assertContentEquals(copy, data)
    }

    @Test
    fun `short buffers are returned as they are`() {
        val data = ByteArray(100) { 7 }
        val result = RestoreTablePageValidator.sanitize(2, data)!!
        assertFalse(result.changed)
        assertContentEquals(data, result.data)
    }

    @Test
    fun `values are clamped to each page raw range`() {
        val ve = RestoreTablePageValidator.sanitize(2, table(255))!!
        assertFalse(ve.changed) // 0..255
        val ign = RestoreTablePageValidator.sanitize(3, table(200))!!
        assertTrue(ign.changed)
        assertEquals(110, u(ign.data, 0))
        assertEquals(110, u(ign.data, 255))
        val afr = RestoreTablePageValidator.sanitize(5, table(10))!!
        assertTrue(afr.changed)
        assertEquals(70, u(afr.data, 0))
        val afrOk = RestoreTablePageValidator.sanitize(5, table(147))!!
        assertFalse(afrOk.changed)
    }

    @Test
    fun `axes are forced to be strictly increasing`() {
        val blank = RestoreTablePageValidator.sanitize(2, ByteArray(288))!!
        assertTrue(blank.changed)
        // RPM: mínimo 1 e passo de 1
        for (i in 0 until 16) assertEquals(i + 1, u(blank.data, 256 + i), "rpm $i")
        // carga: mínimo 0
        for (i in 0 until 16) assertEquals(i, u(blank.data, 272 + i), "load $i")

        val repeated = table(100)
        repeated[257] = repeated[256] // bin repetido
        repeated[274] = 0 // bin decrescente
        val fixed = RestoreTablePageValidator.sanitize(2, repeated)!!
        assertTrue(fixed.changed)
        assertTrue(u(fixed.data, 257) > u(fixed.data, 256))
        assertTrue(u(fixed.data, 274) > u(fixed.data, 273))
    }

    @Test
    fun `a bin at the top of the byte range saturates instead of overflowing`() {
        val d = table(100)
        for (i in 0 until 16) d[256 + i] = 255.toByte()
        val result = RestoreTablePageValidator.sanitize(2, d)!!
        // não existe valor estritamente crescente acima de 255: os bins ficam em 255
        assertFalse(result.changed)
        assertEquals(255, u(result.data, 256 + 15))
    }
}
