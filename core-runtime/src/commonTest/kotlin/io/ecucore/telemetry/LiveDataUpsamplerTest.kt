package io.ecucore.telemetry

import io.ecucore.SpeeduinoLiveData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun liveData(rpm: Int) = SpeeduinoLiveData(
    secl = 0,
    rpm = rpm,
    coolantTemp = 80,
    intakeTemp = 25,
    mapPressure = 100,
    tps = 0,
    batteryVoltage = 12.6,
    advance = 10,
    o2 = 0,
    engineStatus = 1,
    sparkStatus = 1
)

@OptIn(ExperimentalCoroutinesApi::class)
class LiveDataUpsamplerTest {

    @Test
    fun ingestRealSample_publishesImmediatelyWithoutWaitingForTicker() = runTest {
        val upsampler = LiveDataUpsampler(tickIntervalMs = 1_000L)
        val collected = mutableListOf<SpeeduinoLiveData>()
        val job = launch { upsampler.samples.collect { collected.add(it) } }
        runCurrent() // let the collector subscribe before we emit

        upsampler.ingestRealSample(liveData(rpm = 1234))
        advanceTimeBy(1) // let the collector coroutine run

        assertEquals(1, collected.size)
        assertEquals(1234, collected.first().rpm)
        job.cancel()
    }

    @Test
    fun start_emitsExtrapolatedSamplesOnTicker() = runTest {
        val upsampler = LiveDataUpsampler(tickIntervalMs = 100L)
        val collected = mutableListOf<SpeeduinoLiveData>()
        val collectJob = launch { upsampler.samples.collect { collected.add(it) } }
        runCurrent() // let the collector subscribe before we emit

        upsampler.start(this)
        upsampler.ingestRealSample(liveData(rpm = 1000))
        advanceTimeBy(1)
        upsampler.ingestRealSample(liveData(rpm = 2000))

        advanceTimeBy(250) // let the 100ms ticker fire a couple of times

        assertTrue(collected.size >= 3, "expected real sample(s) + at least one ticked sample, got ${collected.size}")

        upsampler.stop()
        collectJob.cancel()
    }

    @Test
    fun estimateAt_matchesUnderlyingPredictorWithoutTicker() = runTest {
        val upsampler = LiveDataUpsampler()
        assertEquals(null, upsampler.estimateAt(0L))

        upsampler.ingestRealSample(liveData(rpm = 900), timestampNs = 0L)
        assertEquals(900, upsampler.estimateRpmAt(nowNs = 0L))
    }
}
