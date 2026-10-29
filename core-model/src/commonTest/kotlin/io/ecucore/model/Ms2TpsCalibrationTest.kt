package io.ecucore.model

import io.ecucore.Ms2LiveDataParser
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Ms2TpsCalibrationTest {
    @Test
    fun parsesTpsAdcAtOffset86() {
        val data = ByteArray(212)
        data[86] = 0x03
        data[87] = 0xFF.toByte()
        assertEquals(1023, Ms2LiveDataParser.fromOutputChannels(data).tpsAdc)
    }

    @Test
    fun encodesAndDecodesS16BigEndian() {
        val c = TpsCalibration(0x0123, 0x03A0, TpsCalibration.ADC_MAX_10BIT)
        val bytes = encodeMs2TpsCalibration(c)
        assertContentEquals(byteArrayOf(0x01, 0x23, 0x03, 0xA0.toByte()), bytes)
        assertEquals(c, decodeMs2TpsCalibration(bytes))
        assertEquals(518, MS2_TPS_MIN_OFFSET)
        assertEquals(520, MS2_TPS_MAX_OFFSET)
    }

    @Test
    fun validationRespectsFamilyRange() {
        assertTrue(ConfigValidator.validateTpsCalibration(TpsCalibration(100, 900, 1023)).isValid)
        assertFalse(ConfigValidator.validateTpsCalibration(TpsCalibration(100, 900)).isValid)
        assertFalse(ConfigValidator.validateTpsCalibration(TpsCalibration(100, 1024, 1023)).isValid)
        assertFalse(ConfigValidator.validateTpsCalibration(TpsCalibration(500, 500, 1023)).isValid)
        assertFalse(ConfigValidator.validateTpsCalibration(TpsCalibration(500, 560, 1023)).isValid)
        assertTrue(ConfigValidator.validateTpsCalibration(TpsCalibration(10, 240)).isValid)
    }
}
