package io.ecucore.model.logging

import io.ecucore.model.EcuOutputChannels
import io.ecucore.model.OutputField
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class LiveLogExportFormat(val fileExtension: String, val mimeType: String) {
    CSV(fileExtension = "csv", mimeType = "text/csv"),
    MSL(fileExtension = "msl", mimeType = "text/plain"),
}

/**
 * Exporta um [LiveLogSnapshot] como CSV ou MSL (MegaLogViewer), igual nos dois apps.
 *
 * Com bloco de canais da ECU ([resolveFields] não vazio) exporta os canais brutos pelo nome do campo
 * (o mesmo nome que a definição da ECU usa), mais velocidade/posição do GPS. Sem bloco (ex.: OBD2/PSA) cai
 * nas colunas decodificadas de [FALLBACK_SIGNALS].
 *
 * [selectedSignals] vazio = tudo. Os nomes são os dos campos brutos, os de [FALLBACK_SIGNALS] e
 * `gps_speed_kmh` / `gps_location`.
 */
object LiveLogExporter {
    const val GPS_SPEED = "gps_speed_kmh"
    const val GPS_LOCATION = "gps_location"

    /** Colunas decodificadas usadas quando o snapshot não tem bloco bruto da ECU. */
    val FALLBACK_SIGNALS: List<String> = listOf(
        "rpm", "map_kpa", "tps", "coolant_c", "iat_c", "batt_v", "advance_deg",
        "candidate_speed_kph", "candidate_gear", "candidate_accel_pedal_pos_pct",
        "candidate_throttle_angle_deg", "candidate_ignition_advance_deg", "o2",
        "inj_time_ms", "inj_time_mirror_ms", GPS_SPEED, GPS_LOCATION,
    )

    /** Definição dos canais brutos do snapshot (vazia se nenhuma amostra trouxe o bloco). */
    fun resolveFields(snapshot: LiveLogSnapshot): List<OutputField> {
        val first = snapshot.entries.firstOrNull { it.outputChannelBlockSize > 0 && it.outputChannelData != null }
            ?: snapshot.entries.firstOrNull()
        val blockSize = first?.outputChannelBlockSize ?: 0
        return if (blockSize > 0) EcuOutputChannels.getDefinition(blockSize) else emptyList()
    }

    /** Sinais que o usuário pode escolher para este snapshot (para montar a lista de seleção na UI). */
    fun availableSignals(snapshot: LiveLogSnapshot): List<String> {
        val fields = resolveFields(snapshot)
        return if (fields.isEmpty()) FALLBACK_SIGNALS else fields.map { it.name } + listOf(GPS_SPEED, GPS_LOCATION)
    }

    fun write(
        out: Writer,
        format: LiveLogExportFormat,
        snapshot: LiveLogSnapshot,
        selectedSignals: Set<String> = emptySet(),
        producerName: String = "ECU Manager",
        captureDate: Date = Date(),
    ) {
        val fields = resolveFields(snapshot)
        when (format) {
            LiveLogExportFormat.CSV -> writeCsv(out, snapshot, fields, selectedSignals)
            LiveLogExportFormat.MSL -> writeMsl(out, snapshot, fields, selectedSignals, producerName, captureDate)
        }
    }

    fun writeCsv(out: Writer, snapshot: LiveLogSnapshot, fields: List<OutputField>, selectedSignals: Set<String>) {
        if (fields.isEmpty()) {
            val columns = fallbackColumns(selectedSignals)
            if (columns.isEmpty()) return
            out.appendLine((listOf("timestamp_ms") + columns.flatMap(::fallbackHeaders)).joinToString(","))
            snapshot.entries.forEach { entry ->
                val cells = columns.flatMap { fallbackCells(entry, it, msl = false) }
                out.appendLine((listOf(entry.timestampMs.toString()) + cells).joinToString(","))
            }
            return
        }
        val plan = rawPlan(fields, selectedSignals) ?: return
        out.appendLine((listOf("timestamp_ms") + plan.headers).joinToString(","))
        snapshot.entries.forEach { entry ->
            out.appendLine((listOf(entry.timestampMs.toString()) + rawCells(entry, plan)).joinToString(","))
        }
    }

