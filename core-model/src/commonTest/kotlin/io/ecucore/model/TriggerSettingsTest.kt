package io.ecucore.model

import io.ecucore.model.TriggerSettings.CoilSignalMode
import io.ecucore.model.TriggerSettings.SignalEdge
import io.ecucore.model.TriggerSettings.TriggerFilter
import io.ecucore.model.TriggerSettings.TriggerSpeed
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TriggerSettingsTest {

    private fun page4() = ByteArray(TriggerSettings.PAGE_LENGTH)

    // ---- Speeduino page 4 ----------------------------------------------------------------------------

    @Test
    fun `decodes every field of page 4`() {
        val d = page4()
        d[0] = 0x9C.toByte(); d[1] = 0xFF.toByte() // -100 graus
        d[2] = 12 // fixed timing
        d[3] = (-5).toByte() // cranking advance (S8)
        d[4] = 3 // multiplicador
        d[5] = (0b10110_1_1_1).toByte() // padrão 22, coil GOING_HIGH, CAM, FALLING
        d[6] = 0x81.toByte() // sec edge FALLING + resync
        d[8] = 0x82.toByte() // nível alto + tipo 2
        d[11] = 7
        d[12] = (0b01_011_00).shl(0).toByte().let { (0b0100_1100 or (0x02 shl 5)).toByte() } // sparkMode 3, filtro 2
        d[15] = 36
        d[16] = 1
        d[123] = 0x08

        val t = TriggerSettings.fromPageData(d)

        assertEquals(-100, t.triggerAngleDeg)
        assertEquals(12, t.fixedTimingAngleDeg)
        assertEquals(-5, t.crankingAdvanceDeg)
        assertEquals(3, t.triggerAngleMultiplier)
        assertEquals(22, t.triggerPattern)
        assertEquals(CoilSignalMode.GOING_HIGH, t.coilSignalMode)
        assertEquals(TriggerSpeed.CAM, t.primaryTriggerSpeed)
        assertEquals(SignalEdge.FALLING, t.triggerEdge)
        assertEquals(SignalEdge.FALLING, t.secondaryTriggerEdge)
        assertTrue(t.reSyncEveryCycle)
        assertEquals(2, t.secondaryTriggerType)
        assertTrue(t.levelForFirstPhaseHigh)
        assertEquals(7, t.skipRevolutions)
        assertEquals(3, t.sparkMode)
        assertEquals(TriggerFilter.MEDIUM, t.triggerFilter)
        assertEquals(36, t.primaryBaseTeeth)
        assertEquals(1, t.missingTeeth)
        assertTrue(t.dwellErrorCorrectionEnabled)
    }

    @Test
    fun `all zero page decodes to the default side of every flag`() {
        val t = TriggerSettings.fromPageData(page4())
        assertEquals(0, t.triggerAngleDeg)
        assertEquals(CoilSignalMode.GOING_LOW, t.coilSignalMode)
        assertEquals(TriggerSpeed.CRANK, t.primaryTriggerSpeed)
        assertEquals(SignalEdge.RISING, t.triggerEdge)
        assertEquals(SignalEdge.RISING, t.secondaryTriggerEdge)
        assertFalse(t.reSyncEveryCycle)
        assertFalse(t.levelForFirstPhaseHigh)
        assertEquals(TriggerFilter.OFF, t.triggerFilter)
        assertFalse(t.dwellErrorCorrectionEnabled)
    }

    @Test
    fun `trigger filter covers its four levels`() {
        val expected = listOf(TriggerFilter.OFF, TriggerFilter.WEAK, TriggerFilter.MEDIUM, TriggerFilter.AGGRESSIVE)
        for (level in 0..3) {
            val d = page4()
            d[12] = (level shl 5).toByte()
            assertEquals(expected[level], TriggerSettings.fromPageData(d).triggerFilter)
        }
    }

    @Test
    fun `page 4 round trip keeps the edited fields and the untouched bytes`() {
        val base = ByteArray(128) { (it * 3 + 1).toByte() }
        val edited = TriggerSettings(
            triggerAngleDeg = -120,
            triggerAngleMultiplier = 2,
            triggerPattern = 9,
            primaryBaseTeeth = 60,
            missingTeeth = 2,
            primaryTriggerSpeed = TriggerSpeed.CAM,
            triggerEdge = SignalEdge.FALLING,
            secondaryTriggerEdge = SignalEdge.FALLING,
            secondaryTriggerType = 3,
            levelForFirstPhaseHigh = true,
            skipRevolutions = 4,
            triggerFilter = TriggerFilter.AGGRESSIVE,
            reSyncEveryCycle = true,
            coilSignalMode = CoilSignalMode.GOING_HIGH,
            crankingAdvanceDeg = 15,
            fixedTimingAngleDeg = 8,
            dwellErrorCorrectionEnabled = true,
            sparkMode = 5,
        )

        val written = edited.toPageData(base)
        val reread = TriggerSettings.fromPageData(written)

        assertEquals(edited, reread.copy(extraFields = edited.extraFields))
        // bytes que a tela não edita continuam idênticos
        for (i in listOf(7, 9, 10, 13, 14, 17, 50, 100, 127)) assertEquals(base[i], written[i], "byte $i")
        // a base original não é alterada
        assertEquals(1.toByte(), base[0])
    }

    @Test
    fun `page 4 writes clear the flags that are off`() {
        val base = ByteArray(128) { 0xFF.toByte() }
        val off = TriggerSettings.fromPageData(ByteArray(128)) // tudo no lado "desligado"
        val written = off.toPageData(base)
        val reread = TriggerSettings.fromPageData(written)
        assertEquals(SignalEdge.RISING, reread.triggerEdge)
        assertEquals(TriggerSpeed.CRANK, reread.primaryTriggerSpeed)
        assertEquals(CoilSignalMode.GOING_LOW, reread.coilSignalMode)
        assertEquals(SignalEdge.RISING, reread.secondaryTriggerEdge)
        assertFalse(reread.reSyncEveryCycle)
        assertFalse(reread.levelForFirstPhaseHigh)
        assertFalse(reread.dwellErrorCorrectionEnabled)
    }

    @Test
    fun `page 4 writes clamp out of range values`() {
        val t = TriggerSettings.fromPageData(page4()).copy(
            triggerAngleDeg = 9999,
            triggerAngleMultiplier = 999,
            fixedTimingAngleDeg = 999,
            crankingAdvanceDeg = 999,
            skipRevolutions = 999,
            primaryBaseTeeth = 999,
            missingTeeth = -4,
        )
        val written = t.toPageData(page4())
        val reread = TriggerSettings.fromPageData(written)
        assertEquals(360, reread.triggerAngleDeg)
        assertEquals(255, reread.triggerAngleMultiplier)
        assertEquals(64, reread.fixedTimingAngleDeg)
        assertEquals(80, reread.crankingAdvanceDeg)
        assertEquals(255, reread.skipRevolutions)
        assertEquals(255, reread.primaryBaseTeeth)
        assertEquals(0, reread.missingTeeth)
        assertEquals(-360, t.copy(triggerAngleDeg = -9999).toPageData(page4()).let { TriggerSettings.fromPageData(it).triggerAngleDeg })
    }

    @Test
    fun `page 4 size is validated`() {
        assertFailsWith<IllegalArgumentException> { TriggerSettings.fromPageData(ByteArray(10)) }
        assertFailsWith<IllegalArgumentException> { TriggerSettings.fromPageData(page4()).toPageData(ByteArray(10)) }
    }

    // ---- rótulos --------------------------------------------------------------------------------------

    @Test
    fun `labels describe the settings`() {
        val base = TriggerSettings.fromPageData(page4())
        assertEquals("Missing Tooth", base.triggerPatternLabel)
        assertEquals("Pattern #99", base.copy(triggerPattern = 99).triggerPatternLabel)
        assertEquals("Crank Speed", base.primaryTriggerSpeedLabel)
        assertEquals("Cam Speed", base.copy(primaryTriggerSpeed = TriggerSpeed.CAM).primaryTriggerSpeedLabel)
        assertEquals("RISING", base.triggerEdgeLabel)
        assertEquals("FALLING", base.copy(triggerEdge = SignalEdge.FALLING).triggerEdgeLabel)
        assertEquals("RISING", base.secondaryTriggerEdgeLabel)
        assertEquals("FALLING", base.copy(secondaryTriggerEdge = SignalEdge.FALLING).secondaryTriggerEdgeLabel)
        assertEquals("Single tooth cam", base.secondaryTriggerTypeLabel)
        assertEquals("Tipo 77", base.copy(secondaryTriggerType = 77).secondaryTriggerTypeLabel)
        assertEquals("Desligado", base.triggerFilterLabel)
        assertEquals("Weak", base.copy(triggerFilter = TriggerFilter.WEAK).triggerFilterLabel)
        assertEquals("Medium", base.copy(triggerFilter = TriggerFilter.MEDIUM).triggerFilterLabel)
        assertEquals("Aggressive", base.copy(triggerFilter = TriggerFilter.AGGRESSIVE).triggerFilterLabel)
    }

    // ---- MS2 ---------------------------------------------------------------------------------------------

    private fun ms2Page() = ByteArray(1024)

    @Test
    fun `ms2 page decode`() {
        val d = ms2Page()
        d[42] = 0xFF.toByte(); d[43] = 0x38.toByte() // -200 -> -20.0 graus
        d[1] = 6 // skip
        d[2] = 0x01 // captura primária RISING
        d[966] = 0x01; d[967] = 0x04 // 260 dentes
        d[968] = 2
        d[988] = (0b0010_1111 or 0).toByte() // nível alto, CAM, config=3, edge=2
        d[989] = 9 // padrão / sparkMode
        d[577] = 0x02 // resync
        d[997] = (3 shl 4).toByte() // filtro agressivo

        val t = TriggerSettings.fromMs2PageData(d)

        assertEquals(-20, t.triggerAngleDeg)
        assertEquals(6, t.skipRevolutions)
        assertEquals(SignalEdge.RISING, t.triggerEdge)
        assertEquals(260, t.primaryBaseTeeth)
        assertEquals(2, t.missingTeeth)
        assertEquals(TriggerSpeed.CAM, t.primaryTriggerSpeed)
        assertEquals(3, t.secondaryTriggerType)
        assertEquals(SignalEdge.FALLING, t.secondaryTriggerEdge)
        assertTrue(t.levelForFirstPhaseHigh)
        assertEquals(9, t.triggerPattern)
        assertTrue(t.reSyncEveryCycle)
        assertEquals(TriggerFilter.AGGRESSIVE, t.triggerFilter)
    }

    @Test
    fun `ms2 filter modes and defaults`() {
        fun filter(bits: Int) = ms2Page().also { it[997] = (bits shl 4).toByte() }.let { TriggerSettings.fromMs2PageData(it).triggerFilter }
        assertEquals(TriggerFilter.OFF, filter(0))
        assertEquals(TriggerFilter.OFF, filter(1))
        assertEquals(TriggerFilter.MEDIUM, filter(2))
        assertEquals(TriggerFilter.AGGRESSIVE, filter(3))
        val zero = TriggerSettings.fromMs2PageData(ms2Page())
        assertEquals(SignalEdge.FALLING, zero.triggerEdge)
        assertEquals(TriggerSpeed.CRANK, zero.primaryTriggerSpeed)
        assertEquals(SignalEdge.FALLING, zero.secondaryTriggerEdge)
        assertFalse(zero.levelForFirstPhaseHigh)
    }

    @Test
    fun `ms2 write then read keeps the settings`() {
        val source = TriggerSettings.fromPageData(page4()).copy(
            triggerAngleDeg = 25,
            skipRevolutions = 3,
            triggerEdge = SignalEdge.RISING,
            primaryBaseTeeth = 36,
            missingTeeth = 1,
            secondaryTriggerType = 2,
            secondaryTriggerEdge = SignalEdge.RISING,
            primaryTriggerSpeed = TriggerSpeed.CAM,
            levelForFirstPhaseHigh = true,
            triggerPattern = 12,
            reSyncEveryCycle = true,
            triggerFilter = TriggerFilter.MEDIUM,
        )
        val written = source.toMs2PageData(ms2Page())
        val t = TriggerSettings.fromMs2PageData(written)
        assertEquals(25, t.triggerAngleDeg)
        assertEquals(3, t.skipRevolutions)
        assertEquals(SignalEdge.RISING, t.triggerEdge)
        assertEquals(36, t.primaryBaseTeeth)
        assertEquals(1, t.missingTeeth)
        assertEquals(2, t.secondaryTriggerType)
        assertEquals(SignalEdge.RISING, t.secondaryTriggerEdge)
        assertEquals(TriggerSpeed.CAM, t.primaryTriggerSpeed)
        assertTrue(t.levelForFirstPhaseHigh)
        assertEquals(12, t.triggerPattern)
        assertTrue(t.reSyncEveryCycle)
        assertEquals(TriggerFilter.MEDIUM, t.triggerFilter)
    }

    @Test
    fun `ms2 write covers the off side of every flag and the filter mapping`() {
        val base = ByteArray(1024) { 0xFF.toByte() }
        val off = TriggerSettings.fromPageData(page4()).copy(
            triggerEdge = SignalEdge.FALLING,
            secondaryTriggerEdge = SignalEdge.FALLING,
            primaryTriggerSpeed = TriggerSpeed.CRANK,
            levelForFirstPhaseHigh = false,
            reSyncEveryCycle = false,
        )
        for ((filter, bits) in mapOf(
            TriggerFilter.OFF to 0, TriggerFilter.WEAK to 2, TriggerFilter.MEDIUM to 2, TriggerFilter.AGGRESSIVE to 3,
        )) {
            val written = off.copy(triggerFilter = filter).toMs2PageData(base)
            assertEquals(bits, (written[997].toInt() shr 4) and 0x03, filter.name)
        }
        val written = off.toMs2PageData(base)
        assertEquals(0, written[2].toInt() and 0x01)
        assertEquals(2, (written[988].toInt() shr 4) and 0x03) // FALLING = 2
        assertEquals(0, written[988].toInt() and 0x03)
        assertEquals(0, written[577].toInt() and 0x02)
    }

    @Test
    fun `ms2 write clamps values and validates the page size`() {
        val t = TriggerSettings.fromPageData(page4()).copy(
            triggerAngleDeg = 5000,
            skipRevolutions = 999,
            primaryBaseTeeth = 999,
            missingTeeth = 9,
            secondaryTriggerType = 9,
            triggerPattern = 99,
        )
        val written = t.toMs2PageData(ms2Page())
        val reread = TriggerSettings.fromMs2PageData(written)
        assertEquals(180, reread.triggerAngleDeg) // 1800 / 10
        assertEquals(255, reread.skipRevolutions)
        assertEquals(255, reread.primaryBaseTeeth)
        assertEquals(4, reread.missingTeeth)
        assertEquals(3, reread.secondaryTriggerType)
        assertEquals(63, reread.triggerPattern)
        assertEquals(-90, t.copy(triggerAngleDeg = -5000).toMs2PageData(ms2Page()).let { TriggerSettings.fromMs2PageData(it).triggerAngleDeg })
        assertFailsWith<IllegalArgumentException> { TriggerSettings.fromMs2PageData(ByteArray(10)) }
        assertFailsWith<IllegalArgumentException> { t.toMs2PageData(ByteArray(10)) }
    }

    // ---- rusEFI ------------------------------------------------------------------------------------------------

    private fun putU32(d: ByteArray, off: Int, value: Int) {
        for (i in 0..3) d[off + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }

    private fun putU16(d: ByteArray, off: Int, value: Int) {
        d[off] = (value and 0xFF).toByte(); d[off + 1] = ((value shr 8) and 0xFF).toByte()
    }

    @Test
    fun `rusefi main page with custom teeth and flags`() {
        val d = ByteArray(1686)
        putU32(d, 488, 0x42340000) // 45.0 graus
        putU32(d, 552, 70) // tipo 36-2-1
        putU32(d, 556, 24) // total custom
        putU32(d, 560, 3) // faltando custom
        putU16(d, 748, 5)
        putU16(d, 750, 0)
        putU32(d, 776, 1 shl 17) // noise filter
        putU32(d, 1356, (1 shl 14) or (1 shl 25)) // secundário FALLING + skipped wheel na cam
        d[1684] = 1; d[1685] = 16

        val t = TriggerSettings.fromRusefiMainPage(d)

        assertEquals(45, t.triggerAngleDeg)
        assertEquals(70, t.triggerPattern)
        assertEquals(24, t.primaryBaseTeeth)
        assertEquals(3, t.missingTeeth)
        assertEquals(TriggerSpeed.CAM, t.primaryTriggerSpeed)
        assertEquals(SignalEdge.FALLING, t.secondaryTriggerEdge)
        assertTrue(t.levelForFirstPhaseHigh)
        assertEquals(TriggerFilter.MEDIUM, t.triggerFilter)
        assertEquals("36-2-1", t.extraFields["rusefi_trigger_type"])
        assertEquals("Input 5", t.extraFields["rusefi_trigger_primary_input"])
        assertEquals("Not assigned", t.extraFields["rusefi_trigger_secondary_input"])
        assertEquals("On camshaft", t.extraFields["rusefi_trigger_skipped_wheel_location"])
        assertEquals("Enabled", t.extraFields["rusefi_trigger_noise_filter"])
        assertEquals("24", t.extraFields["rusefi_trigger_custom_total"])
        assertEquals("3", t.extraFields["rusefi_trigger_custom_missing"])
        assertEquals("Single Tooth", t.extraFields["rusefi_trigger_vvt_mode_1"])
        assertEquals("Honda K Exhaust", t.extraFields["rusefi_trigger_vvt_mode_2"])
    }

    @Test
    fun `rusefi infers teeth from the trigger type when custom values are unset`() {
        val expected = mapOf(8 to (60 to 2), 9 to (36 to 1), 23 to (36 to 2), 48 to (36 to 2), 69 to (32 to 2), 70 to (36 to 2), 71 to (36 to 2), 11 to (0 to 0))
        for ((type, teeth) in expected) {
            val d = ByteArray(1686)
            putU32(d, 552, type)
            putU32(d, 556, 0) // total custom <= 0 -> inferido
            putU32(d, 560, -1) // faltando custom < 0 -> inferido
            val t = TriggerSettings.fromRusefiMainPage(d)
            assertEquals(teeth.first, t.primaryBaseTeeth, "type=$type")
            assertEquals(teeth.second, t.missingTeeth, "type=$type")
            assertEquals("0", t.extraFields["rusefi_trigger_custom_total"])
            assertEquals("0", t.extraFields["rusefi_trigger_custom_missing"])
        }
    }

    @Test
    fun `rusefi trigger and vvt names`() {
        val triggers = mapOf(
            0 to "Custom toothed wheel", 8 to "60-2", 9 to "36-1", 11 to "Single Tooth", 23 to "36-2-2-2",
            48 to "36-2", 57 to "Kawa KX450F", 69 to "32-2", 70 to "36-2-1", 71 to "36-2-1-1", 33 to "Trigger 33",
        )
        for ((index, name) in triggers) {
            val d = ByteArray(1686)
            putU32(d, 552, index)
            assertEquals(name, TriggerSettings.fromRusefiMainPage(d).extraFields["rusefi_trigger_type"], "type=$index")
        }
        val vvt = mapOf(0 to "Inactive", 1 to "Single Tooth", 2 to "Toyota 3 Tooth / 2JZ", 3 to "Miata NB2", 9 to "Nissan VQ", 10 to "Honda K Intake", 16 to "Honda K Exhaust", 5 to "Mode 5")
        for ((mode, name) in vvt) {
            val d = ByteArray(1686)
            d[1684] = mode.toByte()
            assertEquals(name, TriggerSettings.fromRusefiMainPage(d).extraFields["rusefi_trigger_vvt_mode_1"], "mode=$mode")
        }
    }

    @Test
    fun `rusefi f407 discovery uses its own offsets`() {
        val d = ByteArray(1658)
        putU32(d, 484, 0x41200000) // 10.0 graus
        putU32(d, 544, 9)
        putU32(d, 548, 0)
        putU32(d, 552, -1)
        putU16(d, 740, 7)
        putU16(d, 742, 2)
        putU32(d, 768, 1 shl 19)
        putU32(d, 1344, 1 shl 14)
        d[1656] = 2; d[1657] = 3

        val t = TriggerSettings.fromRusefiMainPage(d, schemaId = "rusefi-f407-discovery")

        assertEquals(10, t.triggerAngleDeg)
        assertEquals(9, t.triggerPattern)
        assertEquals(36, t.primaryBaseTeeth)
        assertEquals(1, t.missingTeeth)
        assertEquals(TriggerFilter.MEDIUM, t.triggerFilter)
        assertEquals(SignalEdge.FALLING, t.secondaryTriggerEdge)
        assertEquals(TriggerSpeed.CRANK, t.primaryTriggerSpeed)
        assertEquals("Input 7", t.extraFields["rusefi_trigger_primary_input"])
        assertEquals("Input 2", t.extraFields["rusefi_trigger_secondary_input"])
        assertEquals("On crankshaft", t.extraFields["rusefi_trigger_skipped_wheel_location"])
        assertEquals("Toyota 3 Tooth / 2JZ", t.extraFields["rusefi_trigger_vvt_mode_1"])
        assertEquals("Miata NB2", t.extraFields["rusefi_trigger_vvt_mode_2"])
        assertEquals("Disabled", TriggerSettings.fromRusefiMainPage(ByteArray(1658), "rusefi-f407-discovery").extraFields["rusefi_trigger_noise_filter"])
    }

    @Test
    fun `rusefi page size is validated`() {
        assertFailsWith<IllegalArgumentException> { TriggerSettings.fromRusefiMainPage(ByteArray(100)) }
        assertFailsWith<IllegalArgumentException> { TriggerSettings.fromRusefiMainPage(ByteArray(1685)) }
        assertFailsWith<IllegalArgumentException> { TriggerSettings.fromRusefiMainPage(ByteArray(1657), "rusefi-f407-discovery") }
        assertContentEquals(ByteArray(0), ByteArray(0))
    }
}
