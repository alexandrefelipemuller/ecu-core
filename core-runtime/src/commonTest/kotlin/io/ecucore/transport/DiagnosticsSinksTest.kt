package io.ecucore.transport

import io.ecucore.SpeeduinoLiveDataParser
import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticsSinksTest {

    private class Recording : ConnectionDiagnosticsSink {
        val lines = mutableListOf<Triple<String, String, String>>()
        override fun log(transport: String, state: String, message: String) { lines += Triple(transport, state, message) }
    }

    @Test
    fun `logError logs the message and then the error`() {
        val sink = Recording()
        sink.logError("tcp", "failed", "falhou", IllegalStateException("causa"))
        assertEquals(listOf("falhou", "error: causa"), sink.lines.map { it.third })
        assertEquals(setOf("tcp"), sink.lines.map { it.first }.toSet())
    }

    @Test
    fun `logError without a throwable only logs the message`() {
        val sink = Recording()
        sink.logError("tcp", "failed", "falhou")
        assertEquals(1, sink.lines.size)
    }

    @Test
    fun `logError falls back to toString when the error has no message`() {
        val sink = Recording()
        sink.logError("tcp", "failed", "falhou", IllegalStateException())
        assertEquals(2, sink.lines.size)
        assertEquals(true, sink.lines[1].third.startsWith("error: "))
        assertEquals(true, sink.lines[1].third.length > "error: ".length)
    }

    @Test
    fun `noop sinks accept every call`() {
        NoopConnectionDiagnosticsSink.log("t", "s", "m")
        NoopConnectionDiagnosticsSink.logError("t", "s", "m", RuntimeException("x"))
        val sink: Obd2InvestigationSink = NoopObd2InvestigationSink
        sink.startSession("obd2", mapOf("a" to "b"))
        sink.startSession("obd2")
        sink.info("stage", "msg")
        sink.recordCommand("obd2", "010C", "41 0C", 100, 5)
        sink.recordCommand("obd2", "010C", "41 0C", 100, 5, mapOf("k" to "v"))
        val sample = SpeeduinoLiveDataParser.fromLegacyFrame(ByteArray(128))
        sink.recordSample("obd2", sample)
        sink.recordSample("obd2", sample, mapOf("k" to "v"))
        sink.recordProprietaryFrame("src", 1L, "cmd", "AA BB", listOf(0xAA, 0xBB))
        sink.recordProprietaryFrame("src", 1L, "cmd", "AA BB", listOf(0xAA, 0xBB), mapOf("k" to "v"))
        sink.updateMetadata("key", null)
        sink.updateMetadata("key", 1)
        sink.closeSession()
        sink.closeSession(mapOf("ok" to "true"))
    }
}
