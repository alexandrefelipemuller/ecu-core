package io.ecucore.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EngineConstantsTest {

    private fun page1() = ByteArray(128)

    private fun base(): EngineConstants = EngineConstants.fromPage1(page1())

    private fun putU32(d: ByteArray, off: Int, v: Int) { for (i in 0..3) d[off + i] = ((v ushr (8 * i)) and 0xFF).toByte() }
    private fun putF32(d: ByteArray, off: Int, v: Float) = putU32(d, off, v.toBits())
    private fun putU16Le(d: ByteArray, off: Int, v: Int) { d[off] = (v and 0xFF).toByte(); d[off + 1] = ((v shr 8) and 0xFF).toByte() }
    private fun putU16Be(d: ByteArray, off: Int, v: Int) { d[off] = ((v shr 8) and 0xFF).toByte(); d[off + 1] = (v and 0xFF).toByte() }

    // ---- Speeduino page 1 ------------------------------------------------------------------------------

    @Test
    fun `decodes page 1 fields`() {
        val d = page1()
        d[3] = 0x04 // correção só no open time
        for (i in 0..5) { d[15 + i] = (60 + i * 20).toByte(); d[21 + i] = (160 - i * 10).toByte() }
        d[15] = 0 // pin layout 0
        d[24] = 87 // reqFuel 8.7
        d[25] = 2
        d[26] = (0x01 or (1 shl 4)).toByte() // alternado + ignição Alpha-N
        d[27] = 12
        d[28] = 0x63; d[29] = 0x01 // 355
        d[36] = (0x02 or 0x04 or 0x08 or (6 shl 4)).toByte() // map cycle min, 2 tempos, corpo, 6 cil
        d[37] = (1 or 0x08 or (8 shl 4)).toByte() // Alpha-N, ângulo fixo, 8 injetores
        d[38] = 0x40
        d[40] = 80
        d[50] = 147.toByte()
        d[51] = 0xB4.toByte(); d[52] = 0x00
        d[53] = 0x0E; d[54] = 0x01
        d[55] = 0x68; d[56] = 0x01

        val c = EngineConstants.fromPage1(d)

        assertEquals(InjectorBatteryCorrectionMode.OPEN_TIME_ONLY, c.injectorBatteryCorrectionMode)
        assertEquals(listOf(0f, 8f, 10f, 12f, 14f, 16f).size, c.batteryVoltageBins.size)
        assertEquals(6, c.injectorVoltageCorrectionRates.size)
        assertEquals(160, c.injectorVoltageCorrectionRates[0])
        assertEquals(8.7f, c.reqFuel, 1e-4f)
        assertEquals(2, c.squirtsPerCycle)
        assertEquals(InjectorStaging.ALTERNATING, c.injectorStaging)
        assertEquals(Algorithm.ALPHA_N, c.ignitionAlgorithm)
        assertEquals(Algorithm.ALPHA_N, c.algorithm)
        assertEquals(1.2f, c.injectorOpenTimeMs, 1e-4f)
        assertEquals(355, c.injectorCloseAngle)
        assertEquals(MapSampleMethod.CYCLE_MINIMUM, c.mapSampleMethod)
        assertEquals(EngineStroke.TWO_STROKE, c.engineStroke)
        assertEquals(InjectorPortType.THROTTLE_BODY, c.injectorPortType)
        assertEquals(6, c.numberOfCylinders)
        assertEquals(8, c.numberOfInjectors)
        assertTrue(c.ignitionFixedTimingEnabled)
        assertTrue(c.ignitionPerToothEnabled)
        assertEquals(80, c.injectorDutyLimit)
        assertEquals(14.7f, c.stoichiometricRatio, 1e-4f)
        assertEquals(180, c.channel2Angle)
        assertEquals(270, c.channel3Angle)
        assertEquals(360, c.channel4Angle)
    }

    @Test
    fun `zero page decodes to the default side of each option`() {
        val c = base()
        assertEquals(InjectorBatteryCorrectionMode.WHOLE_PULSE, c.injectorBatteryCorrectionMode)
        assertEquals(InjectorStaging.SIMULTANEOUS, c.injectorStaging)
        assertEquals(EngineStroke.FOUR_STROKE, c.engineStroke)
        assertEquals(InjectorPortType.PORT, c.injectorPortType)
        assertEquals(Algorithm.SPEED_DENSITY, c.algorithm)
        assertEquals(MapSampleMethod.INSTANTANEOUS, c.mapSampleMethod)
        assertTrue(!c.ignitionFixedTimingEnabled && !c.ignitionPerToothEnabled)
        assertEquals(4, c.numberOfCylinders) // bits inválidos caem no padrão
    }

    @Test
    fun `cylinder and injector bits map to counts`() {
        val expected = mapOf(0 to 4, 1 to 1, 2 to 2, 3 to 3, 4 to 4, 5 to 5, 6 to 6, 7 to 4, 8 to 8, 9 to 4, 15 to 4)
        for ((bits, count) in expected) {
            val d = page1()
            d[36] = (bits shl 4).toByte()
            d[37] = (bits shl 4).toByte()
            val c = EngineConstants.fromPage1(d)
            assertEquals(count, c.numberOfCylinders, "cil bits=$bits")
            assertEquals(count, c.numberOfInjectors, "inj bits=$bits")
        }
    }

    @Test
    fun `algorithm and map sample bits`() {
        for ((bits, alg) in mapOf(0 to Algorithm.SPEED_DENSITY, 1 to Algorithm.ALPHA_N, 2 to Algorithm.IMAP_EMAP, 3 to Algorithm.SPEED_DENSITY, 7 to Algorithm.SPEED_DENSITY)) {
            val d = page1(); d[37] = bits.toByte()
            assertEquals(alg, EngineConstants.fromPage1(d).algorithm, "bits=$bits")
        }
        for ((bits, m) in mapOf(0 to MapSampleMethod.INSTANTANEOUS, 1 to MapSampleMethod.CYCLE_AVERAGE, 2 to MapSampleMethod.CYCLE_MINIMUM, 3 to MapSampleMethod.EVENT_AVERAGE)) {
            val d = page1(); d[36] = bits.toByte()
            assertEquals(m, EngineConstants.fromPage1(d).mapSampleMethod)
        }
    }

    @Test
    fun `page 1 round trip through applyToPage1`() {
        val source = base().copy(
            reqFuel = 9.1f, squirtsPerCycle = 2, injectorStaging = InjectorStaging.ALTERNATING,
            algorithm = Algorithm.ALPHA_N, ignitionAlgorithm = Algorithm.IMAP_EMAP,
            engineStroke = EngineStroke.TWO_STROKE, numberOfCylinders = 6,
            injectorPortType = InjectorPortType.THROTTLE_BODY, numberOfInjectors = 8,
            injectorOpenTimeMs = 1.5f, injectorDutyLimit = 70, injectorCloseAngle = 400,
            ignitionFixedTimingEnabled = true, ignitionPerToothEnabled = true,
            injectorBatteryCorrectionMode = InjectorBatteryCorrectionMode.OPEN_TIME_ONLY,
            stoichiometricRatio = 9.0f, mapSampleMethod = MapSampleMethod.EVENT_AVERAGE,
            channel2Angle = 200, channel3Angle = 300, channel4Angle = 400,
            batteryVoltageBins = listOf(7f, 9f, 11f, 13f, 15f, 17f),
            injectorVoltageCorrectionRates = listOf(150, 130, 110, 100, 95, 90),
            boardLayout = "nome-inexistente",
        )
        val written = source.applyToPage1(ByteArray(128) { 0x11 })
        val back = EngineConstants.fromPage1(written)

        assertEquals(source.reqFuel, back.reqFuel, 0.11f)
        assertEquals(2, back.squirtsPerCycle)
        assertEquals(InjectorStaging.ALTERNATING, back.injectorStaging)
        assertEquals(Algorithm.ALPHA_N, back.algorithm)
        assertEquals(Algorithm.IMAP_EMAP, back.ignitionAlgorithm)
        assertEquals(EngineStroke.TWO_STROKE, back.engineStroke)
        assertEquals(6, back.numberOfCylinders)
        assertEquals(InjectorPortType.THROTTLE_BODY, back.injectorPortType)
        assertEquals(8, back.numberOfInjectors)
        assertEquals(1.5f, back.injectorOpenTimeMs, 0.11f)
        assertEquals(70, back.injectorDutyLimit)
        assertEquals(400, back.injectorCloseAngle)
        assertTrue(back.ignitionFixedTimingEnabled)
        assertTrue(back.ignitionPerToothEnabled)
        assertEquals(InjectorBatteryCorrectionMode.OPEN_TIME_ONLY, back.injectorBatteryCorrectionMode)
        assertEquals(MapSampleMethod.EVENT_AVERAGE, back.mapSampleMethod)
        assertEquals(200, back.channel2Angle)
        assertEquals(300, back.channel3Angle)
        assertEquals(400, back.channel4Angle)
        // os offsets 24-26 são compartilhados com reqFuel/divider/config: só as 3 primeiras taxas sobrevivem
        assertEquals(source.injectorVoltageCorrectionRates.take(3), back.injectorVoltageCorrectionRates.take(3))
        // bytes que o modelo não possui continuam como estavam
        for (i in listOf(0, 1, 2, 10, 60, 100, 127)) assertEquals(0x11.toByte(), written[i], "byte $i")
    }

    @Test
    fun `page 1 write clears the off flags and the whole pulse correction`() {
        val dirty = ByteArray(128) { 0xFF.toByte() }
        val c = base().copy(
            injectorBatteryCorrectionMode = InjectorBatteryCorrectionMode.WHOLE_PULSE,
            injectorStaging = InjectorStaging.SIMULTANEOUS,
            ignitionFixedTimingEnabled = false,
            ignitionPerToothEnabled = false,
            engineStroke = EngineStroke.FOUR_STROKE,
            injectorPortType = InjectorPortType.PORT,
        )
        val back = EngineConstants.fromPage1(c.applyToPage1(dirty))
        assertEquals(InjectorBatteryCorrectionMode.WHOLE_PULSE, back.injectorBatteryCorrectionMode)
        assertEquals(InjectorStaging.SIMULTANEOUS, back.injectorStaging)
        assertTrue(!back.ignitionPerToothEnabled)
        assertEquals(EngineStroke.FOUR_STROKE, back.engineStroke)
        assertEquals(InjectorPortType.PORT, back.injectorPortType)
    }

    @Test
    fun `page 1 write clamps and fills missing list entries with defaults`() {
        val c = base().copy(
            reqFuel = 100f, squirtsPerCycle = 999, injectorOpenTimeMs = 0f, injectorDutyLimit = 200,
            stoichiometricRatio = 99f, batteryVoltageBins = listOf(6f), injectorVoltageCorrectionRates = listOf(999),
            numberOfCylinders = 7, numberOfInjectors = 7,
        )
        val w = c.applyToPage1(page1())
        assertEquals(255, w[24].toInt() and 0xFF)
        assertEquals(255, w[25].toInt() and 0xFF)
        assertEquals(1, w[27].toInt() and 0xFF) // mínimo 1
        assertEquals(95, w[40].toInt() and 0xFF)
        assertEquals(255, w[50].toInt() and 0xFF)
        assertEquals(255, w[21].toInt() and 0xFF)
        assertEquals(80, w[16].toInt() and 0xFF) // padrão 8.0 V
        assertEquals(4, (w[36].toInt() shr 4) and 0x0F)
        assertEquals(4, (w[37].toInt() shr 4) and 0x0F)
    }

    @Test
    fun `board layout name selects the pin layout index`() {
        val index = (1..60).first { PinLayoutDetector.fromIndex(it).name != null }
        val name = PinLayoutDetector.fromIndex(index).name!!
        val written = base().copy(boardLayout = name).applyToPage1(page1())
        assertEquals(index, written[15].toInt() and 0xFF)
        assertEquals(name, EngineConstants.fromPage1(written).boardLayout)
    }

    @Test
    fun `toPage1 serializes on a blank page and sizes are validated`() {
        val data = base().toPage1()
        assertEquals(128, data.size)
        assertFailsWith<IllegalArgumentException> { EngineConstants.fromPage1(ByteArray(10)) }
        assertFailsWith<IllegalArgumentException> { base().applyToPage1(ByteArray(10)) }
    }

    // ---- MS2 -------------------------------------------------------------------------------------------------

    private fun ms2() = ByteArray(1024)

    @Test
    fun `decodes ms2 page`() {
        val d = ms2()
        putU16Be(d, 608, 8500) // 8.5 ms
        putU16Be(d, 522, 126) // 12.6 V
        d[0] = 6
        d[2] = 0x08 // odd fire
        d[630] = 3 // Alpha-N
        d[610] = 2
        d[611] = 1
        d[617] = 1 // 2 tempos
        d[619] = 6
        putU16Be(d, 620, 1800) // 180°
        d[601] = ((1 shl 2) or 2).toByte() // EVENT_AVERAGE, 4 eventos
        putU16Be(d, 584, 3000) // 300°
        d[733] = 0x08
        putU16Be(d, 662, 147)
        putU16Be(d, 679, 2400)
        d[740] = 2

        val c = EngineConstants.fromMs2Page1(d)

        assertEquals(8.5f, c.reqFuel, 1e-3f)
        assertEquals(12.6f, c.batteryVoltage, 1e-3f)
        assertEquals(6, c.numberOfCylinders)
        assertEquals(EngineType.ODD_FIRE, c.engineType)
        assertEquals(Algorithm.ALPHA_N, c.algorithm)
        assertEquals(2, c.squirtsPerCycle)
        assertEquals(InjectorStaging.ALTERNATING, c.injectorStaging)
        assertEquals(EngineStroke.TWO_STROKE, c.engineStroke)
        assertEquals(6, c.numberOfInjectors)
        assertEquals(180, c.channel2Angle)
        assertEquals(MapSampleMethod.EVENT_AVERAGE, c.mapSampleMethod)
        assertEquals(4, c.mapSampleEvents)
        assertEquals(300, c.mapSwitchPoint)
        assertTrue(c.includeAfrTarget)
        assertEquals(14.7f, c.stoichiometricRatio, 1e-3f)
        assertEquals(2400, c.engineDisplacementCc)
        assertEquals("Router", c.boardLayout)
    }

    @Test
    fun `ms2 defaults and enumerations`() {
        val c = EngineConstants.fromMs2Page1(ms2())
        assertEquals(1, c.numberOfCylinders) // coerceAtLeast(1)
        assertEquals(1, c.squirtsPerCycle)
        assertEquals(1, c.numberOfInjectors)
        assertEquals(EngineType.EVEN_FIRE, c.engineType)
        assertEquals(Algorithm.SPEED_DENSITY, c.algorithm)
        assertEquals(MapSampleMethod.CYCLE_MINIMUM, c.mapSampleMethod)
        assertEquals(1, c.mapSampleEvents)
        assertEquals("MS2-X", c.boardLayout)
        for ((raw, events) in mapOf(0 to 1, 1 to 2, 2 to 4, 3 to 1)) {
            val d = ms2(); d[601] = raw.toByte()
            assertEquals(events, EngineConstants.fromMs2Page1(d).mapSampleEvents, "raw=$raw")
        }
        for ((raw, name) in mapOf(1 to "MS2-X", 2 to "Router", 3 to "GPIO", 9 to "MS2-X")) {
            val d = ms2(); d[740] = raw.toByte()
            assertEquals(name, EngineConstants.fromMs2Page1(d).boardLayout)
        }
        for ((raw, alg) in mapOf(2 to Algorithm.PERCENT_BARO, 3 to Algorithm.ALPHA_N, 5 to Algorithm.MAF, 6 to Algorithm.ITB, 0 to Algorithm.SPEED_DENSITY)) {
            val d = ms2(); d[630] = raw.toByte()
            assertEquals(alg, EngineConstants.fromMs2Page1(d).algorithm, "raw=$raw")
        }
    }

    @Test
    fun `ms2 round trip through applyToMs2Page1`() {
        val c = EngineConstants.fromMs2Page1(ms2()).copy(
            reqFuel = 7.5f, batteryVoltage = 13.2f, numberOfCylinders = 4, engineType = EngineType.ODD_FIRE,
            algorithm = Algorithm.MAF, squirtsPerCycle = 2, injectorStaging = InjectorStaging.ALTERNATING,
            engineStroke = EngineStroke.TWO_STROKE, numberOfInjectors = 4, channel2Angle = 120,
            mapSampleMethod = MapSampleMethod.EVENT_AVERAGE, mapSampleEvents = 2, mapSwitchPoint = 25,
            includeAfrTarget = true, stoichiometricRatio = 14.7f, engineDisplacementCc = 1600, boardLayout = "GPIO",
        )
        val back = EngineConstants.fromMs2Page1(c.applyToMs2Page1(ms2()))
        assertEquals(7.5f, back.reqFuel, 1e-3f)
        assertEquals(13.2f, back.batteryVoltage, 0.11f)
        assertEquals(4, back.numberOfCylinders)
        assertEquals(EngineType.ODD_FIRE, back.engineType)
        assertEquals(Algorithm.MAF, back.algorithm)
        assertEquals(2, back.squirtsPerCycle)
        assertEquals(InjectorStaging.ALTERNATING, back.injectorStaging)
        assertEquals(EngineStroke.TWO_STROKE, back.engineStroke)
        assertEquals(120, back.channel2Angle)
        assertEquals(MapSampleMethod.EVENT_AVERAGE, back.mapSampleMethod)
        assertEquals(2, back.mapSampleEvents)
        assertEquals(25, back.mapSwitchPoint)
        assertTrue(back.includeAfrTarget)
        assertEquals(14.7f, back.stoichiometricRatio, 0.11f)
        assertEquals(1600, back.engineDisplacementCc)
        assertEquals("GPIO", back.boardLayout)
    }

    @Test
    fun `ms2 write covers each board name and the off side of the flags`() {
        for ((name, byte) in mapOf("Router" to 2, "GPIO" to 3, "MS2" to 1, "MS2-X" to 1, "qualquer" to 1)) {
            val w = EngineConstants.fromMs2Page1(ms2()).copy(boardLayout = name).applyToMs2Page1(ms2())
            assertEquals(byte, w[740].toInt(), name)
        }
        for ((events, bits) in mapOf(1 to 0, 2 to 1, 4 to 2, 8 to 0)) {
            val w = EngineConstants.fromMs2Page1(ms2()).copy(mapSampleEvents = events).applyToMs2Page1(ms2())
            assertEquals(bits, w[601].toInt() and 0x03, "events=$events")
        }
        val dirty = ByteArray(1024) { 0xFF.toByte() }
        val off = EngineConstants.fromMs2Page1(ms2()).copy(
            engineType = EngineType.EVEN_FIRE, injectorStaging = InjectorStaging.SIMULTANEOUS,
            engineStroke = EngineStroke.FOUR_STROKE, mapSampleMethod = MapSampleMethod.CYCLE_MINIMUM,
            includeAfrTarget = false, algorithm = Algorithm.ITB,
        ).applyToMs2Page1(dirty)
        assertEquals(0, off[2].toInt() and 0x08)
        assertEquals(0, off[611].toInt() and 0x01)
        assertEquals(0, off[617].toInt() and 0x03)
        assertEquals(0, off[601].toInt() and 0x04)
        assertEquals(0, off[733].toInt() and 0x08)
        assertEquals(6, off[630].toInt() and 0x07)
        assertEquals(0xFF.toByte(), off[900]) // resto da página preservado
    }

    @Test
    fun `ms2 sizes are validated`() {
        assertFailsWith<IllegalArgumentException> { EngineConstants.fromMs2Page1(ByteArray(100)) }
        assertFailsWith<IllegalArgumentException> { base().applyToMs2Page1(ByteArray(100)) }
    }

    // ---- rusEFI ------------------------------------------------------------------------------------------------

    private fun rusefi(size: Int = 556) = ByteArray(size)

    @Test
    fun `decodes the rusefi main page`() {
        val d = rusefi()
        d[0] = 92 // SIMULATOR_CONFIG
        putF32(d, 76, 250.5f)
        putU16Le(d, 200, 100) // tps min 0.5
        putU16Le(d, 202, 190) // tps max 0.95
        putU16Le(d, 208, 550)
        putF32(d, 436, 2.0f)
        putU32(d, 440, 6)
        d[444] = 9
        d[459] = 1 // sequential
        d[476] = 2 // wasted spark
        putF32(d, 488, 12.0f)
        putU32(d, 552, 8) // 60-2

        val c = EngineConstants.fromRusefiMainPage(d)

        assertEquals(6, c.numberOfCylinders)
        assertEquals(6, c.numberOfInjectors)
        assertEquals(InjectorLayout.SEQUENTIAL, c.injectorLayout)
        assertEquals(InjectorStaging.ALTERNATING, c.injectorStaging)
        assertEquals(2000, c.engineDisplacementCc)
        assertEquals(12, c.mapSwitchPoint)
        assertEquals("rusEFI", c.boardLayout)
        assertEquals("SIMULATOR_CONFIG", c.extraFields["rusefi_engine_type"])
        assertEquals("1-2-3-4-5-6", c.extraFields["rusefi_firing_order"])
        assertEquals("Sequential", c.extraFields["rusefi_injection_mode"])
        assertEquals("Wasted Spark", c.extraFields["rusefi_ignition_mode"])
        assertEquals("60-2", c.extraFields["rusefi_trigger_type"])
        assertEquals("550", c.extraFields["rusefi_cranking_rpm"])
        assertNotNull(c.extraFields["rusefi_injector_flow"])
        assertNotNull(c.extraFields["rusefi_tps_min"])
        assertNotNull(c.extraFields["rusefi_tps_max"])
    }

    @Test
    fun `rusefi names and injector layouts`() {
        for ((mode, layout) in mapOf(0 to InjectorLayout.PAIRED, 1 to InjectorLayout.SEQUENTIAL, 2 to InjectorLayout.BATCH, 3 to InjectorLayout.PAIRED)) {
            val d = rusefi(); d[459] = mode.toByte()
            val c = EngineConstants.fromRusefiMainPage(d)
            assertEquals(layout, c.injectorLayout, "mode=$mode")
            assertEquals(if (mode == 0) InjectorStaging.SIMULTANEOUS else InjectorStaging.ALTERNATING, c.injectorStaging)
        }
        for ((idx, name) in mapOf(92 to "SIMULATOR_CONFIG", 99 to "MINIMAL_PINS", 30 to "PROTEUS_ANALOG_PWM_TEST", 73 to "PROTEUS_STIM_QC", 103 to "PROTEUS_NISSAN_VQ35", 5 to "Type 5")) {
            val d = rusefi(); d[0] = idx.toByte()
            assertEquals(name, EngineConstants.fromRusefiMainPage(d).extraFields["rusefi_engine_type"])
        }
        for ((idx, name) in mapOf(0 to "One Cylinder", 1 to "1-3-4-2", 2 to "1-2-4-3", 3 to "1-3-2-4", 8 to "1-2", 9 to "1-2-3-4-5-6", 17 to "1-4-3-2", 25 to "1-2-3-4-5-6-7-8", 4 to "Order 4")) {
            val d = rusefi(); d[444] = idx.toByte()
            assertEquals(name, EngineConstants.fromRusefiMainPage(d).extraFields["rusefi_firing_order"])
        }
        for ((idx, name) in mapOf(0 to "Simultaneous", 1 to "Sequential", 2 to "Batch", 3 to "Single Point")) {
            val d = rusefi(); d[459] = idx.toByte()
            assertEquals(name, EngineConstants.fromRusefiMainPage(d).extraFields["rusefi_injection_mode"])
        }
        for ((idx, name) in mapOf(0 to "Single Coil", 1 to "Individual Coils", 2 to "Wasted Spark", 3 to "Two Distributors")) {
            val d = rusefi(); d[476] = idx.toByte()
            assertEquals(name, EngineConstants.fromRusefiMainPage(d).extraFields["rusefi_ignition_mode"])
        }
        for ((idx, name) in mapOf(0 to "Custom toothed wheel", 8 to "60-2", 9 to "36-1", 11 to "Single Tooth", 23 to "36-2-2-2", 48 to "36-2", 69 to "32-2", 70 to "36-2-1", 71 to "36-2-1-1", 33 to "Trigger 33")) {
            val d = rusefi(); putU32(d, 552, idx)
            assertEquals(name, EngineConstants.fromRusefiMainPage(d).extraFields["rusefi_trigger_type"])
        }
    }

    @Test
    fun `rusefi clamps the cylinder count`() {
        assertEquals(1, EngineConstants.fromRusefiMainPage(rusefi().also { putU32(it, 440, 0) }).numberOfCylinders)
        assertEquals(12, EngineConstants.fromRusefiMainPage(rusefi().also { putU32(it, 440, 40) }).numberOfCylinders)
    }

    @Test
    fun `rusefi f407 discovery uses its own offsets and scaling`() {
        val d = rusefi(548)
        d[0] = 99
        putF32(d, 72, 100f)
        putU16Le(d, 196, 200) // 200 * 0.0048828125
        putU16Le(d, 198, 400)
        putU16Le(d, 204, 600)
        putF32(d, 432, 1.6f)
        putU32(d, 440, 4)
        d[444] = 1
        d[455] = 2
        d[472] = 3
        putF32(d, 484, 20f)
        putU32(d, 544, 9)

        val c = EngineConstants.fromRusefiMainPage(d, "rusefi-f407-discovery")

        assertEquals(1600, c.engineDisplacementCc)
        assertEquals(InjectorLayout.BATCH, c.injectorLayout)
        assertEquals(20, c.mapSwitchPoint)
        assertEquals("MINIMAL_PINS", c.extraFields["rusefi_engine_type"])
        assertEquals("36-1", c.extraFields["rusefi_trigger_type"])
        assertEquals("Two Distributors", c.extraFields["rusefi_ignition_mode"])
        assertEquals("600", c.extraFields["rusefi_cranking_rpm"])
        assertTrue(c.extraFields["rusefi_tps_min"]!!.startsWith("0.97"))
    }

    @Test
    fun `rusefi sizes are validated`() {
        assertFailsWith<IllegalArgumentException> { EngineConstants.fromRusefiMainPage(ByteArray(555)) }
        assertFailsWith<IllegalArgumentException> { EngineConstants.fromRusefiMainPage(ByteArray(547), "rusefi-f407-discovery") }
    }

    // ---- enums e extensões -----------------------------------------------------------------------------------

    @Test
    fun `algorithm conversions`() {
        for ((bits, alg) in mapOf(0 to Algorithm.SPEED_DENSITY, 1 to Algorithm.ALPHA_N, 2 to Algorithm.IMAP_EMAP, 9 to Algorithm.SPEED_DENSITY)) assertEquals(alg, Algorithm.fromBits(bits))
        for ((bits, alg) in mapOf(2 to Algorithm.PERCENT_BARO, 3 to Algorithm.ALPHA_N, 5 to Algorithm.MAF, 6 to Algorithm.ITB, 1 to Algorithm.SPEED_DENSITY)) assertEquals(alg, Algorithm.fromMs2Bits(bits))
        assertEquals(listOf(0, 1, 2, 0, 0, 0), Algorithm.entries.map { it.toBits() })
        assertEquals(listOf(1, 3, 1, 2, 5, 6), Algorithm.entries.map { it.toMs2Bits() })
        assertEquals("Speed Density (MAP)", Algorithm.SPEED_DENSITY.displayName)
    }

    @Test
    fun `map sample method conversions`() {
        assertEquals(MapSampleMethod.CYCLE_AVERAGE, MapSampleMethod.fromBits(9))
        assertEquals(listOf(0, 1, 2, 3), MapSampleMethod.entries.map { it.toBits() })
        assertEquals("Event Average", MapSampleMethod.EVENT_AVERAGE.displayName)
    }

    @Test
    fun `load type extensions follow the algorithms`() {
        val sd = base()
        assertEquals(VeTable.LoadType.MAP, sd.fuelTableLoadType())
        assertEquals(IgnitionTable.LoadType.MAP, sd.ignitionTableLoadType())
        assertEquals(AfrTable.LoadType.MAP, sd.afrTableLoadType())
        val alpha = sd.copy(algorithm = Algorithm.ALPHA_N, ignitionAlgorithm = Algorithm.ALPHA_N)
        assertEquals(VeTable.LoadType.TPS, alpha.fuelTableLoadType())
        assertEquals(IgnitionTable.LoadType.TPS, alpha.ignitionTableLoadType())
        assertEquals(AfrTable.LoadType.TPS, alpha.afrTableLoadType(isLegacyFormat = true))
        assertEquals(AfrTable.LoadType.MAP, alpha.afrTableLoadType(isLegacyFormat = false))
        assertEquals(AfrTable.LoadType.MAP, sd.afrTableLoadType(isLegacyFormat = true))
    }

    @Test
    fun `enum display names`() {
        assertEquals("Alternating", InjectorStaging.ALTERNATING.displayName)
        assertEquals("Two-stroke", EngineStroke.TWO_STROKE.displayName)
        assertEquals("Throttle Body", InjectorPortType.THROTTLE_BODY.displayName)
        assertEquals("Odd fire", EngineType.ODD_FIRE.displayName)
        assertEquals("Semi-Sequential", InjectorLayout.SEMI_SEQUENTIAL.displayName)
        assertContentEquals(listOf(true), listOf(base().toPage1().size == 128))
    }
}
