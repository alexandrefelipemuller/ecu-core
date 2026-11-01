package io.ecucore.model

data class PressureCalibration(
    val mapMin: Int,
    val mapMax: Int,
    val baroMin: Int,
    val baroMax: Int,
    val emapMin: Int,
    val emapMax: Int
)

data class TpsCalibration(
    val tpsMin: Int,
    val tpsMax: Int,
    /** Maior valor ADC aceito: 255 (Speeduino, U8) ou 1023 (MS2/Extra, S16). */
    val adcMax: Int = ADC_MAX_8BIT
) {
    companion object {
        const val ADC_MAX_8BIT = 255
        const val ADC_MAX_10BIT = 1023
    }
}

/** tpsMin/tpsMax da MS2/Extra: tabela 0x04, S16 big-endian em 518 e 520 (ADC 0..1023). */
const val MS2_TPS_TABLE: Byte = 0x04
const val MS2_TPS_MIN_OFFSET = 518
const val MS2_TPS_MAX_OFFSET = 520
const val MS2_TPS_BYTES = 4 // min (518..519) + max (520..521), adjacentes

fun encodeMs2TpsCalibration(c: TpsCalibration): ByteArray = byteArrayOf(
    (c.tpsMin shr 8).toByte(), c.tpsMin.toByte(),
    (c.tpsMax shr 8).toByte(), c.tpsMax.toByte(),
)

fun decodeMs2TpsCalibration(raw: ByteArray): TpsCalibration {
    require(raw.size >= MS2_TPS_BYTES) { "TPS MS2 incompleto: ${raw.size} bytes" }
    fun s16(i: Int) = (raw[i].toInt() shl 8) or (raw[i + 1].toInt() and 0xFF)
    return TpsCalibration(s16(0), s16(2), TpsCalibration.ADC_MAX_10BIT)
}
