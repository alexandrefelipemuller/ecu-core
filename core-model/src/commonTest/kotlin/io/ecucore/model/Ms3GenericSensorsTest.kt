package io.ecucore.model

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Ms3GenericSensorsTest {

    private val gen14 = Ms3GenericSensors.FirmwareGeneration.MS3_1_4
    private val gen15 = Ms3GenericSensors.FirmwareGeneration.MS3_1_5_PLUS

    @AfterTest
    fun clearAliases() {
        SpeeduinoOutputChannels.setSensorRoleAliases(EcuFamily.MS3, emptyList())
    }

    @Test
    fun `generation comes from the signature version`() {
        assertEquals(gen14, Ms3GenericSensors.generationFor("MS3 Format 0523.15 "))
        assertEquals(gen14, Ms3GenericSensors.generationFor("MS3 Format 0523.15P"))
        assertEquals(gen15, Ms3GenericSensors.generationFor("MS3 Format 0592.13 "))
        assertEquals(gen15, Ms3GenericSensors.generationFor("MS3 Format 0601.168"))
        assertEquals(gen14, Ms3GenericSensors.generationFor("garbage"), "desconhecido = layout mais restrito")
    }

    @Test
    fun `parse reads source, transform, points and filter of each slot`() {
        val page = ByteArray(1024)
        // sensor03: source 4, trans Linear 0.5-4.5V (9), 0.0 .. 10.0, LF 50
        page[662 + 2] = 4
        page[678 + 2] = 9
        page[694 + 4] = 0x00; page[694 + 5] = 0x00
        page[726 + 4] = 0x00; page[726 + 5] = 100
        page[758 + 2] = 50
        // sensor16: val0 negativo (-1.3)
        page[694 + 30] = 0xFF.toByte(); page[694 + 31] = 0xF3.toByte()

        val sensors = Ms3GenericSensors.parse(page, gen15)

        assertEquals(16, sensors.size)
        assertEquals(Ms3GenericSensorConfig(3, 4, 9, 0.0, 10.0, 50), sensors[2])
        assertEquals(-1.3, sensors[15].valueLow, 1e-9)
        assertFalse(sensors[0].isEnabled)
        assertTrue(sensors[2].isEnabled)
    }

    @Test
    fun `apply only touches the slot fields and preserves unused high bits`() {
        val page = ByteArray(1024) { (it * 7).toByte() }
        page[662 + 1] = 0xC0.toByte() // bits 6-7 sem uso no source do slot 2
        page[678 + 1] = 0xF0.toByte() // bits 4-7 sem uso no trans do slot 2
        val config = Ms3GenericSensorConfig(index = 2, source = 5, transform = 9, valueLow = 0.0, valueHigh = 145.0, lagFactor = 60)

        val updated = Ms3GenericSensors.apply(page, config, gen15)

        assertEquals(0xC5, updated[663].toInt() and 0xFF)
        assertEquals(0xF9, updated[679].toInt() and 0xFF)
        assertEquals(config, Ms3GenericSensors.parse(updated, gen15)[1])
        val touched = setOf(663, 679, 696, 697, 728, 729, 759)
        for (i in page.indices) {
            if (i !in touched) assertEquals(page[i], updated[i], "byte $i não deveria mudar")
        }
    }

    @Test
    fun `validation rejects options the firmware generation does not have`() {
        val page = ByteArray(1024)
        val linear05 = Ms3GenericSensorConfig(1, 4, Ms3SensorTransform.LINEAR_05_45V.raw, 0.0, 10.0, 100)
        assertFailsWith<IllegalArgumentException> { Ms3GenericSensors.apply(page, linear05, gen14) }
        assertFailsWith<IllegalArgumentException> { Ms3GenericSensors.apply(page, linear05.copy(source = 32), gen14) }
        assertFailsWith<IllegalArgumentException> { Ms3GenericSensors.apply(page, linear05.copy(transform = 8), gen15) }
        assertFailsWith<IllegalArgumentException> { Ms3GenericSensors.apply(page, linear05.copy(lagFactor = 5), gen15) }
        assertFailsWith<IllegalArgumentException> { Ms3GenericSensors.apply(page, linear05.copy(valueHigh = 4000.0), gen15) }
        assertFailsWith<IllegalArgumentException> { Ms3GenericSensors.apply(page, linear05.copy(index = 17), gen15) }
    }

    @Test
    fun `0,5-4,5V sensor uses the native transform on 0592+ and extrapolates on 0523`() {
        val ps10Bar = LinearSensorSpec(voltageLow = 0.5, valueLow = 0.0, voltageHigh = 4.5, valueHigh = 10.0)

        assertEquals(
            Triple(Ms3SensorTransform.LINEAR_05_45V, 0.0, 10.0),
            Ms3GenericSensors.linearCalibration(ps10Bar, gen15),
        )
        assertEquals(
            Triple(Ms3SensorTransform.LINEAR, -1.3, 11.3), // -1.25 / 11.25 arredondado a 0.1
            Ms3GenericSensors.linearCalibration(ps10Bar, gen14),
        )
        val zeroToFive = LinearSensorSpec(0.0, 0.0, 5.0, 100.0)
        assertEquals(Triple(Ms3SensorTransform.LINEAR, 0.0, 100.0), Ms3GenericSensors.linearCalibration(zeroToFive, gen15))
    }

    @Test
    fun `board inputs follow the MS3 or MS3Pro variant`() {
        val ms3 = Ms3GenericSensors.boardInputs("MS3 Format 0592.13 ")
        assertEquals("JS5 (ADC6)", ms3.first { it.source == 1 }.label)
        assertEquals("CAN ADC01", ms3.first { it.source == 8 }.label)

        val pro = Ms3GenericSensors.boardInputs("MS3 Format 0601.16U")
        assertEquals("Analog In 1", pro.first { it.source == 4 }.label)
        assertEquals("Analog In 8", pro.first { it.source == 36 }.label)
        assertNull(pro.firstOrNull { it.source == 1 })

        val oldPro = Ms3GenericSensors.boardInputs("MS3 Format 0523.15P")
        assertTrue(oldPro.all { it.source <= 31 }, "0523 só tem 5 bits de source")
    }

    @Test
    fun `MS3 block sizes use the MS3 output channel layout, not the MegaSpeed one`() {
        for (blockSize in listOf(509, 512)) {
            assertEquals(226, SpeeduinoOutputChannels.getField(blockSize, "tpsADC")?.offset)
            assertEquals(16, SpeeduinoOutputChannels.getField(blockSize, "baro")?.offset)
            assertEquals(108, SpeeduinoOutputChannels.getField(blockSize, "sensor03")?.offset)
        }
        assertEquals(86, SpeeduinoOutputChannels.getField(212, "tpsADC")?.offset, "MS2 não muda")
    }

    @Test
    fun `role aliases map oil and fuel pressure to generic sensor slots`() {
        val data = ByteArray(509)
        data[104 + 4] = 0x00; data[104 + 5] = 42 // sensor03 = 4.2
        data[104 + 6] = 0x01; data[104 + 7] = 0x2C // sensor04 = 30.0
        SpeeduinoOutputChannels.setSensorRoleAliases(
            EcuFamily.MS3,
            listOf(
                Ms3GenericSensors.liveField(3, name = "oilPressure", units = "bar"),
                Ms3GenericSensors.liveField(4, name = "fuelPressure", units = "psi"),
            )
        )

        assertEquals(4.2, SpeeduinoOutputChannels.getField(509, "oilPressure")!!.parse(data), 1e-9)
        assertEquals(30.0, SpeeduinoOutputChannels.getField(509, "fuelPressure")!!.parse(data), 1e-9)
        assertNull(SpeeduinoOutputChannels.getField(130, "oilPressure")?.takeIf { it.offset == 108 }, "não vaza pra Speeduino")

        SpeeduinoOutputChannels.clearAllRuntimeDefinitions()
        assertEquals("bar", SpeeduinoOutputChannels.getField(509, "oilPressure")?.units, "disconnect não apaga config do usuário")
    }

    @Test
    fun `span written to the ECU covers every slot field`() {
        assertEquals(662, Ms3GenericSensors.CONFIG_SPAN.first)
        assertEquals(773, Ms3GenericSensors.CONFIG_SPAN.last)
        val page = ByteArray(1024)
        val updated = Ms3GenericSensors.apply(page, Ms3GenericSensorConfig(16, 3, 1, -1.0, 100.0, 100), gen15)
        val changed = page.indices.filter { page[it] != updated[it] }
        assertTrue(changed.all { it in Ms3GenericSensors.CONFIG_SPAN }, "alterados: $changed")
        assertContentEquals(page.copyOfRange(0, 662), updated.copyOfRange(0, 662))
    }
}

