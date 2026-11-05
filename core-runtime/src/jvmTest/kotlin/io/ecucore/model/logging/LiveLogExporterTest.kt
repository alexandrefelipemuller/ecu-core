package io.ecucore.model.logging

import io.ecucore.model.EcuOutputChannels
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveLogExporterTest {
    private val rnd = Random(42)

    private fun entry(ts: Long, raw: Int = 0, gps: Boolean = true, optionals: Boolean = true) = LiveLogEntry(
        timestampMs = ts, rpm = 800 + (ts / 10).toInt(), mapKpa = 40 + (ts % 7).toInt(), tps = (ts % 100).toInt(),
        coolantTempC = 85, intakeTempC = 30, batteryDeciVolt = 138, advanceDeg = 12, o2 = 147,
        candidateSpeedKph = if (optionals) 60 else null,
        candidateAccelPedalPosPct = if (optionals) 20 else null,
        candidateGear = if (optionals) (ts % 3).toInt() else null,
        candidateThrottleAngleDeg = if (optionals) 12.5 else null,
        candidateIgnitionAdvanceDeg = if (optionals) 10.0 else null,
        candidateInjectionDurationMs = if (optionals) 3.456 else null,
        candidateInjectionDurationMirrorMs = if (optionals) 3.4 else null,
        gpsSpeedKph = if (gps) 42.25 else null,
        gpsLatitude = if (gps) -22.906847 else null,
        gpsLongitude = if (gps) -43.172896 else null,
        outputChannelBlockSize = raw,
        outputChannelData = if (raw > 0) ByteArray(raw).also { rnd.nextBytes(it) } else null,
    )

    private fun snapshot(entries: List<LiveLogEntry>) =
        LiveLogSnapshot(id = "t", startedAtMs = 0, stoppedAtMs = 1000, sampleIntervalMs = 100, entries = entries)

    private fun legacy(format: LiveLogExportFormat, s: LiveLogSnapshot, selected: Set<String>): String {
        val sw = StringWriter()
        PrintWriter(sw).use { out ->
            val fields = LiveLogExporter.resolveFields(s)
            when (format) {
                LiveLogExportFormat.CSV -> LegacyAndroidExporter.writeSnapshotAsCsv(out, s, fields, selected)
                LiveLogExportFormat.MSL -> LegacyAndroidExporter.writeSnapshotAsMsl(out, s, fields, selected)
            }
        }
        return sw.toString()
    }

    private fun current(format: LiveLogExportFormat, s: LiveLogSnapshot, selected: Set<String>): String {
        val sw = StringWriter()
        LiveLogExporter.write(sw, format, s, selected)
        return sw.toString()
    }

    private fun norm(text: String) = text.lines().filterNot { it.contains("Capture Date") }
        .joinToString("\n") { it.trimEnd('\r') }.trimEnd()

    private fun assertSame(s: LiveLogSnapshot, selected: Set<String>) {
        for (format in LiveLogExportFormat.entries) {
            val expected = norm(legacy(format, s, selected))
            assertEquals(expected, norm(current(format, s, selected)), "$format selected=$selected")
            if ("nao_existe" !in selected && format == LiveLogExportFormat.CSV) {
                assertTrue(expected.lines().size > s.entries.size, "saída de referência vazia: $format $selected")
            }
        }
    }

    @Test
    fun `fallback columns match the previous Android output`() {
        val s = snapshot((0L until 8L).map { entry(it * 100) })
        assertSame(s, emptySet())
        assertSame(s, setOf("rpm", "batt_v", "candidate_gear", "gps_location"))
        assertSame(s, setOf("gps_speed_kmh"))
        assertSame(s, setOf("nao_existe")) // nada selecionado: as duas versões não escrevem colunas
    }

    @Test
    fun `fallback with missing optional values and no gps matches`() {
        val s = snapshot((0L until 5L).map { entry(it * 100, gps = false, optionals = false) })
        assertSame(s, emptySet())
    }

    @Test
    fun `raw channels for each block size match the previous Android output`() {
        for (block in listOf(130, 212)) {
            val s = snapshot((0L until 6L).map { entry(it * 100, raw = block) })
            assertTrue(LiveLogExporter.resolveFields(s).isNotEmpty(), "bloco $block sem definição")
            assertSame(s, emptySet())
            assertSame(s, setOf("rpm", "map", "gps_speed_kmh"))
            assertSame(s, setOf("gps_location"))
            assertSame(snapshot((0L until 3L).map { entry(it * 100, raw = block, gps = false) }), emptySet())
        }
    }

    @Test
    fun `raw csv keeps columns aligned when a sample has no channel block`() {
        val fields = EcuOutputChannels.getDefinition(130)
        val s = snapshot(listOf(entry(0, raw = 130), entry(100), entry(200, raw = 130)))
        val lines = current(LiveLogExportFormat.CSV, s, emptySet()).trim().lines()
        val columns = lines.first().split(",").size
        assertEquals(1 + fields.size + 3, columns)
        lines.drop(1).forEach { assertEquals(columns, it.split(",").size, it) }
    }

    @Test
    fun `msl header uses the producer name and relative time`() {
        val s = snapshot(listOf(entry(5000), entry(6500)))
        val sw = StringWriter()
        LiveLogExporter.write(sw, LiveLogExportFormat.MSL, s, setOf("rpm"), producerName = "SpeeduinoManager Desktop", captureDate = Date(0))
        val lines = sw.toString().lines()
        assertTrue(lines[0].contains("SpeeduinoManager Desktop LiveData Export"))
        assertTrue(lines[1].contains("File author: SpeeduinoManager Desktop"))
        assertEquals("Time\trpm", lines[2])
        assertEquals("s\trpm", lines[3])
        assertTrue(lines[4].startsWith("0.000\t"))
        assertTrue(lines[5].startsWith("1.500\t"))
    }

    @Test
    fun `available signals depend on the snapshot`() {
        assertEquals(LiveLogExporter.FALLBACK_SIGNALS, LiveLogExporter.availableSignals(snapshot(listOf(entry(0)))))
        val raw = LiveLogExporter.availableSignals(snapshot(listOf(entry(0, raw = 130))))
        assertTrue("rpm" in raw && "gps_speed_kmh" in raw && "gps_location" in raw)
        assertEquals(raw, LiveLogExporter.availableSignals(130))
        assertEquals(LiveLogExporter.FALLBACK_SIGNALS, LiveLogExporter.availableSignals(0))
    }

    @Test
    fun `formatters`() {
        assertEquals("12", LiveLogExporter.formatLogValue(12.00004))
        assertEquals("12.35", LiveLogExporter.formatLogValue(12.345))
        assertEquals("C", LiveLogExporter.formatMslUnit("°C"))
        assertEquals("us", LiveLogExporter.formatMslUnit("uS"))
        assertEquals("-22.906847", LiveLogExporter.formatCoordinateValue(-22.906847))
    }
}
