package io.ecucore.shared

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

fun Byte.toHex02(): String =
    (toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')

fun formatDecimal(value: Double, decimals: Int): String {
    if (decimals <= 0) return round(value).toLong().toString()
    val factor = 10.0.pow(decimals)
    val rounded = round(value * factor) / factor
    val negative = rounded < 0
    val absValue = abs(rounded)
    val intPart = absValue.toLong()
    val fracPart = round((absValue - intPart) * factor).toLong()
    val fracStr = fracPart.toString().padStart(decimals, '0')
    return (if (negative) "-" else "") + "$intPart.$fracStr"
}
