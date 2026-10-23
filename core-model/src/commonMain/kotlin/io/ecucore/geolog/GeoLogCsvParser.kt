package io.ecucore.geolog

/**
 * Lê o CSV de log do app (cabeçalho com `timestamp...`, e opcionalmente `latitude,longitude`)
 * e devolve um [GeoLogTrack]. Valores ficam nas unidades do arquivo; conversão de unidade é da UI.
 * Logs sem GPS válido resultam em [GeoLogTrack.isEmpty].
 */
object GeoLogCsvParser {
    private val LAT_NAMES = setOf("latitude", "lat", "gps_latitude")
    private val LON_NAMES = setOf("longitude", "lon", "lng", "gps_longitude")

    /** True se o cabeçalho tem colunas de latitude e longitude (não garante fixes válidos). */
    fun hasGpsColumns(header: String): Boolean {
        val cols = header.split(',').map { it.trim().lowercase() }
        return cols.any { it in LAT_NAMES } && cols.any { it in LON_NAMES }
    }

    fun parse(lines: List<String>): GeoLogTrack {
        if (lines.size < 2) return GeoLogTrack.EMPTY
        val headers = lines.first().split(',').map { it.trim() }
        val lower = headers.map { it.lowercase() }
        val latIdx = lower.indexOfFirst { it in LAT_NAMES }
        val lonIdx = lower.indexOfFirst { it in LON_NAMES }
        if (latIdx < 0 || lonIdx < 0) return GeoLogTrack.EMPTY
        val tsIdx = lower.indexOfFirst { it.contains("timestamp") }.takeIf { it >= 0 } ?: 0
        val valueIdx = headers.indices.filter { it != tsIdx && it != latIdx && it != lonIdx }

        var base: Long? = null
        val samples = ArrayList<GeoLogSample>(lines.size - 1)
        for (line in lines.drop(1)) {
            val cols = line.split(',')
            val ts = cols.getOrNull(tsIdx)?.trim()?.toLongOrNull() ?: continue
            val anchor = base ?: ts.also { base = it }
            val values = HashMap<String, Double>()
            for (i in valueIdx) {
                cols.getOrNull(i)?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { values[headers[i]] = it }
            }
            samples += GeoLogSample(
                timeSec = (ts - anchor) / 1000.0,
                latitude = cols.getOrNull(latIdx)?.trim()?.toDoubleOrNull(),
                longitude = cols.getOrNull(lonIdx)?.trim()?.toDoubleOrNull(),
                values = values
            )
        }
        return GeoLogTrack.build(samples)
    }
}
