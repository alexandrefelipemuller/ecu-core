package io.ecucore.model.logging

import io.ecucore.model.OutputField
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Cópia literal da exportação que vivia na MainActivity do Android (antes de ir para o core), usada como
 * oráculo: a nova implementação tem que produzir exatamente a mesma saída.
 */
object LegacyAndroidExporter {
        fun writeSnapshotAsCsv(
            out: java.io.PrintWriter,
            snapshot: LiveLogSnapshot,
            fields: List<OutputField>,
            selectedSignals: Set<String>
        ) {
            if (fields.isEmpty()) {
                val fallbackColumns = listOf(
                    "rpm",
                    "map_kpa",
                    "tps",
                    "coolant_c",
                    "iat_c",
                    "batt_v",
                    "advance_deg",
                    "candidate_speed_kph",
                    "candidate_gear",
                    "candidate_accel_pedal_pos_pct",
                    "candidate_throttle_angle_deg",
                    "candidate_ignition_advance_deg",
                    "o2",
                    "inj_time_ms",
                    "inj_time_mirror_ms",
                    "gps_speed_kmh",
                    "gps_location",
                )
                val effectiveColumns = if (selectedSignals.isEmpty()) {
                    fallbackColumns
                } else {
                    fallbackColumns.filter { selectedSignals.contains(it) }
                }
                if (effectiveColumns.isEmpty()) return

                out.println(buildString {
                    append("timestamp_ms")
                    effectiveColumns.forEach { column ->
                        when (column) {
                            "gps_location" -> append(",latitude,longitude")
                            else -> append(",").append(column)
                        }
                    }
                })
                snapshot.entries.forEach { entry ->
                    val batt = entry.batteryDeciVolt / 10.0
                    out.println(buildString {
                        append(entry.timestampMs)
                        effectiveColumns.forEach { column ->
                            when (column) {
                                "rpm" -> append(",").append(entry.rpm)
                                "map_kpa" -> append(",").append(entry.mapKpa)
                                "tps" -> append(",").append(entry.tps)
                                "coolant_c" -> append(",").append(entry.coolantTempC)
                                "iat_c" -> append(",").append(entry.intakeTempC)
                                "batt_v" -> append(",").append(String.format(Locale.US, "%.1f", batt))
                                "advance_deg" -> append(",").append(entry.advanceDeg)
                                "candidate_speed_kph" -> append(",").append(entry.candidateSpeedKph ?: "")
                                "candidate_gear" -> append(",").append(entry.candidateGear?.let { if (it == 0) "N" else it.toString() } ?: "")
                                "candidate_accel_pedal_pos_pct" -> append(",").append(entry.candidateAccelPedalPosPct ?: "")
                                "candidate_throttle_angle_deg" -> append(",").append(entry.candidateThrottleAngleDeg?.let { formatLogValue(it) } ?: "")
                                "candidate_ignition_advance_deg" -> append(",").append(entry.candidateIgnitionAdvanceDeg?.let { formatLogValue(it) } ?: "")
                                "o2" -> append(",").append(entry.o2)
                                "inj_time_ms" -> append(",").append(entry.candidateInjectionDurationMs?.let { formatLogValue(it) } ?: "")
                                "inj_time_mirror_ms" -> append(",").append(entry.candidateInjectionDurationMirrorMs?.let { formatLogValue(it) } ?: "")
                                "gps_speed_kmh" -> append(",").append(entry.gpsSpeedKph?.let { formatLogValue(it) } ?: "")
                                "gps_location" -> {
                                    append(",").append(entry.gpsLatitude?.let { formatCoordinateValue(it) } ?: "")
                                    append(",").append(entry.gpsLongitude?.let { formatCoordinateValue(it) } ?: "")
                                }
                            }
                        }
                    })
                }
                return
            }

            val selectedFields = if (selectedSignals.isEmpty()) {
                fields
            } else {
                fields.filter { selectedSignals.contains(it.name) }
            }
            val includeGpsSpeed = selectedSignals.isEmpty() || selectedSignals.contains("gps_speed_kmh")
            val includeGpsLocation = selectedSignals.isEmpty() || selectedSignals.contains("gps_location")
            if (selectedFields.isEmpty() && !includeGpsSpeed && !includeGpsLocation) return

            out.println(buildString {
                append("timestamp_ms")
                selectedFields.forEach { field ->
                    append(",").append(field.name)
                }
                if (includeGpsSpeed) {
                    append(",gps_speed_kmh")
                }
                if (includeGpsLocation) {
                    append(",latitude,longitude")
                }
            })

            snapshot.entries.forEach { entry ->
                val data = entry.outputChannelData
                if (data == null || entry.outputChannelBlockSize <= 0) {
                    out.println(buildString {
                        append(entry.timestampMs)
                        if (includeGpsSpeed) {
                            append(",").append(entry.gpsSpeedKph?.let { formatLogValue(it) } ?: "")
                        }
                        if (includeGpsLocation) {
                            append(",").append(entry.gpsLatitude?.let { formatCoordinateValue(it) } ?: "")
                            append(",").append(entry.gpsLongitude?.let { formatCoordinateValue(it) } ?: "")
                        }
                    })
                } else {
                    out.println(buildString {
                        append(entry.timestampMs)
                        selectedFields.forEach { field ->
                            val value = field.parse(data)
                            append(",").append(formatLogValue(value))
                        }
                        if (includeGpsSpeed) {
                            append(",").append(entry.gpsSpeedKph?.let { formatLogValue(it) } ?: "")
                        }
                        if (includeGpsLocation) {
                            append(",").append(entry.gpsLatitude?.let { formatCoordinateValue(it) } ?: "")
                            append(",").append(entry.gpsLongitude?.let { formatCoordinateValue(it) } ?: "")
                        }
                    })
                }
            }
        }

