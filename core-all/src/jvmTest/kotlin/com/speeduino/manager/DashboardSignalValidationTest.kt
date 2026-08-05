package com.speeduino.manager

import kotlin.test.Test
import kotlin.test.assertEquals

class DashboardSignalValidationTest {

    @Test
    fun invalidatesTpsAboveHundredPercent() {
        val assessment = assessDashboardSignal(
            liveData = sampleLiveData(),
            fieldName = "tps",
            rawValue = 122.0,
            rawUnit = "%",
        )

        assertEquals(DashboardSignalTone.INVALID, assessment.tone)
    }

    @Test
    fun alertsLowOilPressureWhileEngineIsRunning() {
        val assessment = assessDashboardSignal(
            liveData = sampleLiveData(rpm = 2856),
            fieldName = "oilPressure",
            rawValue = 0.0,
            rawUnit = "psi",
        )

        assertEquals(DashboardSignalTone.ALERT, assessment.tone)
        assertEquals(DashboardAlertKind.LOW_OIL_PRESSURE, assessment.alertKind)
    }

    @Test
    fun invalidatesOutOfRangeGear() {
        val assessment = assessDashboardSignal(
            liveData = sampleLiveData(),
            fieldName = "candidateGear",
            rawValue = 45.0,
            rawUnit = "gear",
        )

        assertEquals(DashboardSignalTone.INVALID, assessment.tone)
    }

    @Test
    fun alertsBatteryOutsideSafeRange() {
        val assessment = assessDashboardSignal(
            liveData = sampleLiveData(),
            fieldName = "batteryVoltage",
            rawValue = 10.7,
            rawUnit = "V",
        )

        assertEquals(DashboardSignalTone.ALERT, assessment.tone)
        assertEquals(DashboardAlertKind.BATTERY_OUT_OF_RANGE, assessment.alertKind)
    }

    private fun sampleLiveData(rpm: Int = 900): SpeeduinoLiveData = SpeeduinoLiveData(
        secl = 0,
        rpm = rpm,
        coolantTemp = 90,
        intakeTemp = 35,
        mapPressure = 100,
        tps = 15,
        batteryVoltage = 13.8,
        advance = 10,
        o2 = 147,
        engineStatus = 1,
        sparkStatus = 1,
    )
}
