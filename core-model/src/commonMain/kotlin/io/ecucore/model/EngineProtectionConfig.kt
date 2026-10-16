package io.ecucore.model

enum class ProtectionCut {
    OFF,
    SPARK_ONLY,
    FUEL_ONLY,
    BOTH
}

enum class CutMethod {
    FULL,
    ROLLING
}

data class EngineProtectionConfig(
    val protectionCut: ProtectionCut,
    val cutMethod: CutMethod,
    val engineProtectionRpmMin: Int,
    val engineProtectEnabled: Boolean,
    val revLimiterEnabled: Boolean,
    val boostLimitEnabled: Boolean,
    val oilPressureProtectionEnabled: Boolean,
    val afrProtectionEnabled: Boolean,
    val coolantProtectionEnabled: Boolean,
    // Fixed hard/soft rev limiter (pages 4 and 9). 0 = not read from the ECU.
    val hardRevLimitEnabled: Boolean = false,
    val hardRevLimitRpm: Int = 0,
    val softRevLimitRpm: Int = 0,
)

const val VALET_MODE_RPM_DEFAULT = 4000
const val VALET_MODE_RPM_MIN = 2000
const val VALET_MODE_RPM_MAX = 5000
private const val VALET_SOFT_RPM_OFFSET = 500 // soft cut starts this many RPM before the hard cut

fun EngineProtectionConfig.isValetMode(): Boolean =
    engineProtectEnabled &&
        hardRevLimitEnabled &&
        hardRevLimitRpm in VALET_MODE_RPM_MIN..VALET_MODE_RPM_MAX

fun EngineProtectionConfig.asValetMode(targetRpm: Int = VALET_MODE_RPM_DEFAULT): EngineProtectionConfig {
    val clampedRpm = targetRpm.coerceIn(VALET_MODE_RPM_MIN, VALET_MODE_RPM_MAX)
    return copy(
        protectionCut = ProtectionCut.SPARK_ONLY,
        cutMethod = CutMethod.ROLLING,
        engineProtectEnabled = true,
        hardRevLimitEnabled = true,
        hardRevLimitRpm = clampedRpm,
        softRevLimitRpm = clampedRpm - VALET_SOFT_RPM_OFFSET,
    )
}

object EngineProtectionMapper {
    const val PAGE_NUMBER = 6
    const val PAGE_SIZE = 240

    private const val ENGINE_PROTECT_TYPE_OFFSET = 0
    private const val BOOST_CUT_ENABLED_OFFSET = 6
    private const val HARD_CUT_TYPE_OFFSET = 26
    private const val ENGINE_PROTECT_MAX_RPM_OFFSET = 120
    private const val OIL_PRESSURE_FLAGS_OFFSET = 135
    private const val HARD_REV_MODE_OFFSET = 166
    private const val AFR_PROTECT_ENABLED_OFFSET = 185

    // Rev-limiter pages (offsets from speeduino_202501.6.ini): page 1 = hardCutType/SoftLimitMode,
    // page 4 = SoftRevLim/SoftLimRetard/SoftLimMax/hardRevLim, page 9 = hardRevMode.
    const val CUT_TYPE_PAGE_NUMBER = 1
    const val CUT_TYPE_PAGE_SIZE = 128
    const val RPM_PAGE_NUMBER = 4
    const val RPM_PAGE_SIZE = 128
    const val HARD_REV_MODE_PAGE_NUMBER = 9
    const val HARD_REV_MODE_PAGE_SIZE = 192

    private const val SOFT_LIMIT_MODE_OFFSET = 3
    private const val SOFT_REV_LIM_OFFSET = 19
    private const val SOFT_LIM_RETARD_OFFSET = 20
    private const val SOFT_LIM_MAX_OFFSET = 21
    private const val HARD_REV_LIM_OFFSET = 22
    private const val RPM_PAGE_MAX_RPM_OFFSET = 120
    private const val VALET_SOFT_RETARD_DEG = 15
    private const val VALET_SOFT_MAX_TIME = 20 // 2.0 s (0.1 s scale)

