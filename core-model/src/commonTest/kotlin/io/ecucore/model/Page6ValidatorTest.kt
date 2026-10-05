package io.ecucore.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Page6ValidatorTest {

    private fun u(page: ByteArray, i: Int) = page[i].toInt() and 0xFF

    /** Página 6 com todos os campos já dentro das faixas aceitas pela ECU. */
    private fun validPage(): ByteArray {
        val p = ByteArray(128)
        p[7] = 5; p[8] = 100.toByte(); p[9] = 200.toByte(); p[10] = 50; p[11] = 10; p[12] = 100
        for (i in 0 until 6) p[15 + i] = (60 + i * 10).toByte()
        for (i in 0 until 9) p[27 + i] = (i * 10).toByte()
        p[45] = 10; p[46] = 10; p[47] = 10
        p[49] = 2; p[50] = 0; p[51] = 2; p[52] = 40; p[56] = 10; p[57] = 50; p[58] = 50; p[59] = 50
        p[61] = 2; p[62] = 0; p[63] = 2
        for (i in 0 until 10) { p[84 + i] = 50; p[94 + i] = (i * 5).toByte() }
        for (i in 0 until 4) { p[108 + i] = 50; p[112 + i] = (i * 5).toByte() }
        p[118] = 0; p[119] = 5
        return p
    }

    @Test
    fun `a valid page is returned unchanged and the input is not modified`() {
        val page = validPage()
        val copy = page.copyOf()
        val result = Page6Validator.sanitize(page)
        assertFalse(result.changed)
        assertContentEquals(copy, result.data)
        assertContentEquals(copy, page)
    }

    @Test
    fun `a blank page is lifted to the minimum accepted values`() {
        val result = Page6Validator.sanitize(ByteArray(128))
        assertTrue(result.changed)
        val d = result.data
        assertEquals(70, u(d, 8))
        assertEquals(70, u(d, 9))
        assertEquals(1, u(d, 11))
        assertEquals(60, u(d, 15))
        assertEquals(5, u(d, 45))
        assertEquals(1, u(d, 49))
        assertEquals(1, u(d, 51))
        assertEquals(1, u(d, 61))
        assertEquals(1, u(d, 63))
        assertEquals(1, u(d, 119))
    }

    @Test
    fun `values above the range are clamped down`() {
        val p = validPage()
        p[7] = 200.toByte(); p[10] = 255.toByte(); p[12] = 255.toByte(); p[52] = 255.toByte()
        p[57] = 255.toByte(); p[119] = 99
        for (i in 0 until 10) p[84 + i] = 255.toByte()
        val d = Page6Validator.sanitize(p).data
        assertEquals(16, u(d, 7))
        assertEquals(120, u(d, 10))
        assertEquals(200, u(d, 12))
        assertEquals(80, u(d, 52))
        assertEquals(200, u(d, 57))
        assertEquals(10, u(d, 119))
        for (i in 0 until 10) assertEquals(100, u(d, 84 + i))
    }

    @Test
    fun `inverted ego limits are swapped`() {
        val p = validPage()
        p[8] = 220.toByte(); p[9] = 90
        val d = Page6Validator.sanitize(p).data
        assertEquals(90, u(d, 8))
        assertEquals(220, u(d, 9))
    }

    @Test
    fun `signed fields are clamped on both ends`() {
        val high = validPage().also { it[50] = 100; it[62] = 120 }
        val dh = Page6Validator.sanitize(high).data
        assertEquals(40, dh[50].toInt())
        assertEquals(80, dh[62].toInt())
        val low = validPage().also { it[50] = (-100).toByte(); it[62] = (-100).toByte() }
        val dl = Page6Validator.sanitize(low).data
        assertEquals(-30, dl[50].toInt())
        assertEquals(-30, dl[62].toInt())
        val inside = validPage().also { it[50] = (-10).toByte(); it[62] = 30 }
        assertFalse(Page6Validator.sanitize(inside).changed)
    }

    @Test
    fun `monotonic arrays never decrease`() {
        val p = validPage()
        p[15] = 200.toByte(); p[16] = 100; p[17] = 150.toByte() // tensões: 200,100,150 -> 200,200,200...
        val d = Page6Validator.sanitize(p).data
        for (i in 1 until 6) assertTrue(u(d, 15 + i) >= u(d, 14 + i), "bin $i")
        // arrays não monotônicos só são limitados
        val q = validPage()
        q[84] = 90; q[85] = 10
        val dq = Page6Validator.sanitize(q).data
        assertEquals(90, u(dq, 84))
        assertEquals(10, u(dq, 85))
    }

    @Test
    fun `short pages are handled without touching missing offsets`() {
        val short = ByteArray(10)
        short[8] = 100
        val result = Page6Validator.sanitize(short)
        assertEquals(10, result.data.size)
        // o offset 9 (zerado) sobe para 70 e os limites ficam invertidos: são trocados
        assertEquals(70, u(result.data, 8))
        assertEquals(100, u(result.data, 9))
        // limites invertidos com o offset 9 ainda dentro, e com o offset 9 fora da página
        val tiny = ByteArray(9).also { it[8] = 100 }
        val tinyResult = Page6Validator.sanitize(tiny)
        assertEquals(9, tinyResult.data.size)
        assertTrue(Page6Validator.sanitize(ByteArray(0)).data.isEmpty())
    }
}
