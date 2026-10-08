package io.ecucore.model

/**
 * Validação de faixa das configurações de página (trigger, proteção do motor, calibrações).
 *
 * Os serializadores (`toPageData`, `applyToPage`, `writeU8`…) fazem `coerceIn` em silêncio: um valor
 * fora da faixa viraria outro valor na ECU sem o usuário saber. Aqui o valor é rejeitado antes.
 * Faixas do speeduino.ini 202501 (page 4, 6 e 1). Ver docs/HARA.md (H4–H6).
 */
object ConfigValidator {

    const val TRIGGER_ANGLE_MIN = -360
    const val TRIGGER_ANGLE_MAX = 360
    const val FIXED_ANGLE_MIN = -64
    const val FIXED_ANGLE_MAX = 64
    const val CRANKING_ADVANCE_MIN = -10
    const val CRANKING_ADVANCE_MAX = 80
    const val CRANKING_ADVANCE_WARNING = 30
    const val TRIGGER_ANGLE_MULTIPLIER_MAX = 88
    const val MS2_TRIGGER_ANGLE_MIN = -90
    const val MS2_TRIGGER_ANGLE_MAX = 180
    const val MS2_MISSING_TEETH_MAX = 4
    const val PROTECTION_RPM_MIN = 100
    const val PROTECTION_RPM_MAX = 25500
    const val PROTECTION_RPM_STEP = 100
    const val TPS_ADC_MAX = 255
    const val TPS_MIN_SPAN_WARNING = 50
    const val PRESSURE_MIN_KPA = -100
    const val PRESSURE_MIN_MAX_KPA = 127
    const val PRESSURE_MAX_KPA = 25500

    fun validateTrigger(settings: TriggerSettings, ms2: Boolean = false): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val angleRange = if (ms2) MS2_TRIGGER_ANGLE_MIN..MS2_TRIGGER_ANGLE_MAX else TRIGGER_ANGLE_MIN..TRIGGER_ANGLE_MAX
        rangeError(errors, "Trigger angle", settings.triggerAngleDeg, angleRange, "°")
        rangeError(errors, "Skip revolutions", settings.skipRevolutions, 0..255)
        rangeError(errors, "Primary base teeth", settings.primaryBaseTeeth, 0..255)
        if (ms2) {
            rangeError(errors, "Missing teeth", settings.missingTeeth, 0..MS2_MISSING_TEETH_MAX)
        } else {
            rangeError(errors, "Missing teeth", settings.missingTeeth, 0..255)
            rangeError(errors, "Fixed timing angle", settings.fixedTimingAngleDeg, FIXED_ANGLE_MIN..FIXED_ANGLE_MAX, "°")
            rangeError(errors, "Cranking advance", settings.crankingAdvanceDeg, CRANKING_ADVANCE_MIN..CRANKING_ADVANCE_MAX, "°")
            rangeError(errors, "Trigger angle multiplier", settings.triggerAngleMultiplier, 0..TRIGGER_ANGLE_MULTIPLIER_MAX)
            rangeError(errors, "Trigger pattern", settings.triggerPattern, 0..31)
            if (settings.crankingAdvanceDeg in (CRANKING_ADVANCE_WARNING + 1)..CRANKING_ADVANCE_MAX) {
                warnings.add("⚠️  Cranking advance of ${settings.crankingAdvanceDeg}° is high - risk of starter kickback")
            }
        }

        // Missing Tooth (padrão 0): roda precisa ter mais dentes que dentes faltando.
        val isMissingTooth = !ms2 && settings.triggerPattern == 0
        if (isMissingTooth && settings.primaryBaseTeeth > 0 && settings.missingTeeth >= settings.primaryBaseTeeth) {
            errors.add("Missing teeth (${settings.missingTeeth}) must be fewer than base teeth (${settings.primaryBaseTeeth})")
        }
        if (isMissingTooth && settings.primaryBaseTeeth == 0) {
            errors.add("Missing Tooth pattern requires base teeth > 0")
        }
        return result(errors, warnings)
    }

    fun validateEngineProtection(config: EngineProtectionConfig): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (config.engineProtectEnabled) {
            rangeError(errors, "Engine protection RPM", config.engineProtectionRpmMin, PROTECTION_RPM_MIN..PROTECTION_RPM_MAX, " rpm")
            if (config.engineProtectionRpmMin % PROTECTION_RPM_STEP != 0) {
                warnings.add("⚠️  Engine protection RPM ${config.engineProtectionRpmMin} will be truncated to a multiple of $PROTECTION_RPM_STEP")
            }
            if (config.protectionCut == ProtectionCut.OFF) {
                warnings.add("⚠️  Engine protection is enabled but cut type is OFF - nothing will be cut")
            }
        } else if (
            config.boostLimitEnabled || config.oilPressureProtectionEnabled ||
            config.afrProtectionEnabled || config.coolantProtectionEnabled
        ) {
            warnings.add("⚠️  Engine protection is disabled - boost/oil/AFR/coolant protections will have no effect")
        }
        return result(errors, warnings)
    }

    fun validateTpsCalibration(calibration: TpsCalibration): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        rangeError(errors, "TPS min", calibration.tpsMin, 0..TPS_ADC_MAX, " ADC")
        rangeError(errors, "TPS max", calibration.tpsMax, 0..TPS_ADC_MAX, " ADC")
        if (calibration.tpsMin >= calibration.tpsMax) {
            errors.add("TPS min (${calibration.tpsMin}) must be lower than TPS max (${calibration.tpsMax})")
        } else if (calibration.tpsMax - calibration.tpsMin < TPS_MIN_SPAN_WARNING) {
            warnings.add("⚠️  TPS calibration span is only ${calibration.tpsMax - calibration.tpsMin} ADC counts - check the sensor")
        }
        return result(errors, warnings)
    }

    fun validatePressureCalibration(calibration: PressureCalibration): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        pressurePair(errors, "MAP", calibration.mapMin, calibration.mapMax)
        pressurePair(errors, "Baro", calibration.baroMin, calibration.baroMax)
        // EMAP é opcional: 0/0 = sensor não usado, não é erro.
        if (calibration.emapMin != 0 || calibration.emapMax != 0) {
            pressurePair(errors, "EMAP", calibration.emapMin, calibration.emapMax)
        }
        return result(errors, warnings)
    }

    private fun pressurePair(errors: MutableList<String>, name: String, min: Int, max: Int) {
        rangeError(errors, "$name min", min, PRESSURE_MIN_KPA..PRESSURE_MIN_MAX_KPA, " kPa")
        rangeError(errors, "$name max", max, 0..PRESSURE_MAX_KPA, " kPa")
        if (min >= max) errors.add("$name min ($min) must be lower than $name max ($max)")
    }

    private fun rangeError(errors: MutableList<String>, name: String, value: Int, range: IntRange, unit: String = "") {
        if (value !in range) {
            errors.add("$name out of range: $value$unit (valid: ${range.first}..${range.last}$unit)")
        }
    }

    private fun result(errors: List<String>, warnings: List<String>) =
        ValidationResult(isValid = errors.isEmpty(), errors = errors, warnings = warnings)

    /** Lança [ValidationException] se houver erros; avisos não bloqueiam. */
    fun requireValid(result: ValidationResult) {
        if (!result.isValid) throw ValidationException(result)
    }
}
