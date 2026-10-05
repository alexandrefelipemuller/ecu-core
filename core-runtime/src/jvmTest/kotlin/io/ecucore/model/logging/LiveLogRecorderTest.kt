package io.ecucore.model.logging

import io.ecucore.SpeeduinoLiveData
import io.ecucore.SpeeduinoLiveDataParser
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveLogRecorderTest {

    private fun sample(rpm: Int = 1000, block: ByteArray? = null): SpeeduinoLiveData =
        SpeeduinoLiveDataParser.fromLegacyFrame(ByteArray(128)).copy(rpm = rpm, outputChannelData = block, outputChannelBlockSize = block?.size ?: 0)

    @Test
    fun `nothing is recorded before a session starts`() {
        val recorder = LiveLogRecorder()
        recorder.record(sample())
        assertNull(recorder.snapshot())
        assertFalse(recorder.state.value.isRecording)
        assertEquals(0, recorder.state.value.samplesCaptured)
        recorder.stop() // sem sessão: no-op
        assertNull(recorder.state.value.stoppedAtMs)
    }

    @Test
    fun `start opens a session and record fills the buffer`() {
        val recorder = LiveLogRecorder()
        recorder.start(sampleIntervalMs = 50)
        val s = recorder.state.value
        assertTrue(s.isRecording)
        assertNotNull(s.startedAtMs)
        assertEquals(50, s.sampleIntervalMs)
        assertEquals(0, s.samplesCaptured)
        assertNull(s.lastSample)

        recorder.record(sample(rpm = 1500))
        recorder.record(sample(rpm = 2500))

        assertEquals(2, recorder.state.value.samplesCaptured)
        assertEquals(2500, recorder.state.value.lastSample!!.rpm)
        val snap = recorder.snapshot()!!
        assertEquals(listOf(1500, 2500), snap.entries.map { it.rpm })
        assertEquals(50, snap.sampleIntervalMs)
        assertNull(snap.stoppedAtMs)
        assertTrue(snap.id.isNotBlank())
    }

    @Test
    fun `the buffer keeps only the newest samples`() {
        val recorder = LiveLogRecorder(maxSamples = 3)
        recorder.start(100)
        (1..5).forEach { recorder.record(sample(rpm = it * 100)) }
        assertEquals(3, recorder.state.value.samplesCaptured)
        assertEquals(listOf(300, 400, 500), recorder.snapshot()!!.entries.map { it.rpm })
    }

    @Test
    fun `stop freezes the session but keeps the buffer`() {
        val recorder = LiveLogRecorder()
        recorder.start(100)
        recorder.record(sample(rpm = 1000))
        recorder.stop()
        val s = recorder.state.value
        assertFalse(s.isRecording)
        assertNotNull(s.stoppedAtMs)
        assertEquals(1, s.samplesCaptured)

        recorder.record(sample(rpm = 9999)) // ignorado depois do stop
        assertEquals(1, recorder.state.value.samplesCaptured)
        val snap = recorder.snapshot()!!
        assertEquals(listOf(1000), snap.entries.map { it.rpm })
        assertNotNull(snap.stoppedAtMs)
        assertTrue(snap.startedAtMs > 0)
    }

    @Test
    fun `starting again discards the previous buffer and creates a new session`() {
        val recorder = LiveLogRecorder()
        recorder.start(100)
        recorder.record(sample())
        val firstId = recorder.snapshot()!!.id
        recorder.stop()
        recorder.start(200)
        assertTrue(recorder.state.value.isRecording)
        assertNull(recorder.state.value.stoppedAtMs)
        assertEquals(0, recorder.state.value.samplesCaptured)
        val snap = recorder.snapshot()!!
        assertTrue(snap.entries.isEmpty())
        assertNotEquals(firstId, snap.id)
        assertEquals(200, snap.sampleIntervalMs)
    }

    @Test
    fun `the snapshot is detached from the live buffer`() {
        val recorder = LiveLogRecorder()
        recorder.start(100)
        recorder.record(sample(rpm = 1))
        val snap = recorder.snapshot()!!
        recorder.record(sample(rpm = 2))
        assertEquals(1, snap.entries.size)
        assertEquals(2, recorder.snapshot()!!.entries.size)
    }

    @Test
    fun `entries normalise the live data fields`() {
        val block = byteArrayOf(1, 2, 3)
        val data = sample(rpm = 3000, block = block).copy(
            coolantTemp = 85, intakeTemp = 30, mapPressure = 99, tps = 12, batteryVoltage = 13.8, advance = 22, o2 = 147,
            candidateSpeedKph = 80, candidateAccelPedalPosPct = 40, candidateGear = 3, candidateThrottleAngleDeg = 11.5,
            candidateIgnitionAdvanceDeg = 14.0, candidateInjectionDurationMs = 2.5, candidateInjectionDurationMirrorMs = 2.6,
            gpsSpeedKph = 81.0, gpsLatitude = -23.5, gpsLongitude = -46.6,
        )
        val entry = LiveLogEntry.fromLiveData(data, timestampMs = 42L)
        assertEquals(42L, entry.timestampMs)
        assertEquals(3000, entry.rpm)
        assertEquals(99, entry.mapKpa)
        assertEquals(12, entry.tps)
        assertEquals(85, entry.coolantTempC)
        assertEquals(30, entry.intakeTempC)
        assertEquals(138, entry.batteryDeciVolt)
        assertEquals(22, entry.advanceDeg)
        assertEquals(147, entry.o2)
        assertEquals(80, entry.candidateSpeedKph)
        assertEquals(40, entry.candidateAccelPedalPosPct)
        assertEquals(3, entry.candidateGear)
        assertEquals(11.5, entry.candidateThrottleAngleDeg)
        assertEquals(14.0, entry.candidateIgnitionAdvanceDeg)
        assertEquals(2.5, entry.candidateInjectionDurationMs)
        assertEquals(2.6, entry.candidateInjectionDurationMirrorMs)
        assertEquals(81.0, entry.gpsSpeedKph)
        assertEquals(-23.5, entry.gpsLatitude)
        assertEquals(-46.6, entry.gpsLongitude)
        assertEquals(3, entry.outputChannelBlockSize)
        assertContentEquals(block, entry.outputChannelData)
        block[0] = 99 // a entrada guarda uma cópia
        assertEquals(1, entry.outputChannelData!![0].toInt())
        assertNull(LiveLogEntry.fromLiveData(sample(), 0).outputChannelData)
    }

    @Test
    fun `defaults`() {
        assertEquals(12_000, LiveLogRecorder.DEFAULT_MAX_SAMPLES)
        assertEquals(100L, LiveLogRecorderState.DEFAULT_SAMPLE_INTERVAL_MS)
        val s = LiveLogRecorderState()
        assertFalse(s.isRecording)
        assertEquals(100L, s.sampleIntervalMs)
        assertNull(s.startedAtMs)
        assertNull(s.lastSample)
    }

    @Test
    fun `concurrent producers never exceed the capacity`() {
        val recorder = LiveLogRecorder(maxSamples = 50)
        recorder.start(10)
        val threads = (1..4).map { Thread { repeat(200) { recorder.record(sample()) } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(50, recorder.state.value.samplesCaptured)
        assertEquals(50, recorder.snapshot()!!.entries.size)
    }
}
