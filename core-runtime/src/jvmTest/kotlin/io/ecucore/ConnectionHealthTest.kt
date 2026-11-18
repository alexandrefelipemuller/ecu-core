package io.ecucore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionHealthTest {

    @Test
    fun `fresh session has no degradation signals`() {
        val s = ConnectionHealth().snapshot(null, false)
        assertEquals(0, s.recoverableFailures)
        assertEquals(0.0, s.failuresPer1000Frames)
        assertEquals(emptyList(), s.protocolSwitches)
        assertNull(s.lastError)
        assertNull(s.lastWriteOperation)
        assertNull(s.msSinceLastWrite)
    }

    @Test
    fun `counters and rates add up`() {
        val h = ConnectionHealth()
        repeat(1000) { h.recordFrame() }
        h.recordRecoverable(isDesync = false, message = "timeout")
        h.recordRecoverable(isDesync = true, message = "desync")
        h.recordRecoverable(isDesync = true, message = "desync2")
        h.recordModernLiveFailure("modern failed")
        h.recordStreamRecovery()
        h.recordWriteRetry(); h.recordWriteRetry()
        h.recordLinkLossDuringStream()

        val s = h.snapshot("legacy_ascii_reply", legacyLockedOut = true)

        assertEquals(1, s.recoverableTimeouts)
        assertEquals(2, s.recoverableDesyncs)
        assertEquals(3, s.recoverableFailures)
        assertEquals(3.0, s.failuresPer1000Frames)
        assertEquals(1, s.modernLiveFailures)
        assertEquals(1, s.streamRecoveries)
        assertEquals(2, s.writeChunkRetries)
        assertEquals(1, s.linkLossesDuringStream)
        assertEquals("legacy_ascii_reply", s.handshakeFallbackReason)
        assertTrue(s.legacyLockedOut)
        assertEquals("modern failed", s.lastError)
    }

    @Test
    fun `protocol switches are recorded in order, summarized and capped`() {
        val h = ConnectionHealth()
        h.recordProtocolSwitch("modern", "legacy", "live_data_modern_failed")
        repeat(20) { h.recordProtocolSwitch("legacy", "modern", "retry$it") }

        val s = h.snapshot(null, false)

        assertEquals(8, s.protocolSwitches.size, "limite de trocas guardadas")
        assertTrue(s.protocolSwitchSummary().startsWith("modern>legacy:live_data_modern_failed@"))
        assertEquals(4, s.protocolSwitchSummary().split(';').size)
    }

    @Test
    fun `last write operation is tracked and reset starts a new session`() {
        val h = ConnectionHealth()
        h.recordWriteOperation("writeVeTable")
        h.recordFrame(); h.recordForcedDisconnect("x".repeat(500))

        val s = h.snapshot(null, false)
        assertEquals("writeVeTable", s.lastWriteOperation)
        assertTrue(s.msSinceLastWrite!! >= 0)
        assertEquals(120, s.lastError!!.length, "mensagem longa é truncada")

        h.reset()
        val fresh = h.snapshot(null, false)
        assertEquals(0L, fresh.framesReceived)
        assertEquals(0, fresh.forcedDisconnects)
        assertNull(fresh.lastWriteOperation)
        assertNull(fresh.lastError)
    }

    @Test
    fun `swallowed errors are counted, kept as last and reset with the session`() {
        val h = ConnectionHealth()
        h.recordSwallowed("battery_correction_page_4", "Timeout: expected 16 bytes")
        h.recordSwallowed("listener_write_integrity", "x".repeat(500))

        val s = h.snapshot(null, false)
        assertEquals(2, s.swallowedErrors)
        assertTrue(s.lastSwallowed!!.startsWith("listener_write_integrity:"))
        assertEquals(120, s.lastSwallowed!!.length)

        h.reset()
        assertEquals(0, h.snapshot(null, false).swallowedErrors)
    }
}