class Ms2PressureSensorAliasTest {

    @AfterTest
    fun clearAliases() {
        SpeeduinoOutputChannels.setSensorRoleAliases(EcuFamily.MS2, emptyList())
    }

    private fun adcFrame(offset: Int, adc: Int): ByteArray = ByteArray(212).also {
        it[offset] = (adc shr 8).toByte()
        it[offset + 1] = (adc and 0xFF).toByte()
    }

    @Test
    fun `PS-10 on ADC6 converts raw ADC to bar like TunerStudio would`() {
        val ps10 = PressureSensorPresets.byId("ps10_bar")!!
        val field = Ms2AdcInputs.roleField(SensorRole.OIL_PRESSURE, Ms2AdcInputs.input("adc6")!!, ps10.spec, ps10.units)

        // 0,5 V = 102,3 counts -> 0 bar; 2,5 V = 511,5 -> 5 bar; 4,5 V = 920,7 -> 10 bar
        assertEquals(0.0, field.parse(adcFrame(128, 102)), 0.02)
        assertEquals(5.0, field.parse(adcFrame(128, 512)), 0.02)
        assertEquals(10.0, field.parse(adcFrame(128, 921)), 0.02)
        assertEquals(-1.25, field.parse(adcFrame(128, 0)), 0.001, "abaixo de 0,5 V extrapola (sensor em curto/desligado)")
    }

