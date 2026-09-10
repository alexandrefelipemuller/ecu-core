package io.ecucore.telemetry

import io.ecucore.SpeeduinoLiveData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun liveData(rpm: Int = 0, mapPressure: Int = 0, tps: Int = 0) = SpeeduinoLiveData(
    secl = 0,
    rpm = rpm,
    coolantTemp = 80,
    intakeTemp = 25,
    mapPressure = mapPressure,
    tps = tps,
    batteryVoltage = 12.6,
    advance = 10,
    o2 = 0,
    engineStatus = 1,
    sparkStatus = 1
)

class LiveDataPredictorTest {

    @Test
    fun estimateAt_returnsNull_beforeAnySample() {
        val predictor = LiveDataPredictor()
        assertNull(predictor.estimateAt(0L))
    }

    @Test
    fun estimateAt_extrapolatesLinearlyBetweenTwoRealSamples() {
        val predictor = LiveDataPredictor()
        predictor.onSample(liveData(rpm = 1000), timestampNs = 0L)
        predictor.onSample(liveData(rpm = 2000), timestampNs = 500_000_000L) // +500ms

        // 250ms after the 2nd real sample, at the velocity observed (1000rpm/500ms = 2000rpm/s),
        // the estimate should have moved forward from 2000 towards ~2500, clearly above 2000
        // and below what a naive doubled extrapolation would give.
        val estimated = predictor.estimateRpmAt(nowNs = 500_000_000L + 250_000_000L)
        assertTrue(estimated > 2000, "expected forward extrapolation above last real value, got $estimated")
        assertTrue(estimated < 3200, "expected extrapolation not to overshoot wildly, got $estimated")
    }

    @Test
    fun estimateAt_neverExceedsAbsoluteClampBeyondMaxLookahead() {
        val config = FieldPredictionConfig(
            maxVelocityPerSecond = 8000.0,
            velocitySmoothingHalfLifeMs = 180L,
            maxLookaheadNs = 350_000_000L
        )
        val predictor = LiveDataPredictor(mapOf(PredictedField.RPM to config))
        predictor.onSample(liveData(rpm = 1000), timestampNs = 0L)
        predictor.onSample(liveData(rpm = 4000), timestampNs = 100_000_000L) // steep climb, 30000rpm/s raw (clamped)

        val hardClamp = 4000.0 + config.maxVelocityPerSecond * (config.maxLookaheadNs / 1_000_000_000.0)

        // Long after the link "stalls" (well beyond maxLookaheadNs), the estimate must never exceed
        // the hard bound reachable within maxLookaheadNs at the clamped velocity, even under decay.
        val farFuture = predictor.estimateRpmAt(nowNs = 100_000_000L + 5_000_000_000L)
        assertTrue(
            farFuture <= hardClamp + 1,
            "expected estimate to stay under the lookahead-bounded clamp ($hardClamp), got $farFuture"
        )
    }

    @Test
    fun onSample_ignoresHeldStaleValueWithoutResettingTrend() {
        val predictor = LiveDataPredictor()
        predictor.onSample(liveData(rpm = 1000), timestampNs = 0L)
        predictor.onSample(liveData(rpm = 2000), timestampNs = 100_000_000L)

        val estimateBeforeHold = predictor.estimateRpmAt(nowNs = 150_000_000L)

        // A "stale" sample where RPM didn't actually change (e.g. an OBD2 slow-cadence PID holding
        // its last polled value) must not reset the velocity/trend.
        predictor.onSample(liveData(rpm = 2000), timestampNs = 200_000_000L)

        val estimateAfterHold = predictor.estimateRpmAt(nowNs = 250_000_000L)
        assertTrue(
            estimateAfterHold > 2000,
            "expected trend to keep extrapolating forward despite the held/stale sample, got $estimateAfterHold"
        )
        assertTrue(estimateBeforeHold > 2000)
    }

    @Test
    fun reset_clearsTrackersAndFirstSampleAfterHoldsFlat() {
        val predictor = LiveDataPredictor()
        predictor.onSample(liveData(rpm = 1000), timestampNs = 0L)
        predictor.onSample(liveData(rpm = 5000), timestampNs = 100_000_000L)

        predictor.reset()
        assertNull(predictor.estimateAt(nowNs = 100_000_000L))

        predictor.onSample(liveData(rpm = 800), timestampNs = 200_000_000L)
        // Immediately after reset + first sample there is no velocity yet, so it must hold flat.
        assertEquals(800, predictor.estimateRpmAt(nowNs = 300_000_000L))
    }

    @Test
    fun aggressiveness_scalesExtrapolationRelativeToLightAndModerate() {
        fun predictorFor(aggressiveness: PredictionAggressiveness) = LiveDataPredictor(aggressiveness).apply {
            onSample(liveData(rpm = 1000), timestampNs = 0L)
            onSample(liveData(rpm = 2000), timestampNs = 100_000_000L)
        }

        val nowNs = 100_000_000L + 200_000_000L // 200ms past the last real sample

        val light = predictorFor(PredictionAggressiveness.LIGHT).estimateRpmAt(nowNs)
        val moderate = predictorFor(PredictionAggressiveness.MODERATE).estimateRpmAt(nowNs)
        val aggressive = predictorFor(PredictionAggressiveness.AGGRESSIVE).estimateRpmAt(nowNs)

        assertTrue(
            light <= moderate && moderate <= aggressive,
            "expected light <= moderate <= aggressive extrapolation, got light=$light moderate=$moderate aggressive=$aggressive"
        )
        assertTrue(aggressive > light, "expected AGGRESSIVE to extrapolate visibly further than LIGHT")
    }

    @Test
    fun velocitySmoothing_absorbsASingleNoisySampleWithoutReversingTheRamp() {
        val predictor = LiveDataPredictor()
        // Amostras subindo, mas com uma leitura ruidosa/quantizada (queda momentânea de 20rpm em
        // 50ms) no meio - típico de resolução/jitter de OBD2, não uma mudança real de tendência.
        predictor.onSample(liveData(rpm = 1000), timestampNs = 0L)
        predictor.onSample(liveData(rpm = 1300), timestampNs = 100_000_000L)
        predictor.onSample(liveData(rpm = 1280), timestampNs = 150_000_000L) // blip ruidoso

        // Sem suavização, a inclinação seria substituída pela do blip (negativa) e a curva
        // reverteria/"achataria" na hora - exatamente a "onda quadrada" reportada em vez de rampa.
        // Com EMA, a tendência de subida (ponderada pelas amostras anteriores) ainda domina.
        val shortlyAfter = predictor.estimateRpmAt(nowNs = 170_000_000L)
        assertTrue(
            shortlyAfter >= 1280,
            "expected smoothed trend to keep climbing (or hold) despite one noisy dip, got $shortlyAfter"
        )
    }
}
