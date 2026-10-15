package io.ecucore.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MegaSpeedLoggerTest {

    /** Codifica um registro como o ISR da MS3 faz: byte0 = flags<<4 | t[19:16]; bytes 1..2 = t[15:0]. */
    private fun put(buf: ByteArray, index: Int, ticks: Int, flags: Int) {
        val o = index * 3
        buf[o] = (((flags and 0xF) shl 4) or ((ticks shr 16) and 0x0F)).toByte()
        buf[o + 1] = (ticks shr 8).toByte()
        buf[o + 2] = ticks.toByte()
    }

    private fun buffer() = ByteArray(1024)

    @Test
    fun `decodifica tempo de 20 bits e flags do nibble alto`() {
        val b = buffer()
        put(b, 0, ticks = 0xABCDE, flags = 0b0101)
        put(b, 1, ticks = 1500, flags = 0b1010)
        val c = MegaSpeedLogDecoder.decode(MegaSpeedLoggerKind.COMPOSITE, b)

        assertEquals(2, c.records.size)
        assertEquals(0xABCDE, c.records[0].ticks)
        assertTrue(c.records[0].synced)
        assertFalse(c.records[0].secondFlag)
        assertTrue(c.records[0].bit6)
        assertFalse(c.records[0].bit7)
        assertFalse(c.records[1].synced)
        assertTrue(c.records[1].secondFlag)
        assertTrue(c.records[1].bit7)
    }

    @Test
    fun `tick e 0,667 microssegundos`() {
        val r = MegaSpeedLogRecord(ticks = 1500, flags = 0)
        assertEquals(1000.0, r.micros, 0.5) // 1500 ticks = 1 ms
    }

    @Test
    fun `fim da captura e o primeiro registro todo zero`() {
        val b = buffer()
        repeat(5) { put(b, it, 1000 + it, 1) }
        assertEquals(5, MegaSpeedLogDecoder.decode(MegaSpeedLoggerKind.TOOTH, b).records.size)
        assertEquals(0, MegaSpeedLogDecoder.decode(MegaSpeedLoggerKind.TOOTH, buffer()).records.size)
    }

    @Test
    fun `buffer cheio le 340 registros e nao invade ponteiro e versao`() {
        val b = buffer()
        repeat(MegaSpeedLogDecoder.MAX_RECORDS) { put(b, it, 1000 + it, 1) }
        b[0x3FF] = 1
        val c = MegaSpeedLogDecoder.decode(MegaSpeedLoggerKind.COMPOSITE, b)
        assertEquals(340, c.records.size)
        assertEquals(1000 + 339, c.records.last().ticks)
        assertEquals(1, c.formatVersion)
        assertTrue(c.formatKnown)
    }

    @Test
    fun `versao diferente de 1 e marcada como desconhecida`() {
        val b = buffer().also { put(it, 0, 1000, 1); it[0x3FF] = 7 }
        val c = MegaSpeedLogDecoder.decode(MegaSpeedLoggerKind.TOOTH, b)
        assertEquals(7, c.formatVersion)
        assertFalse(c.formatKnown)
    }

    @Test
    fun `composto de erro de sync e circular a partir do ponteiro`() {
        val b = buffer()
        repeat(MegaSpeedLogDecoder.MAX_RECORDS) { put(b, it, 100 + it, 1) }
        // ponteiro = próximo ponto a escrever = registro 10 (o mais antigo)
        b[0x3FD] = 0x00; b[0x3FE] = (10 * 3).toByte()
        val c = MegaSpeedLogDecoder.decode(MegaSpeedLoggerKind.SYNC_COMPOSITE, b)
        assertEquals(340, c.records.size)
        assertEquals(110, c.records.first().ticks)
        assertEquals(109, c.records.last().ticks)
    }

    @Test
    fun `ponteiro invalido cai para leitura linear`() {
        val b = buffer()
        repeat(4) { put(b, it, 200 + it, 1) }
        b[0x3FD] = 0xFF.toByte(); b[0x3FE] = 0xFF.toByte()
        val c = MegaSpeedLogDecoder.decode(MegaSpeedLoggerKind.SYNC_COMPOSITE, b)
        assertEquals(listOf(200, 201, 202, 203), c.records.map { it.ticks })
    }

    @Test
    fun `buffer curto e rejeitado`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            MegaSpeedLogDecoder.decode(MegaSpeedLoggerKind.TOOTH, ByteArray(100))
        }
    }

    @Test
    fun `roda 36-1 aparece como um intervalo longo e estima rotacao`() {
        // 36-1 a 3000 rpm: 20 ms/volta, 10 graus por dente = 555,5 us = 833 ticks; o dente faltante = 2x
        val b = buffer()
        val normal = 833
        var i = 0
        repeat(2) {
            repeat(34) { put(b, i++, normal, 1) }
            put(b, i++, normal * 2, 1) // gap do dente faltante
        }
        val c = MegaSpeedLogDecoder.decode(MegaSpeedLoggerKind.TOOTH, b)
        val s = assertNotNull(MegaSpeedLogAnalysis.summarize(c, wheelTeeth = 36))

        assertEquals(70, s.count)
        assertEquals(listOf(34, 69), s.longGapIndices)
        assertEquals(1.0, s.syncedRatio!!, 1e-9)
        assertTrue(s.estimatedRpm!! in 2950..3050, "rpm estimado ${s.estimatedRpm}")
    }

    @Test
    fun `gatilho nao tem flag de sync e sem dentes nao estima rotacao`() {
        val b = buffer().also { buf -> repeat(3) { i -> put(buf, i, ticks = 900, flags = 0) } }
        val c = MegaSpeedLogDecoder.decode(MegaSpeedLoggerKind.TRIGGER, b)
        val s = assertNotNull(MegaSpeedLogAnalysis.summarize(c, wheelTeeth = 36))
        assertNull(s.syncedRatio)
        assertNull(s.estimatedRpm)
        assertNull(MegaSpeedLogAnalysis.summarize(MegaSpeedLogDecoder.decode(MegaSpeedLoggerKind.TOOTH, buffer())))
    }

    @Test
    fun `csv traz cabecalho e uma linha por registro`() {
        val b = buffer().also { put(it, 0, 1500, 1); put(it, 1, 3000, 0) }
        val csv = MegaSpeedLogAnalysis.toCsv(MegaSpeedLogDecoder.decode(MegaSpeedLoggerKind.COMPOSITE, b)).trim().lines()
        assertEquals("index,micros,ticks,flags", csv.first())
        assertEquals(3, csv.size)
        assertTrue(csv[1].startsWith("0,1000"))
    }
}
