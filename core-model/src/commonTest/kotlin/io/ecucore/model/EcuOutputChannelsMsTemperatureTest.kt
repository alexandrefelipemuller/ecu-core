package io.ecucore.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Logs CSV/MSL do MS2/MS3 precisam de CLT e MAT (antes só saía warmupEnrich). */
class EcuOutputChannelsMsTemperatureTest {

    private fun width(t: DataType) = when (t) { DataType.U08, DataType.S08 -> 1; DataType.U16, DataType.S16 -> 2 }

    private fun put16(d: ByteArray, off: Int, v: Int) { d[off] = (v shr 8).toByte(); d[off + 1] = v.toByte() }

    private fun block(size: Int) = ByteArray(size).also {
        put16(it, 16, 1013)   // barometer 101.3 kPa
        put16(it, 20, 500)    // mat 50.0 F -> 10.0 C
        put16(it, 22, 1852)   // coolant 185.2 F -> 85.1 C
    }

    @Test
    fun `MS2 212 tem mat coolant e barometer em celsius`() {
        val d = block(212)
        assertEquals(10.0, assertNotNull(EcuOutputChannels.getField(212, "mat")).parse(d), 0.01)
        assertEquals(85.1, assertNotNull(EcuOutputChannels.getField(212, "coolant")).parse(d), 0.01)
        assertEquals(101.3, assertNotNull(EcuOutputChannels.getField(212, "barometer")).parse(d), 0.01)
    }

    @Test
    fun `MS3 509 tem mat e coolant em celsius`() {
        val d = block(509)
        assertEquals(10.0, assertNotNull(EcuOutputChannels.getField(509, "mat")).parse(d), 0.01)
        assertEquals(85.1, assertNotNull(EcuOutputChannels.getField(509, "coolant")).parse(d), 0.01)
    }

    @Test
    fun `canais do MS2 cabem no bloco de 212 e oil fica so na MegaSpeed`() {
        EcuOutputChannels.getDefinition(212).forEach {
            assertTrue(it.offset + width(it.type) <= 212, "${it.name} fora do bloco de 212")
        }
        assertNull(EcuOutputChannels.getField(212, "oil"))
        assertNotNull(EcuOutputChannels.getField(219, "oil"))
    }
}