    fun writeMsl(
        out: Writer,
        snapshot: LiveLogSnapshot,
        fields: List<OutputField>,
        selectedSignals: Set<String>,
        producerName: String = "ECU Manager",
        captureDate: Date = Date(),
    ) {
        val captureDateFormat = SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy", Locale.US)
        out.appendLine("\"speeduino_manager_live_log: $producerName LiveData Export\"")
        out.appendLine("\"Capture Date: ${captureDateFormat.format(captureDate)}, File author: $producerName\"")
        val firstTimestampMs = snapshot.entries.firstOrNull()?.timestampMs ?: 0L
        fun relTime(entry: LiveLogEntry) =
            String.format(Locale.US, "%.3f", (entry.timestampMs - firstTimestampMs).coerceAtLeast(0L) / 1000.0)

        if (fields.isEmpty()) {
            val columns = fallbackColumns(selectedSignals)
            if (columns.isEmpty()) return
            out.appendLine((listOf("Time") + columns.flatMap(::fallbackHeaders)).joinToString("\t"))
            out.appendLine((listOf("s") + columns.flatMap(::fallbackUnits)).joinToString("\t"))
            snapshot.entries.forEach { entry ->
                out.appendLine((listOf(relTime(entry)) + columns.flatMap { fallbackCells(entry, it, msl = true) }).joinToString("\t"))
            }
            return
        }
        val plan = rawPlan(fields, selectedSignals) ?: return
        out.appendLine((listOf("Time") + plan.headers).joinToString("\t"))
        out.appendLine((listOf("s") + plan.units).joinToString("\t"))
        snapshot.entries.forEach { entry ->
            out.appendLine((listOf(relTime(entry)) + rawCells(entry, plan)).joinToString("\t"))
        }
    }

    // ---- plano de colunas brutas ---------------------------------------------------------------------------------

    private class RawPlan(val fields: List<OutputField>, val gpsSpeed: Boolean, val gpsLocation: Boolean) {
        val headers: List<String> = fields.map { it.name } +
            (if (gpsSpeed) listOf(GPS_SPEED) else emptyList()) +
            (if (gpsLocation) listOf("latitude", "longitude") else emptyList())
        val units: List<String> = fields.map { formatMslUnit(it.units) } +
            (if (gpsSpeed) listOf("km/h") else emptyList()) +
            (if (gpsLocation) listOf("deg", "deg") else emptyList())
    }

    private fun rawPlan(fields: List<OutputField>, selectedSignals: Set<String>): RawPlan? {
        val selected = if (selectedSignals.isEmpty()) fields else fields.filter { selectedSignals.contains(it.name) }
        val gpsSpeed = selectedSignals.isEmpty() || selectedSignals.contains(GPS_SPEED)
        val gpsLocation = selectedSignals.isEmpty() || selectedSignals.contains(GPS_LOCATION)
        if (selected.isEmpty() && !gpsSpeed && !gpsLocation) return null
        return RawPlan(selected, gpsSpeed, gpsLocation)
    }

    /** Amostra sem bloco bruto: os canais saem vazios (colunas continuam alinhadas) e o GPS vale. */
    private fun rawCells(entry: LiveLogEntry, plan: RawPlan): List<String> {
        val data = entry.outputChannelData
        val raw = plan.fields.map { field ->
            if (data != null && entry.outputChannelBlockSize > 0) formatLogValue(field.parse(data)) else ""
        }
        return raw +
            (if (plan.gpsSpeed) listOf(entry.gpsSpeedKph?.let(::formatLogValue) ?: "") else emptyList()) +
            (if (plan.gpsLocation) {
                listOf(entry.gpsLatitude?.let(::formatCoordinateValue) ?: "", entry.gpsLongitude?.let(::formatCoordinateValue) ?: "")
            } else emptyList())
    }

