package com.speeduino.manager

import kotlin.math.roundToInt

object DashboardSignalThresholds {
    const val MAX_TPS_PERCENT = 100.0
    const val MAX_VALID_GEAR = 10
    const val OIL_PRESSURE_SENSOR_SATURATION_PSI = 255.0
    const val LOW_OIL_PRESSURE_RUNNING_PSI = 7.0
    const val LOW_OIL_PRESSURE_MIN_RPM = 500
    const val HIGH_COOLANT_C = 110.0
    const val LOW_BATTERY_VOLTAGE = 11.0
    const val HIGH_BATTERY_VOLTAGE = 15.5
}

enum class DashboardSignalTone {
    NORMAL,
    INVALID,
    ALERT,
}

enum class DashboardAlertKind {
    LOW_OIL_PRESSURE,
    HIGH_COOLANT,
    BATTERY_OUT_OF_RANGE,
}

data class DashboardSignalAssessment(
    val tone: DashboardSignalTone = DashboardSignalTone.NORMAL,
    val alertKind: DashboardAlertKind? = null,
) {
    val isValid: Boolean
        get() = tone != DashboardSignalTone.INVALID

    val isAlert: Boolean
        get() = tone == DashboardSignalTone.ALERT
}

fun assessDashboardSignal(
    liveData: SpeeduinoLiveData,
    fieldName: String,
    rawValue: Double?,
    rawUnit: String,
): DashboardSignalAssessment {
    if (rawValue == null) return DashboardSignalAssessment()

    val normalizedField = fieldName.trim().lowercase()

    if (normalizedField in setOf("tps") && rawValue > DashboardSignalThresholds.MAX_TPS_PERCENT) {
        return DashboardSignalAssessment(tone = DashboardSignalTone.INVALID)
    }

    if (normalizedField in setOf("gear", "candidategear")) {
        val gear = rawValue.roundToInt()
        if (gear < 0 || gear > DashboardSignalThresholds.MAX_VALID_GEAR) {
            return DashboardSignalAssessment(tone = DashboardSignalTone.INVALID)
        }
    }

    if (normalizedField in setOf("oilpressure", "oil")) {
        val oilPsi = rawValue.toPsi(rawUnit)
        if (oilPsi != null) {
            if (oilPsi >= DashboardSignalThresholds.OIL_PRESSURE_SENSOR_SATURATION_PSI) {
                return DashboardSignalAssessment(tone = DashboardSignalTone.INVALID)
            }
            if (liveData.rpm > DashboardSignalThresholds.LOW_OIL_PRESSURE_MIN_RPM &&
                oilPsi < DashboardSignalThresholds.LOW_OIL_PRESSURE_RUNNING_PSI
            ) {
                return DashboardSignalAssessment(
                    tone = DashboardSignalTone.ALERT,
                    alertKind = DashboardAlertKind.LOW_OIL_PRESSURE,
                )
            }
        }
    }

    if (normalizedField in setOf("coolant", "coolantraw")) {
        val coolantC = rawValue.toCelsius(rawUnit)
        if (coolantC != null && coolantC > DashboardSignalThresholds.HIGH_COOLANT_C) {
            return DashboardSignalAssessment(
                tone = DashboardSignalTone.ALERT,
                alertKind = DashboardAlertKind.HIGH_COOLANT,
            )
        }
    }

    if (normalizedField == "batteryvoltage" &&
        (rawValue < DashboardSignalThresholds.LOW_BATTERY_VOLTAGE ||
            rawValue > DashboardSignalThresholds.HIGH_BATTERY_VOLTAGE)
    ) {
        return DashboardSignalAssessment(
            tone = DashboardSignalTone.ALERT,
            alertKind = DashboardAlertKind.BATTERY_OUT_OF_RANGE,
        )
    }

    return DashboardSignalAssessment()
}

private fun Double.toPsi(unit: String): Double? = when (unit.trim().lowercase()) {
    "psi" -> this
    "kpa" -> this * 0.1450377377
    "bar" -> this * 14.50377377
    else -> null
}

private fun Double.toCelsius(unit: String): Double? = when (unit.trim().lowercase()) {
    "°c", "c" -> this
    "°f", "f" -> (this - 32.0) / 1.8
    else -> null
}