        fun writeSnapshotAsMsl(
            out: java.io.PrintWriter,
            snapshot: LiveLogSnapshot,
            fields: List<OutputField>,
            selectedSignals: Set<String>
        ) {
            val captureDateFormat = SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy", Locale.US)
            out.println("\"speeduino_manager_live_log: ECU Manager LiveData Export\"")
            out.println("\"Capture Date: ${captureDateFormat.format(Date())}, File author: ECU Manager\"")

            if (fields.isEmpty()) {
                val fallbackColumns = listOf(
                    "rpm",
                    "map_kpa",
                    "tps",
                    "coolant_c",
                    "iat_c",
                    "batt_v",
                    "advance_deg",
                    "candidate_speed_kph",
                    "candidate_gear",
                    "candidate_accel_pedal_pos_pct",
                    "candidate_throttle_angle_deg",
                    "candidate_ignition_advance_deg",
                    "o2",
                    "inj_time_ms",
                    "inj_time_mirror_ms",
                    "gps_speed_kmh",
                    "gps_location",
                )
                val effectiveColumns = if (selectedSignals.isEmpty()) {
                    fallbackColumns
                } else {
                    fallbackColumns.filter { selectedSignals.contains(it) }
                }
                if (effectiveColumns.isEmpty()) return

                out.println(buildString {
                    append("Time")
                    effectiveColumns.forEach { column ->
                        when (column) {
                            "gps_location" -> {
                                append('\t').append("latitude")
                                append('\t').append("longitude")
                            }
                            else -> append('\t').append(column)
                        }
                    }
                })
                out.println(buildString {
                    append("s")
                    effectiveColumns.forEach { column ->
                        when (column) {
                            "gps_location" -> {
                                append('\t').append("deg")
                                append('\t').append("deg")
                            }
                            else -> {
                                append('\t').append(
                                    when (column) {
                                        "rpm" -> "rpm"
                                        "map_kpa" -> "kPa"
                                        "tps" -> "%"
                                        "coolant_c", "iat_c" -> "C"
                                        "batt_v" -> "V"
                                        "advance_deg" -> "deg"
                                        "candidate_speed_kph" -> "km/h"
                                        "candidate_gear" -> "gear"
                                        "candidate_accel_pedal_pos_pct" -> "%"
                                        "candidate_throttle_angle_deg" -> "deg"
                                        "candidate_ignition_advance_deg" -> "deg"
                                        "o2" -> "O2"
                                        "inj_time_ms", "inj_time_mirror_ms" -> "ms"
                                        "gps_speed_kmh" -> "km/h"
                                        else -> ""
                                    }
                                )
                            }
                        }
                    }
                })
                val firstTimestampMs = snapshot.entries.firstOrNull()?.timestampMs ?: 0L
                snapshot.entries.forEach { entry ->
                    val relTimeSeconds = (entry.timestampMs - firstTimestampMs).coerceAtLeast(0L) / 1000.0
                    val batt = entry.batteryDeciVolt / 10.0
                    out.println(
                        buildString {
                            append(String.format(Locale.US, "%.3f", relTimeSeconds))
                            effectiveColumns.forEach { column ->
                                append('\t')
                                when (column) {
                                    "rpm" -> append(entry.rpm)
                                    "map_kpa" -> append(entry.mapKpa)
                                    "tps" -> append(entry.tps)
                                    "coolant_c" -> append(entry.coolantTempC)
                                    "iat_c" -> append(entry.intakeTempC)
                                    "batt_v" -> append(String.format(Locale.US, "%.1f", batt))
                                    "advance_deg" -> append(entry.advanceDeg)
                                    "candidate_speed_kph" -> append(entry.candidateSpeedKph ?: "")
                                    "candidate_gear" -> append(entry.candidateGear?.let { if (it == 0) "N" else it.toString() } ?: "")
                                    "candidate_accel_pedal_pos_pct" -> append(entry.candidateAccelPedalPosPct ?: "")
                                    "candidate_throttle_angle_deg" -> append(entry.candidateThrottleAngleDeg?.let { formatLogValue(it) } ?: "")
                                    "candidate_ignition_advance_deg" -> append(entry.candidateIgnitionAdvanceDeg?.let { formatLogValue(it) } ?: "")
                                    "o2" -> append(entry.o2)
                                    "inj_time_ms" -> append(entry.candidateInjectionDurationMs?.let { formatLogValue(it) } ?: "")
                                    "inj_time_mirror_ms" -> append(entry.candidateInjectionDurationMirrorMs?.let { formatLogValue(it) } ?: "")
                                    "gps_speed_kmh" -> append(entry.gpsSpeedKph?.let { formatLogValue(it) } ?: "")
                                    "gps_location" -> {
                                        append(entry.gpsLatitude?.let { formatCoordinateValue(it) } ?: "")
                                        append('\t')
                                        append(entry.gpsLongitude?.let { formatCoordinateValue(it) } ?: "")
                                    }
                                }
                            }
                        }
                    )
                }
                return
            }

            val selectedFields = if (selectedSignals.isEmpty()) {
                fields
            } else {
                fields.filter { selectedSignals.contains(it.name) }
            }
            val includeGpsSpeed = selectedSignals.isEmpty() || selectedSignals.contains("gps_speed_kmh")
            val includeGpsLocation = selectedSignals.isEmpty() || selectedSignals.contains("gps_location")
            if (selectedFields.isEmpty() && !includeGpsSpeed && !includeGpsLocation) return

            out.println(buildString {
                append("Time")
                selectedFields.forEach { field ->
                    append('\t').append(field.name)
                }
                if (includeGpsSpeed) {
                    append('\t').append("gps_speed_kmh")
                }
                if (includeGpsLocation) {
                    append('\t').append("latitude")
                    append('\t').append("longitude")
                }
            })

            out.println(buildString {
                append("s")
                selectedFields.forEach { field ->
                    append('\t').append(formatMslUnit(field.units))
                }
                if (includeGpsSpeed) {
                    append('\t').append("km/h")
                }
                if (includeGpsLocation) {
                    append('\t').append("deg")
                    append('\t').append("deg")
                }
            })

            val firstTimestampMs = snapshot.entries.firstOrNull()?.timestampMs ?: 0L
            snapshot.entries.forEach { entry ->
                val relTimeSeconds = (entry.timestampMs - firstTimestampMs).coerceAtLeast(0L) / 1000.0
                out.println(buildString {
                    append(String.format(Locale.US, "%.3f", relTimeSeconds))
                    val data = entry.outputChannelData
                    selectedFields.forEach { field ->
                        append('\t')
                        if (data != null && entry.outputChannelBlockSize > 0) {
                            val value = field.parse(data)
                            append(formatLogValue(value))
                        }
                    }
                    if (includeGpsSpeed) {
                        append('\t').append(entry.gpsSpeedKph?.let { formatLogValue(it) } ?: "")
                    }
                    if (includeGpsLocation) {
                        append('\t').append(entry.gpsLatitude?.let { formatCoordinateValue(it) } ?: "")
                        append('\t').append(entry.gpsLongitude?.let { formatCoordinateValue(it) } ?: "")
                    }
                })
            }
        }

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
            return if (abs(value - rounded) < 0.0001) {
                rounded.toInt().toString()
            } else {
                String.format(Locale.US, "%.2f", value)
            }
        }

        fun formatCoordinateValue(value: Double): String {
            return String.format(Locale.US, "%.6f", value)
        }
}