    // ---- colunas decodificadas (sem bloco bruto) -----------------------------------------------------------------

    private fun fallbackColumns(selectedSignals: Set<String>): List<String> =
        if (selectedSignals.isEmpty()) FALLBACK_SIGNALS else FALLBACK_SIGNALS.filter { selectedSignals.contains(it) }

    private fun fallbackHeaders(column: String): List<String> =
        if (column == GPS_LOCATION) listOf("latitude", "longitude") else listOf(column)

    private fun fallbackUnits(column: String): List<String> = when (column) {
        GPS_LOCATION -> listOf("deg", "deg")
        "rpm" -> listOf("rpm")
        "map_kpa" -> listOf("kPa")
        "tps", "candidate_accel_pedal_pos_pct" -> listOf("%")
        "coolant_c", "iat_c" -> listOf("C")
        "batt_v" -> listOf("V")
        "advance_deg", "candidate_throttle_angle_deg", "candidate_ignition_advance_deg" -> listOf("deg")
        "candidate_speed_kph", GPS_SPEED -> listOf("km/h")
        "candidate_gear" -> listOf("gear")
        "o2" -> listOf("O2")
        "inj_time_ms", "inj_time_mirror_ms" -> listOf("ms")
        else -> listOf("")
    }

    private fun fallbackCells(entry: LiveLogEntry, column: String, msl: Boolean): List<String> = when (column) {
        "rpm" -> listOf(entry.rpm.toString())
        "map_kpa" -> listOf(entry.mapKpa.toString())
        "tps" -> listOf(entry.tps.toString())
        "coolant_c" -> listOf(entry.coolantTempC.toString())
        "iat_c" -> listOf(entry.intakeTempC.toString())
        "batt_v" -> listOf(String.format(Locale.US, "%.1f", entry.batteryDeciVolt / 10.0))
        "advance_deg" -> listOf(entry.advanceDeg.toString())
        "candidate_speed_kph" -> listOf(entry.candidateSpeedKph?.toString() ?: "")
        "candidate_gear" -> listOf(entry.candidateGear?.let { if (it == 0) "N" else it.toString() } ?: "")
        "candidate_accel_pedal_pos_pct" -> listOf(entry.candidateAccelPedalPosPct?.toString() ?: "")
        "candidate_throttle_angle_deg" -> listOf(entry.candidateThrottleAngleDeg?.let(::formatLogValue) ?: "")
        "candidate_ignition_advance_deg" -> listOf(entry.candidateIgnitionAdvanceDeg?.let(::formatLogValue) ?: "")
        "o2" -> listOf(entry.o2.toString())
        "inj_time_ms" -> listOf(entry.candidateInjectionDurationMs?.let(::formatLogValue) ?: "")
        "inj_time_mirror_ms" -> listOf(entry.candidateInjectionDurationMirrorMs?.let(::formatLogValue) ?: "")
        GPS_SPEED -> listOf(entry.gpsSpeedKph?.let(::formatLogValue) ?: "")
        GPS_LOCATION -> listOf(
            entry.gpsLatitude?.let(::formatCoordinateValue) ?: "",
            entry.gpsLongitude?.let(::formatCoordinateValue) ?: "",
        )
        else -> listOf("")
    }

    // ---- formatação ----------------------------------------------------------------------------------------------

    fun formatMslUnit(rawUnit: String): String {
        if (rawUnit.isBlank()) return ""
        return when (rawUnit) {
            "°C" -> "C"
            "uS" -> "us"
            else -> rawUnit
        }
    }

    fun formatLogValue(value: Double): String {
        val rounded = value.roundToInt().toDouble()
        return if (abs(value - rounded) < 0.0001) rounded.toInt().toString() else String.format(Locale.US, "%.2f", value)
    }

    fun formatCoordinateValue(value: Double): String = String.format(Locale.US, "%.6f", value)
}
