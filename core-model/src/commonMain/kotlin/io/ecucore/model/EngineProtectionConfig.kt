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
    // true quando as páginas 1/4/9/10 foram lidas da ECU. Sem isso a config só conhece a página 6,
    // e gravar as demais zeraria campos que o app nunca viu.
    val extendedPagesLoaded: Boolean = false,
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
        coolantProtectionEnabled = false, // valet usa o limite fixo (hardRevMode = Fixed)
        hardRevLimitRpm = clampedRpm,
        softRevLimitRpm = clampedRpm - VALET_SOFT_RPM_OFFSET,
    )
}

/**
 * Mapa dos campos de "Engine Protection" nas páginas do Speeduino (offsets de speeduino_202501.ini,
 * conferidos nos .ini 202207..202501).
 *
 * ⚠️ ARMADILHA RECORRENTE: a página 6 tem **128 bytes**, não 240 (240 é a página 7). Só
 * `engineProtectType` e `boostCutEnabled` moram nela. Os demais campos estão em outras páginas;
 * gravar o offset na página errada ou é rejeitado (0x84 RANGE_ERR, offset >= 128) ou sobrescreve
 * outro campo (ex.: página 6 offset 120 = fanPin). Por isso cada campo tem a sua página aqui e o
 * teste `EngineProtectionMapperTest` confere offset/tamanho contra o .ini.
 */
object EngineProtectionMapper {
    // Página 6: engineProtectType (0, bits 6-7) e boostCutEnabled (6, bit 7).
    const val PAGE_NUMBER = 6
    const val PAGE_SIZE = 128

    private const val ENGINE_PROTECT_TYPE_OFFSET = 0
    private const val BOOST_CUT_ENABLED_OFFSET = 6

    // Página 1: hardCutType.
    const val CUT_TYPE_PAGE_NUMBER = 1
    const val CUT_TYPE_PAGE_SIZE = 128
    private const val HARD_CUT_TYPE_OFFSET = 26
    private const val SOFT_LIMIT_MODE_OFFSET = 3

    // Página 4: engineProtectMaxRPM, SoftRevLim/SoftLimRetard/SoftLimMax/hardRevLim.
    const val RPM_PAGE_NUMBER = 4
    const val RPM_PAGE_SIZE = 128
    private const val SOFT_REV_LIM_OFFSET = 19
    private const val SOFT_LIM_RETARD_OFFSET = 20
    private const val SOFT_LIM_MAX_OFFSET = 21
    private const val HARD_REV_LIM_OFFSET = 22
    private const val ENGINE_PROTECT_MAX_RPM_OFFSET = 120

    // Página 9: hardRevMode (166, bits 0-1) e afrProtectEnabled (185, bits 0-1) - 202207+.
    const val HARD_REV_MODE_PAGE_NUMBER = 9
    const val HARD_REV_MODE_PAGE_SIZE = 192
    private const val HARD_REV_MODE_OFFSET = 166
    private const val AFR_PROTECT_ENABLED_OFFSET = 185

    // Página 10: oilPressureEnable (135, bit 1) e oilPressureProtEnbl (135, bit 2).
    const val OIL_PAGE_NUMBER = 10
    const val OIL_PAGE_SIZE = 192
    private const val OIL_PRESSURE_FLAGS_OFFSET = 135

    private const val VALET_SOFT_RETARD_DEG = 15
    private const val VALET_SOFT_MAX_TIME = 20 // 2.0 s (0.1 s scale)

    /** hardRevMode/afrProtectEnabled só existem na página 9 a partir do 202207 (era MODERN_2022 em diante). */
    fun hasPage9Protection(era: FirmwareEra): Boolean = era.isModern() && era != FirmwareEra.MODERN_2020

    /** Campos da página 6 (cut type e boost cut). O resto vem de [withExtendedPages]. */
    fun fromPage(page: ByteArray, @Suppress("UNUSED_PARAMETER") era: FirmwareEra): EngineProtectionConfig {
        val protectionCut = when (readBits(page, ENGINE_PROTECT_TYPE_OFFSET, 6, 2)) {
            1 -> ProtectionCut.SPARK_ONLY
            2 -> ProtectionCut.FUEL_ONLY
            3 -> ProtectionCut.BOTH
            else -> ProtectionCut.OFF
        }
        return EngineProtectionConfig(
            protectionCut = protectionCut,
            cutMethod = CutMethod.FULL,
            engineProtectionRpmMin = 0,
            engineProtectEnabled = protectionCut != ProtectionCut.OFF,
            revLimiterEnabled = false,
            boostLimitEnabled = readBits(page, BOOST_CUT_ENABLED_OFFSET, 7, 1) == 1,
            oilPressureProtectionEnabled = false,
            afrProtectionEnabled = false,
            coolantProtectionEnabled = false,
        )
    }