    /** Merges the rev-limiter fields read from pages 1, 4 and 9 into a config parsed from page 6. */
    fun withRevLimiterPages(
        base: EngineProtectionConfig,
        page1: ByteArray,
        page4: ByteArray,
        page9: ByteArray,
    ): EngineProtectionConfig = base.copy(
        cutMethod = if (readBits(page1, HARD_CUT_TYPE_OFFSET, 3, 1) == 1) CutMethod.ROLLING else CutMethod.FULL,
        hardRevLimitEnabled = readBits(page9, HARD_REV_MODE_OFFSET, 0, 2) == 1,
        hardRevLimitRpm = readU8(page4, HARD_REV_LIM_OFFSET) * 100,
        softRevLimitRpm = readU8(page4, SOFT_REV_LIM_OFFSET) * 100,
    )

    fun applyToPage1(base: ByteArray, config: EngineProtectionConfig): ByteArray {
        val page = base.copyOf()
        writeBits(page, HARD_CUT_TYPE_OFFSET, 3, 1, if (config.cutMethod == CutMethod.ROLLING) 1 else 0)
        // SoftLimitMode 1 = Relative (retards by an amount), smoother than Fixed
        if (config.softRevLimitRpm > 0) writeBits(page, SOFT_LIMIT_MODE_OFFSET, 3, 1, 1)
        return page
    }

    fun applyToPage4(base: ByteArray, config: EngineProtectionConfig): ByteArray {
        val page = base.copyOf()
        writeU8(page, RPM_PAGE_MAX_RPM_OFFSET, (config.engineProtectionRpmMin / 100).coerceIn(0, 255))
        writeU8(page, HARD_REV_LIM_OFFSET, (config.hardRevLimitRpm / 100).coerceIn(0, 255))
        writeU8(page, SOFT_REV_LIM_OFFSET, (config.softRevLimitRpm / 100).coerceIn(0, 255))
        if (config.isValetMode()) {
            writeU8(page, SOFT_LIM_RETARD_OFFSET, VALET_SOFT_RETARD_DEG)
            writeU8(page, SOFT_LIM_MAX_OFFSET, VALET_SOFT_MAX_TIME)
        }
        return page
    }

    fun applyToPage9(base: ByteArray, config: EngineProtectionConfig): ByteArray {
        val page = base.copyOf()
        // 0 = Off, 1 = Fixed, 2 = Coolant based; a Coolant-based mode already set is preserved
        val currentMode = readBits(page, HARD_REV_MODE_OFFSET, 0, 2)
        val newMode = when {
            config.hardRevLimitEnabled -> 1
            currentMode == 2 -> 2
            else -> 0
        }
        writeBits(page, HARD_REV_MODE_OFFSET, 0, 2, newMode)
        return page
    }

    fun fromPage(page: ByteArray, era: FirmwareEra): EngineProtectionConfig {
        val engineProtectType = readBits(page, ENGINE_PROTECT_TYPE_OFFSET, 6, 2)
        val protectionCut = when (engineProtectType) {
            1 -> ProtectionCut.SPARK_ONLY
            2 -> ProtectionCut.FUEL_ONLY
            3 -> ProtectionCut.BOTH
            else -> ProtectionCut.OFF
        }
        val engineProtectEnabled = protectionCut != ProtectionCut.OFF

        val hardCutType = readBits(page, HARD_CUT_TYPE_OFFSET, 3, 1)
        val cutMethod = if (hardCutType == 1) CutMethod.ROLLING else CutMethod.FULL

        val rpmRaw = readU8(page, ENGINE_PROTECT_MAX_RPM_OFFSET)
        val rpmMin = rpmRaw * 100

        val boostLimitEnabled = readBits(page, BOOST_CUT_ENABLED_OFFSET, 7, 1) == 1

        val oilEnable = readBits(page, OIL_PRESSURE_FLAGS_OFFSET, 1, 1) == 1
        val oilProtect = readBits(page, OIL_PRESSURE_FLAGS_OFFSET, 2, 1) == 1
        val oilPressureProtectionEnabled = oilEnable && oilProtect

        val hardRevMode = if (page.size > HARD_REV_MODE_OFFSET && era.isModern()) {
            readBits(page, HARD_REV_MODE_OFFSET, 0, 2)
        } else {
            0
        }
        val revLimiterEnabled = hardRevMode != 0
        val coolantProtectionEnabled = hardRevMode == 2

        val afrProtectEnabled = if (page.size > AFR_PROTECT_ENABLED_OFFSET && era.isModern()) {
            readBits(page, AFR_PROTECT_ENABLED_OFFSET, 0, 2) > 0
        } else {
            false
        }

        return EngineProtectionConfig(
            protectionCut = protectionCut,
            cutMethod = cutMethod,
            engineProtectionRpmMin = rpmMin,
            engineProtectEnabled = engineProtectEnabled,
            revLimiterEnabled = revLimiterEnabled,
            boostLimitEnabled = boostLimitEnabled,
            oilPressureProtectionEnabled = oilPressureProtectionEnabled,
            afrProtectionEnabled = afrProtectEnabled,
            coolantProtectionEnabled = coolantProtectionEnabled,
        )
    }

