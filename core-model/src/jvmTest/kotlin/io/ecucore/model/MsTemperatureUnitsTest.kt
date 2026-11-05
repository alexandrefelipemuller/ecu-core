package io.ecucore.model

import io.ecucore.Ms2LiveDataParser
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Campo 2026-11 (MicroSquirt MS2/Extra 3.4.4): TunerStudio CLT 75 °C / MAT 8 °C, app 24 °C / −13 °C.
 * Com `tempUnits` em °C a ECU manda °C×10 no outpc, e o app convertia de novo como °F.
 */
class MsTemperatureUnitsTest {

    @AfterTest
    fun reset() {
        MsTemperatureUnits.celsius = false
    }

    private fun outpc(matRaw: Int, cltRaw: Int) = ByteArray(212).also {
        it[20] = (matRaw shr 8).toByte(); it[21] = matRaw.toByte()
        it[22] = (cltRaw shr 8).toByte(); it[23] = cltRaw.toByte()
    }

    @Test
    fun `padrao em F x10 continua igual`() {
        val data = outpc(matRaw = 464, cltRaw = 1670) // 46.4 °F = 8 °C, 167 °F = 75 °C
        val live = Ms2LiveDataParser.fromOutputChannels(data)
        assertEquals(8, live.intakeTemp)
        assertEquals(75, live.coolantTemp)
        assertEquals(75.0, EcuOutputChannels.getField(212, "coolant")!!.parse(data), 0.05)
    }

    @Test
    fun `tempUnits em C le C x10 sem converter de novo`() {
        MsTemperatureUnits.celsius = true
        val data = outpc(matRaw = 80, cltRaw = 750)
        val live = Ms2LiveDataParser.fromOutputChannels(data)
        assertEquals(8, live.intakeTemp)
        assertEquals(75, live.coolantTemp)
        assertEquals(8.0, EcuOutputChannels.getField(212, "mat")!!.parse(data), 1e-9)
        assertEquals(75.0, EcuOutputChannels.getField(212, "coolant")!!.parse(data), 1e-9)
        assertEquals(75.0, EcuOutputChannels.getField(219, "coolant")!!.parse(outpc(80, 750) + ByteArray(7)), 1e-9)

        val readings = MegaSpeedLiveChannels.decode(data)!!.associateBy { it.channel.name }
        assertEquals(8.0, readings.getValue("mat").value, 1e-9)
        assertEquals(75.0, readings.getValue("coolant").value, 1e-9)
        // Outros canais não mudam.
        assertEquals(0.1, EcuOutputChannels.getField(212, "tps")!!.scale)
    }

    @Test
    fun `decodeFlag le o bit 0 no offset da familia`() {
        val page = ByteArray(1024).also { it[599] = 0x01 }
        assertEquals(true, MsTemperatureUnits.decodeFlag(EcuFamily.MS2, page))
        assertEquals(true, MsTemperatureUnits.decodeFlag(EcuFamily.MEGASPEED, byteArrayOf(0x03), pageOffset = 599))
        assertEquals(false, MsTemperatureUnits.decodeFlag(EcuFamily.MS2, byteArrayOf(0x02), pageOffset = 599))
        assertTrue(MsTemperatureUnits.decodeFlag(EcuFamily.MS3, ByteArray(256).also { it[103] = 1 })!!)
        assertNull(MsTemperatureUnits.decodeFlag(EcuFamily.MS2, ByteArray(256)))
        assertNull(MsTemperatureUnits.decodeFlag(EcuFamily.SPEEDUINO, page))
        assertFalse(MsTemperatureUnits.celsius)
    }
}
