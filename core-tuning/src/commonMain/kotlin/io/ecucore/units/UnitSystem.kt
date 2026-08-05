package io.ecucore.units

enum class UnitSystem(val storageValue: String) {
    AUTO("auto"),
    METRIC("metric"),
    IMPERIAL("imperial");

    companion object {
        fun fromStorage(value: String?): UnitSystem {
            return entries.firstOrNull { it.storageValue == value } ?: AUTO
        }
    }
}

fun defaultUnitSystemForLocale(countryCode: String = currentLocaleCountryCode()): UnitSystem {
    return when (countryCode.uppercase()) {
        "US", "LR", "MM" -> UnitSystem.IMPERIAL
        else -> UnitSystem.METRIC
    }
}

fun resolveEffectiveUnitSystem(selected: UnitSystem, countryCode: String = currentLocaleCountryCode()): UnitSystem {
    return if (selected == UnitSystem.AUTO) {
        defaultUnitSystemForLocale(countryCode)
    } else {
        selected
    }
}

object UnitConverter {
    private const val KPA_TO_PSI = 0.1450377377
    private const val KMH_TO_MPH = 0.6213711922

    fun convertValue(value: Double, unit: String, system: UnitSystem): Pair<Double, String> {
        if (system != UnitSystem.IMPERIAL) {
            return value to unit
        }

        return when (unit.trim().lowercase()) {
            "kpa" -> value * KPA_TO_PSI to "psi"
            "km/h", "kph" -> value * KMH_TO_MPH to "mph"
            "°c", "c" -> (value * 9.0 / 5.0 + 32.0) to "°F"
            else -> value to unit
        }
    }
}