    fun applyToPage(
        base: ByteArray,
        config: EngineProtectionConfig,
        era: FirmwareEra
    ): ByteArray {
        val page = base.copyOf()

        val protectTypeValue = if (!config.engineProtectEnabled) {
            0
        } else {
            when (config.protectionCut) {
                ProtectionCut.SPARK_ONLY -> 1
                ProtectionCut.FUEL_ONLY -> 2
                ProtectionCut.BOTH -> 3
                ProtectionCut.OFF -> 0
            }
        }
        writeBits(page, ENGINE_PROTECT_TYPE_OFFSET, 6, 2, protectTypeValue)

        val hardCutValue = if (config.cutMethod == CutMethod.ROLLING) 1 else 0
        writeBits(page, HARD_CUT_TYPE_OFFSET, 3, 1, hardCutValue)

        val rpmRaw = (config.engineProtectionRpmMin / 100).coerceIn(0, 255)
        writeU8(page, ENGINE_PROTECT_MAX_RPM_OFFSET, rpmRaw)

        writeBits(page, BOOST_CUT_ENABLED_OFFSET, 7, 1, if (config.boostLimitEnabled) 1 else 0)

        val oilEnabledValue = if (config.oilPressureProtectionEnabled) 1 else 0
        writeBits(page, OIL_PRESSURE_FLAGS_OFFSET, 1, 1, oilEnabledValue)
        writeBits(page, OIL_PRESSURE_FLAGS_OFFSET, 2, 1, oilEnabledValue)

        if (page.size > HARD_REV_MODE_OFFSET && era.isModern()) {
            val hardRevMode = when {
                config.coolantProtectionEnabled -> 2
                config.revLimiterEnabled -> 1
                else -> 0
            }
            writeBits(page, HARD_REV_MODE_OFFSET, 0, 2, hardRevMode)
        }

        if (page.size > AFR_PROTECT_ENABLED_OFFSET && era.isModern()) {
            val afrMode = if (config.afrProtectionEnabled) 1 else 0
            writeBits(page, AFR_PROTECT_ENABLED_OFFSET, 0, 2, afrMode)
        }

        return page
    }

    private fun readU8(page: ByteArray, offset: Int): Int {
        return page.getOrNull(offset)?.toInt()?.and(0xFF) ?: 0
    }

    private fun writeU8(page: ByteArray, offset: Int, value: Int) {
        if (offset >= page.size) return
        page[offset] = (value and 0xFF).toByte()
    }

    private fun readBits(page: ByteArray, offset: Int, shift: Int, width: Int): Int {
        if (offset >= page.size) return 0
        val mask = (1 shl width) - 1
        return (page[offset].toInt() shr shift) and mask
    }

    private fun writeBits(page: ByteArray, offset: Int, shift: Int, width: Int, value: Int) {
        if (offset >= page.size) return
        val mask = ((1 shl width) - 1) shl shift
        val current = page[offset].toInt() and 0xFF
        val updated = (current and mask.inv()) or ((value shl shift) and mask)
        page[offset] = (updated and 0xFF).toByte()
    }
}
