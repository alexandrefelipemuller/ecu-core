package io.ecucore.dashboard

import io.ecucore.EcuLiveData
import io.ecucore.model.EcuOutputChannels

/**
 * Resolução de sinais do dashboard (nome do campo → valor + unidade), compartilhada entre os apps.
 * Ordem: sinais virtuais/derivados, sinais canônicos, canal de saída da ECU pelo nome, fallbacks (OBD2/GPS).
 */
const val PETROL_CENTER_SIGNAL_BOOST_BAR = "petrol_center_boost_bar"
const val PETROL_CENTER_SIGNAL_BOOST_KPA = "petrol_center_boost_kpa"
const val PETROL_CENTER_SIGNAL_BOOST_PSI = "petrol_center_boost_psi"

val SIGNAL_UNIT_OVERRIDES = mapOf(
    "afr" to "AFR",
    "afr2" to "AFR",
    "afrTarget" to "AFR",
    "targetAFR" to "AFR",
    "afrtgt1" to "AFR",
    "lambda" to "",
    "lam" to "",
    "λ" to "",
    "lambdaValue" to "",
    "targetLambda" to "",
    "candidateAccelPedalPosPct" to "%",
    "candidateIgnitionAdvanceDeg" to "deg",
    "candidateInjectionDurationMs" to "ms",
    "candidateInjectionDurationMirrorMs" to "ms",
    "candidateGear" to "gear",
    "candidateSpeedKph" to "km/h",
    "gpsSpeedKph" to "km/h",
    "candidateThrottleAngleDeg" to "deg"
)

fun resolveSignalSourceValue(
    liveData: EcuLiveData?,
    fieldName: String,
    stoichRatio: Double = 14.7
): Pair<Double, String>? {
    if (liveData == null) return null

    when (fieldName) {
        PETROL_CENTER_SIGNAL_BOOST_KPA -> return (liveData.mapPressure - 100).toDouble() to "kPa"
        PETROL_CENTER_SIGNAL_BOOST_BAR -> return (liveData.mapPressure - 100).toDouble() / 100.0 to "bar"
        PETROL_CENTER_SIGNAL_BOOST_PSI -> return (liveData.mapPressure - 100).toDouble() * 0.1450377377 to "psi"
        "lambda", "lam", "λ" -> {
            val ratio = stoichRatio.takeIf { it > 0.0 } ?: 14.7
            return (liveData.o2.toDouble() / ratio) to ""
        }
        "candidateSpeedKph" -> return liveData.candidateSpeedKph?.toDouble()?.let { it to "km/h" }
        "gpsSpeedKph" -> return liveData.gpsSpeedKph?.let { it to "km/h" }
        "candidateGear" -> return liveData.candidateGear?.toDouble()?.let { it to "gear" }
        "candidateAccelPedalPosPct" -> return liveData.candidateAccelPedalPosPct?.toDouble()?.let { it to "%" }
        "candidateThrottleAngleDeg" -> return liveData.candidateThrottleAngleDeg?.let { it to "deg" }
        "candidateIgnitionAdvanceDeg" -> return liveData.candidateIgnitionAdvanceDeg?.let { it to "deg" }
        "candidateInjectionDurationMs" -> return liveData.candidateInjectionDurationMs?.let { it to "ms" }
        "candidateInjectionDurationMirrorMs" -> return liveData.candidateInjectionDurationMirrorMs?.let { it to "ms" }
    }

    val data = liveData.outputChannelData
    val blockSize = liveData.outputChannelBlockSize
    canonicalLiveSignal(liveData, fieldName)?.let { return it }
    if (data != null && blockSize > 0) {
        val field = EcuOutputChannels.getField(blockSize, fieldName)
        if (field != null) {
            val units = SIGNAL_UNIT_OVERRIDES[fieldName] ?: field.units
            return field.parse(data) to units
        }
    }

    if (fieldName == "vss") {
        // OBD2/PSA sessions never populate native Speeduino output channels (no "vss"
        // field), but they do decode speed from the ECU (candidateSpeedKph) or GPS
        // (gpsSpeedKph) — fall back to those so speed gauges aren't left blank.
        liveData.candidateSpeedKph?.toDouble()?.let { return it to "km/h" }
        liveData.gpsSpeedKph?.let { return it to "km/h" }
    }

    return when (fieldName) {
        "lambdaValue",
        "targetLambda",
        "targetAFR",
        "afrTarget",
        "afrtgt1",
        "lambda",
        "lam",
        "λ" -> {
            val ratio = stoichRatio.takeIf { it > 0.0 } ?: 14.7
            liveData.o2.toDouble() / ratio to ""
        }
        "rpm" -> liveData.rpm.toDouble() to "rpm"
        "map" -> liveData.mapPressure.toDouble() to "kPa"
        "tps" -> liveData.tps.toDouble() to "%"
        "batteryVoltage" -> liveData.batteryVoltage to "V"
        "advance" -> liveData.advance.toDouble() to "deg"
        "afr" -> liveData.o2.toDouble() to "AFR"
        else -> null
    }
}

private fun canonicalLiveSignal(liveData: EcuLiveData, fieldName: String): Pair<Double, String>? = when (fieldName) {
    "rpm" -> liveData.rpm.toDouble() to "rpm"
    "map" -> liveData.mapPressure.toDouble() to "kPa"
    "coolant", "coolantRaw" -> liveData.coolantTemp.toDouble() to "°C"
    "iat", "intake", "iatRaw" -> liveData.intakeTemp.toDouble() to "°C"
    "tps" -> liveData.tps.toDouble() to "%"
    "batteryVoltage" -> liveData.batteryVoltage to "V"
    "advance" -> liveData.advance.toDouble() to "deg"
    "afr" -> liveData.o2.toDouble() to "AFR"
    else -> null
}
