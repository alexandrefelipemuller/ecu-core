package io.ecucore.model

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigValidatorTest {

    private fun trigger(
        angle: Int = 0,
        teeth: Int = 36,
        missing: Int = 1,
        pattern: Int = 0,
        crankingAdvance: Int = 10,
        fixed: Int = 10,
        multiplier: Int = 1,
    ) = TriggerSettings(
        triggerAngleDeg = angle,
        triggerAngleMultiplier = multiplier,
        triggerPattern = pattern,
        primaryBaseTeeth = teeth,
        missingTeeth = missing,
        primaryTriggerSpeed = TriggerSettings.TriggerSpeed.CRANK,
        triggerEdge = TriggerSettings.SignalEdge.RISING,
        secondaryTriggerEdge = TriggerSettings.SignalEdge.RISING,
        secondaryTriggerType = 0,
        levelForFirstPhaseHigh = false,
        skipRevolutions = 0,
        triggerFilter = TriggerSettings.TriggerFilter.OFF,
        reSyncEveryCycle = false,
        crankingAdvanceDeg = crankingAdvance,
        fixedTimingAngleDeg = fixed,
    )

    @Test
    fun validTriggerPasses() {
        assertTrue(ConfigValidator.validateTrigger(trigger()).isValid)
    }

    @Test
    fun triggerAngleOutOfRangeIsRejectedInsteadOfClamped() {
        assertFalse(ConfigValidator.validateTrigger(trigger(angle = 361)).isValid)
        assertFalse(ConfigValidator.validateTrigger(trigger(angle = -361)).isValid)
        assertTrue(ConfigValidator.validateTrigger(trigger(angle = 360)).isValid)
    }

    @Test
    fun crankingAndFixedAngleLimits() {
        assertFalse(ConfigValidator.validateTrigger(trigger(crankingAdvance = 81)).isValid)
        assertFalse(ConfigValidator.validateTrigger(trigger(crankingAdvance = -11)).isValid)
        assertFalse(ConfigValidator.validateTrigger(trigger(fixed = 65)).isValid)
        val warned = ConfigValidator.validateTrigger(trigger(crankingAdvance = 45))
        assertTrue(warned.isValid)
        assertTrue(warned.warnings.any { it.contains("kickback") })
    }

    @Test
    fun missingToothNeedsMoreTeethThanMissing() {
        assertFalse(ConfigValidator.validateTrigger(trigger(teeth = 2, missing = 2)).isValid)
        assertFalse(ConfigValidator.validateTrigger(trigger(teeth = 0, missing = 0)).isValid)
        // Outros padrões não usam essa relação
        assertTrue(ConfigValidator.validateTrigger(trigger(pattern = 1, teeth = 0, missing = 0)).isValid)
    }

    @Test
    fun ms2TriggerUsesItsOwnLimits() {
        assertTrue(ConfigValidator.validateTrigger(trigger(angle = 120, missing = 2), ms2 = true).isValid)
        assertFalse(ConfigValidator.validateTrigger(trigger(angle = 200), ms2 = true).isValid)
        assertFalse(ConfigValidator.validateTrigger(trigger(missing = 5), ms2 = true).isValid)
    }

    private fun protection(
        enabled: Boolean = true,
        rpm: Int = 7000,
        cut: ProtectionCut = ProtectionCut.BOTH,
        oil: Boolean = false,
    ) = EngineProtectionConfig(
        protectionCut = cut,
        cutMethod = CutMethod.FULL,
        engineProtectionRpmMin = rpm,
        engineProtectEnabled = enabled,
        revLimiterEnabled = false,
        boostLimitEnabled = false,
        oilPressureProtectionEnabled = oil,
        afrProtectionEnabled = false,
        coolantProtectionEnabled = false,
    )

    @Test
    fun protectionRpmMustBeInRangeWhenEnabled() {
        assertTrue(ConfigValidator.validateEngineProtection(protection(rpm = 7000)).isValid)
        assertFalse(ConfigValidator.validateEngineProtection(protection(rpm = 30000)).isValid)
        assertFalse(ConfigValidator.validateEngineProtection(protection(rpm = 0)).isValid)
        assertTrue(ConfigValidator.validateEngineProtection(protection(enabled = false, rpm = 0)).isValid)
    }

    @Test
    fun protectionInconsistenciesWarn() {
        val truncated = ConfigValidator.validateEngineProtection(protection(rpm = 7050))
        assertTrue(truncated.isValid && truncated.warnings.any { it.contains("truncated") })

        val noCut = ConfigValidator.validateEngineProtection(protection(cut = ProtectionCut.OFF))
        assertTrue(noCut.isValid && noCut.warnings.any { it.contains("nothing will be cut") })

        val disabledButSub = ConfigValidator.validateEngineProtection(protection(enabled = false, oil = true))
        assertTrue(disabledButSub.isValid && disabledButSub.warnings.any { it.contains("no effect") })
    }

    @Test
    fun tpsCalibrationRules() {
        assertTrue(ConfigValidator.validateTpsCalibration(TpsCalibration(20, 230)).isValid)
        assertFalse(ConfigValidator.validateTpsCalibration(TpsCalibration(200, 100)).isValid)
        assertFalse(ConfigValidator.validateTpsCalibration(TpsCalibration(100, 100)).isValid)
        assertFalse(ConfigValidator.validateTpsCalibration(TpsCalibration(0, 300)).isValid)
        val narrow = ConfigValidator.validateTpsCalibration(TpsCalibration(100, 120))
        assertTrue(narrow.isValid && narrow.warnings.isNotEmpty())
    }

    @Test
    fun pressureCalibrationRules() {
        assertTrue(ConfigValidator.validatePressureCalibration(PressureCalibration(10, 260, 10, 260, 0, 0)).isValid)
        assertFalse(ConfigValidator.validatePressureCalibration(PressureCalibration(10, 5, 10, 260, 0, 0)).isValid)
        assertFalse(ConfigValidator.validatePressureCalibration(PressureCalibration(-101, 260, 10, 260, 0, 0)).isValid)
        assertFalse(ConfigValidator.validatePressureCalibration(PressureCalibration(10, 260, 10, 260, 50, 40)).isValid)
    }

    @Test
    fun requireValidThrowsOnlyOnErrors() {
        ConfigValidator.requireValid(ConfigValidator.validateTpsCalibration(TpsCalibration(20, 230)))
        assertFailsWith<ValidationException> {
            ConfigValidator.requireValid(ConfigValidator.validateTpsCalibration(TpsCalibration(200, 100)))
        }
    }
}