    @Test
    fun `MS2 aliases apply to MS2 block size only`() {
        val ps10 = PressureSensorPresets.byId("ps10_bar")!!
        SpeeduinoOutputChannels.setSensorRoleAliases(
            EcuFamily.MS2,
            listOf(Ms2AdcInputs.roleField(SensorRole.FUEL_PRESSURE, Ms2AdcInputs.input("adc7")!!, ps10.spec, "bar")),
        )

        assertEquals(130, SpeeduinoOutputChannels.getField(212, "fuelPressure")?.offset)
        assertEquals(5.0, SpeeduinoOutputChannels.getField(212, "fuelPressure")!!.parse(adcFrame(130, 512)), 0.02)
        assertNull(SpeeduinoOutputChannels.getField(509, "fuelPressure"), "não vaza pra MS3")
    }

    @Test
    fun `invalid curves are rejected`() {
        val input = Ms2AdcInputs.input("adc6")!!
        assertFailsWith<IllegalArgumentException> {
            Ms2AdcInputs.roleField(SensorRole.OIL_PRESSURE, input, LinearSensorSpec(4.5, 0.0, 0.5, 10.0), "bar")
        }
        assertFailsWith<IllegalArgumentException> {
            Ms2AdcInputs.roleField(SensorRole.OIL_PRESSURE, input, LinearSensorSpec(0.5, 3.0, 4.5, 3.0), "bar")
        }
    }
}

class Ms3SlotPlannerTest {

    private fun sensors(vararg enabled: Pair<Int, Int>): List<Ms3GenericSensorConfig> {
        val bySlot = enabled.toMap()
        return (1..16).map { Ms3GenericSensorConfig(it, bySlot[it] ?: 0, 1, 0.0, 100.0, 100) }
    }

    @Test
    fun `picks the first free slot and never one enabled elsewhere`() {
        val plan = Ms3SlotPlanner.plan(sensors(1 to 3, 2 to 5), source = 4, previousSlot = null, previousSource = null, reservedSlots = emptySet())
        assertEquals(Ms3SlotPlan(3, emptyList()), plan)
    }

    @Test
    fun `skips slots reserved for the other role`() {
        val plan = Ms3SlotPlanner.plan(sensors(1 to 3), source = 4, previousSlot = null, previousSource = null, reservedSlots = setOf(2))
        assertEquals(3, plan?.slot)
    }

    @Test
    fun `reuses the app's previous slot, warning if it was changed outside the app`() {
        assertEquals(
            Ms3SlotPlan(5, emptyList()),
            Ms3SlotPlanner.plan(sensors(5 to 4), source = 4, previousSlot = 5, previousSource = 4, reservedSlots = emptySet()),
        )
        assertEquals(
            Ms3SlotPlan(5, listOf(Ms3SlotWarning.SlotChangedOutsideApp(5, 2))),
            Ms3SlotPlanner.plan(sensors(5 to 2), source = 4, previousSlot = 5, previousSource = 4, reservedSlots = emptySet()),
        )
    }

    @Test
    fun `warns when another enabled slot reads the same input`() {
        val plan = Ms3SlotPlanner.plan(sensors(1 to 4), source = 4, previousSlot = null, previousSource = null, reservedSlots = emptySet())
        assertEquals(Ms3SlotPlan(2, listOf(Ms3SlotWarning.InputAlreadyUsed(1, 4))), plan)
    }

    @Test
    fun `returns null when every slot is taken`() {
        val all = (1..16).map { it to 1 }.toTypedArray()
        assertNull(Ms3SlotPlanner.plan(sensors(*all), source = 4, previousSlot = null, previousSource = null, reservedSlots = emptySet()))
    }
}
