package io.ecucore.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Protege o layout de páginas da Engine Protection (speeduino_202501.ini).
 *
 * Histórico: a página 6 foi tratada como tendo 240 bytes (é a página 7) e campos das páginas
 * 1/4/9/10 eram lidos/gravados nela. Resultado em campo: gravar o offset 166 devolvia RANGE_ERR
 * (0x84) e os offsets 26/120 sobrescreviam outros campos da página 6 (ex.: fanPin).
 */
class EngineProtectionMapperTest {

    private val era = FirmwareEra.MODERN_2025

    private fun config(
        cut: ProtectionCut = ProtectionCut.BOTH,
        rolling: Boolean = true,
        rpmMin: Int = 3000,
        boost: Boolean = true,
        oil: Boolean = true,
        afr: Boolean = true,
        coolant: Boolean = false,
        hardEnabled: Boolean = true,
        hardRpm: Int = 7000,
        softRpm: Int = 6500,
    ) = EngineProtectionConfig(
        protectionCut = cut,
        cutMethod = if (rolling) CutMethod.ROLLING else CutMethod.FULL,
        engineProtectionRpmMin = rpmMin,
        engineProtectEnabled = cut != ProtectionCut.OFF,
        revLimiterEnabled = hardEnabled || coolant,
        boostLimitEnabled = boost,
        oilPressureProtectionEnabled = oil,
        afrProtectionEnabled = afr,
        coolantProtectionEnabled = coolant,
        hardRevLimitEnabled = hardEnabled && !coolant,
        hardRevLimitRpm = hardRpm,
        softRevLimitRpm = softRpm,
        extendedPagesLoaded = true,
    )

    @Test
    fun page6HasThe128BytesOfTheIniNot240() {
        assertEquals(6, EngineProtectionMapper.PAGE_NUMBER)
        assertEquals(128, EngineProtectionMapper.PAGE_SIZE)
        assertEquals(128, ClosedLoopCorrectionMapper.PAGE_SIZE)
    }

    @Test
    fun otherPagesMatchTheIniPageSizes() {
        // pageSize = 128, 288, 288, 128, 288, 128, 240, 384, 192, 192, 288, ... (páginas 1..)
        assertEquals(128, EngineProtectionMapper.CUT_TYPE_PAGE_SIZE) // página 1
        assertEquals(128, EngineProtectionMapper.RPM_PAGE_SIZE) // página 4
        assertEquals(192, EngineProtectionMapper.HARD_REV_MODE_PAGE_SIZE) // página 9
        assertEquals(192, EngineProtectionMapper.OIL_PAGE_SIZE) // página 10
        assertEquals(192, ClosedLoopCorrectionMapper.MAP_WINDOW_PAGE_SIZE)
    }

    @Test
    fun page6WriteTouchesOnlyProtectTypeAndBoostCut() {
        val base = ByteArray(EngineProtectionMapper.PAGE_SIZE) { 0x55.toByte() }

        val updated = EngineProtectionMapper.applyToPage(base, config())

        assertEquals(EngineProtectionMapper.PAGE_SIZE, updated.size)
        val changed = updated.indices.filter { updated[it] != base[it] }
        assertTrue(changed.all { it == 0 || it == 6 }, "só os offsets 0 e 6 podem mudar, mudou: $changed")
        assertEquals(3, (updated[0].toInt() shr 6) and 0b11) // BOTH
        assertEquals(1, (updated[6].toInt() shr 7) and 1)
        // offsets que o mapper antigo corrompia na página 6 (fanPin = 120, byte 26)
        assertEquals(0x55.toByte(), updated[26])
        assertEquals(0x55.toByte(), updated[120])
    }

    @Test
    fun eachFieldIsWrittenToItsOwnPage() {
        val c = config(coolant = true, hardEnabled = false)

        val p1 = EngineProtectionMapper.applyToPage1(ByteArray(128), c)
        val p4 = EngineProtectionMapper.applyToPage4(ByteArray(128), c)
        val p9 = EngineProtectionMapper.applyToPage9(ByteArray(192), c, era)
        val p10 = EngineProtectionMapper.applyToPage10(ByteArray(192), c)

        assertEquals(1, (p1[26].toInt() shr 3) and 1) // hardCutType rolling
        assertEquals(30, p4[120].toInt() and 0xFF) // engineProtectMaxRPM = 3000 / 100
        assertEquals(70, p4[22].toInt() and 0xFF) // hardRevLim
        assertEquals(65, p4[19].toInt() and 0xFF) // SoftRevLim
        assertEquals(2, p9[166].toInt() and 0b11) // hardRevMode = Coolant based
        assertEquals(1, p9[185].toInt() and 0b11) // afrProtectEnabled
        assertEquals(0b110, p10[135].toInt() and 0b110) // oilPressureEnable + ProtEnbl
    }

    @Test
    fun fixedModeAndPreservedAfrTableMode() {
        val fixed = EngineProtectionMapper.applyToPage9(ByteArray(192), config(), era)
        assertEquals(1, fixed[166].toInt() and 0b11)

        val tableMode = ByteArray(192).also { it[185] = 2 }
        val kept = EngineProtectionMapper.applyToPage9(tableMode, config(afr = true), era)
        assertEquals(2, kept[185].toInt() and 0b11, "modo Table já escolhido deve ser preservado")

        val off = EngineProtectionMapper.applyToPage9(tableMode, config(afr = false, hardEnabled = false), era)
        assertEquals(0, off[185].toInt() and 0b11)
        assertEquals(0, off[166].toInt() and 0b11)
    }

    @Test
    fun readsEachFieldFromItsOwnPageAndRoundTrips() {
        val original = config(coolant = true, hardEnabled = false)
        val p6 = EngineProtectionMapper.applyToPage(ByteArray(128), original)
        val p1 = EngineProtectionMapper.applyToPage1(ByteArray(128), original)
        val p4 = EngineProtectionMapper.applyToPage4(ByteArray(128), original)
        val p9 = EngineProtectionMapper.applyToPage9(ByteArray(192), original, era)
        val p10 = EngineProtectionMapper.applyToPage10(ByteArray(192), original)

        val parsed = EngineProtectionMapper.withExtendedPages(EngineProtectionMapper.fromPage(p6, era), era, p1, p4, p9, p10)

        assertEquals(original, parsed)
        assertTrue(parsed.extendedPagesLoaded)
    }

    @Test
    fun page6AloneDoesNotClaimExtendedPages() {
        val parsed = EngineProtectionMapper.fromPage(ByteArray(128), era)
        assertFalse(parsed.extendedPagesLoaded)
        assertEquals(0, parsed.hardRevLimitRpm)
    }

    @Test
    fun page9ProtectionIsSkippedBeforeTheFieldsExist() {
        val base = ByteArray(192) { 0x33.toByte() }
        val updated = EngineProtectionMapper.applyToPage9(base, config(coolant = true), FirmwareEra.MODERN_2020)
        assertContentEquals(base, updated)
        assertFalse(EngineProtectionMapper.hasPage9Protection(FirmwareEra.MODERN_2020))
        assertTrue(EngineProtectionMapper.hasPage9Protection(FirmwareEra.MODERN_2023))
    }

    @Test
    fun valetModeSwitchesCoolantProtectionToFixedLimit() {
        val valet = config(coolant = true, hardEnabled = false).asValetMode()
        val p9 = EngineProtectionMapper.applyToPage9(ByteArray(192), valet, era)
        assertEquals(1, p9[166].toInt() and 0b11)
    }
}
