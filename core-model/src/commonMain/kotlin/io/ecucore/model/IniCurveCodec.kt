package io.ecucore.model

/** Codifica/decodifica arrays 1D do .ini (U08/S08/U16/S16 big-endian) com scale/translate: valor = (raw + translate) * scale. */
object IniCurveCodec {
    fun decode(bytes: ByteArray, width: Int, signed: Boolean, scale: Double, translate: Double): List<Double> =
        (0 until bytes.size / width).map { i ->
            var raw = if (width == 2) {
                ((bytes[i * 2].toInt() and 0xFF) shl 8) or (bytes[i * 2 + 1].toInt() and 0xFF)
            } else {
                bytes[i].toInt() and 0xFF
            }
            if (signed && raw >= (1 shl (8 * width - 1))) raw -= 1 shl (8 * width)
            (raw + translate) * scale
        }

    fun encode(values: List<Double>, width: Int, signed: Boolean, scale: Double, translate: Double): ByteArray {
        val range = if (signed) -(1 shl (8 * width - 1)) until (1 shl (8 * width - 1)) else 0 until (1 shl (8 * width))
        val out = ByteArray(values.size * width)
        values.forEachIndexed { i, v ->
            val raw = kotlin.math.round(v / scale - translate).toInt()
            require(raw in range) { "Valor $v fora do alcance do campo" }
            if (width == 2) {
                out[i * 2] = ((raw shr 8) and 0xFF).toByte()
                out[i * 2 + 1] = (raw and 0xFF).toByte()
            } else {
                out[i] = (raw and 0xFF).toByte()
            }
        }
        return out
    }
}