    /** Completa a config da página 6 com os campos das páginas 1, 4, 9 e 10. */
    fun withExtendedPages(
        base: EngineProtectionConfig,
        era: FirmwareEra,
        page1: ByteArray,
        page4: ByteArray,
        page9: ByteArray,
        page10: ByteArray,
    ): EngineProtectionConfig {
        val hardRevMode = if (hasPage9Protection(era)) readBits(page9, HARD_REV_MODE_OFFSET, 0, 2) else 0
        val afrMode = if (hasPage9Protection(era)) readBits(page9, AFR_PROTECT_ENABLED_OFFSET, 0, 2) else 0
        val oilEnable = readBits(page10, OIL_PRESSURE_FLAGS_OFFSET, 1, 1) == 1
        val oilProtect = readBits(page10, OIL_PRESSURE_FLAGS_OFFSET, 2, 1) == 1
        return base.copy(
            cutMethod = if (readBits(page1, HARD_CUT_TYPE_OFFSET, 3, 1) == 1) CutMethod.ROLLING else CutMethod.FULL,
            engineProtectionRpmMin = readU8(page4, ENGINE_PROTECT_MAX_RPM_OFFSET) * 100,
            revLimiterEnabled = hardRevMode != 0,
            coolantProtectionEnabled = hardRevMode == 2,
            afrProtectionEnabled = afrMode > 0,
            oilPressureProtectionEnabled = oilEnable && oilProtect,
            hardRevLimitEnabled = hardRevMode == 1,
            hardRevLimitRpm = readU8(page4, HARD_REV_LIM_OFFSET) * 100,
            softRevLimitRpm = readU8(page4, SOFT_REV_LIM_OFFSET) * 100,
            extendedPagesLoaded = true,
        )
    }

    /** Página 6: apenas engineProtectType e boostCutEnabled. */
    fun applyToPage(base: ByteArray, config: EngineProtectionConfig): ByteArray {
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
        writeBits(page, BOOST_CUT_ENABLED_OFFSET, 7, 1, if (config.boostLimitEnabled) 1 else 0)
        return page
    }

    fun applyToPage1(base: ByteArray, config: EngineProtectionConfig): ByteArray {
        val page = base.copyOf()
        writeBits(page, HARD_CUT_TYPE_OFFSET, 3, 1, if (config.cutMethod == CutMethod.ROLLING) 1 else 0)
        // SoftLimitMode 1 = Relative (retards by an amount), smoother than Fixed
        if (config.softRevLimitRpm > 0) writeBits(page, SOFT_LIMIT_MODE_OFFSET, 3, 1, 1)
        return page
    }

    fun applyToPage4(base: ByteArray, config: EngineProtectionConfig): ByteArray {
        val page = base.copyOf()
        writeU8(page, ENGINE_PROTECT_MAX_RPM_OFFSET, (config.engineProtectionRpmMin / 100).coerceIn(0, 255))
        writeU8(page, HARD_REV_LIM_OFFSET, (config.hardRevLimitRpm / 100).coerceIn(0, 255))
        writeU8(page, SOFT_REV_LIM_OFFSET, (config.softRevLimitRpm / 100).coerceIn(0, 255))
        if (config.isValetMode()) {
            writeU8(page, SOFT_LIM_RETARD_OFFSET, VALET_SOFT_RETARD_DEG)
            writeU8(page, SOFT_LIM_MAX_OFFSET, VALET_SOFT_MAX_TIME)
        }
        return page
    }

    /** Página 9: hardRevMode (0 Off, 1 Fixed, 2 Coolant) e afrProtectEnabled (0 Off, 1 Fixed, 2 Table). */
    fun applyToPage9(base: ByteArray, config: EngineProtectionConfig, era: FirmwareEra): ByteArray {
        val page = base.copyOf()
        if (!hasPage9Protection(era)) return page
        val newMode = when {
            config.coolantProtectionEnabled -> 2
            config.hardRevLimitEnabled || config.revLimiterEnabled -> 1
            else -> 0
        }
        writeBits(page, HARD_REV_MODE_OFFSET, 0, 2, newMode)
        val currentAfr = readBits(page, AFR_PROTECT_ENABLED_OFFSET, 0, 2)
        val newAfr = when {
            !config.afrProtectionEnabled -> 0
            currentAfr in 1..2 -> currentAfr // preserva Fixed/Table já escolhido
            else -> 1
        }
        writeBits(page, AFR_PROTECT_ENABLED_OFFSET, 0, 2, newAfr)
        return page
    }

    /** Página 10: oilPressureEnable + oilPressureProtEnbl. */
    fun applyToPage10(base: ByteArray, config: EngineProtectionConfig): ByteArray {
        val page = base.copyOf()
        val value = if (config.oilPressureProtectionEnabled) 1 else 0
        writeBits(page, OIL_PRESSURE_FLAGS_OFFSET, 1, 1, value)
        writeBits(page, OIL_PRESSURE_FLAGS_OFFSET, 2, 1, value)
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
