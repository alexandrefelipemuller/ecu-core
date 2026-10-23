package io.ecucore.geolog

/** Paletas do mapa de calor. Cada cor é 0xRRGGBB; o menor valor usa a primeira cor. */
enum class HeatmapPalette(internal val stops: IntArray) {
    /** Azul → ciano → verde → amarelo → vermelho. */
    BLUE_TO_RED(intArrayOf(0x2166AC, 0x00BCD4, 0x4CAF50, 0xFFEB3B, 0xD32F2F)),

    /** Viridis: percebida de forma uniforme e segura para daltônicos. */
    VIRIDIS(intArrayOf(0x440154, 0x3B528B, 0x21918C, 0x5EC962, 0xFDE725))
}

/**
 * Converte o valor de um sensor em cor. [invert] troca os extremos (ex.: velocidade alta = azul,
 * baixa = vermelho). Valores fora de [min,max] são limitados às pontas.
 */
data class HeatmapScale(
    val min: Double,
    val max: Double,
    val palette: HeatmapPalette = HeatmapPalette.BLUE_TO_RED,
    val invert: Boolean = false
) {
    init {
        require(min.isFinite() && max.isFinite()) { "min/max devem ser finitos" }
        require(min <= max) { "min deve ser <= max" }
    }

    /** Posição normalizada 0..1 (já considerando [invert]); 0.5 quando a faixa é degenerada. */
    fun normalize(value: Double): Double {
        val n = if (max == min) 0.5 else ((value - min) / (max - min)).coerceIn(0.0, 1.0)
        return if (invert) 1.0 - n else n
    }

    /** Cor ARGB opaca (0xFFRRGGBB) para [value]. */
    fun colorAt(value: Double): Int = interpolate(palette.stops, normalize(value))

    companion object {
        /** Escala automática pela faixa robusta (percentis) do sensor no trajeto. */
        fun auto(
            track: GeoLogTrack,
            signal: String,
            palette: HeatmapPalette = HeatmapPalette.BLUE_TO_RED,
            invert: Boolean = false
        ): HeatmapScale? {
            val range = track.valueRange(signal) ?: return null
            return HeatmapScale(range.start, range.endInclusive, palette, invert)
        }

        private fun interpolate(stops: IntArray, t: Double): Int {
            val scaled = t.coerceIn(0.0, 1.0) * (stops.size - 1)
            val i = scaled.toInt().coerceAtMost(stops.size - 2)
            val f = scaled - i
            val a = stops[i]
            val b = stops[i + 1]
            fun ch(shift: Int): Int {
                val ca = (a shr shift) and 0xFF
                val cb = (b shr shift) and 0xFF
                return (ca + (cb - ca) * f).toInt().coerceIn(0, 255)
            }
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
