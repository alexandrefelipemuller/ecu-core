package io.ecucore.model

import kotlin.math.abs
import kotlin.math.round

/** Número com [digits] casas decimais fixas, sem depender de formatação de plataforma (KMP). */
internal fun formatFixed(value: Double, digits: Int): String {
    if (digits <= 0) return round(value).toLong().toString()
    var factor = 1.0
    repeat(digits) { factor *= 10.0 }
    val scaled = round(value * factor).toLong()
    val negative = scaled < 0
    val padded = abs(scaled).toString().padStart(digits + 1, '0')
    val text = padded.substring(0, padded.length - digits) + "." + padded.substring(padded.length - digits)
    return if (negative) "-$text" else text
}
