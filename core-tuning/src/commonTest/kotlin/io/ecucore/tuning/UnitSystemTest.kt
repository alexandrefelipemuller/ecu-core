package io.ecucore.tuning

import io.ecucore.units.UnitConverter
import io.ecucore.units.UnitSystem
import io.ecucore.units.defaultUnitSystemForLocale
import io.ecucore.units.resolveEffectiveUnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class UnitSystemTest {

    @Test
    fun fromStorageFallsBackToAuto() {
        assertEquals(UnitSystem.IMPERIAL, UnitSystem.fromStorage("imperial"))
        assertEquals(UnitSystem.METRIC, UnitSystem.fromStorage("metric"))
        assertEquals(UnitSystem.AUTO, UnitSystem.fromStorage("lixo"))
        assertEquals(UnitSystem.AUTO, UnitSystem.fromStorage(null))
    }

    @Test
    fun localeDefaults() {
        assertEquals(UnitSystem.IMPERIAL, defaultUnitSystemForLocale("us"))
        assertEquals(UnitSystem.IMPERIAL, defaultUnitSystemForLocale("LR"))
        assertEquals(UnitSystem.IMPERIAL, defaultUnitSystemForLocale("MM"))
        assertEquals(UnitSystem.METRIC, defaultUnitSystemForLocale("BR"))
    }

    @Test
    fun explicitSelectionBeatsLocale() {
        assertEquals(UnitSystem.METRIC, resolveEffectiveUnitSystem(UnitSystem.METRIC, "US"))
        assertEquals(UnitSystem.IMPERIAL, resolveEffectiveUnitSystem(UnitSystem.AUTO, "US"))
    }

    @Test
    fun metricLeavesValuesUntouched() {
        assertEquals(100.0 to "kPa", UnitConverter.convertValue(100.0, "kPa", UnitSystem.METRIC))
        assertEquals(100.0 to "kPa", UnitConverter.convertValue(100.0, "kPa", UnitSystem.AUTO))
    }

    @Test
    fun imperialConversions() {
        val (psi, psiUnit) = UnitConverter.convertValue(100.0, " kPa ", UnitSystem.IMPERIAL)
        assertEquals("psi", psiUnit); assertEquals(14.50377377, psi, 1e-6)
        val (mph, mphUnit) = UnitConverter.convertValue(100.0, "KM/H", UnitSystem.IMPERIAL)
        assertEquals("mph", mphUnit); assertEquals(62.13711922, mph, 1e-6)
        assertEquals(212.0 to "°F", UnitConverter.convertValue(100.0, "°C", UnitSystem.IMPERIAL))
        assertEquals(32.0 to "°F", UnitConverter.convertValue(0.0, "c", UnitSystem.IMPERIAL))
        assertEquals(5.0 to "ms", UnitConverter.convertValue(5.0, "ms", UnitSystem.IMPERIAL))
    }
}
