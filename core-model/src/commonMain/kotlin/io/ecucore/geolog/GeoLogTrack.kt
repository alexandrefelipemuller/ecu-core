package io.ecucore.geolog

import kotlin.math.asin
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Amostra crua de um log: instante, posição GPS (pode faltar) e valores dos sensores. */
data class GeoLogSample(
    val timeSec: Double,
    val latitude: Double?,
    val longitude: Double?,
    val values: Map<String, Double> = emptyMap()
)

/** Ponto do trajeto já validado. [index] aponta para a amostra correspondente em [GeoLogTrack]. */
data class GeoLogPoint(
    val timeSec: Double,
    val latitude: Double,
    val longitude: Double
)

data class GeoPosition(val latitude: Double, val longitude: Double)

data class GeoBounds(
    val minLatitude: Double,
    val maxLatitude: Double,
    val minLongitude: Double,
    val maxLongitude: Double
)

/**
 * Trajeto de um log com GPS: posições válidas e suavizadas + séries dos sensores alinhadas
 * ao mesmo eixo de tempo. Independente de UI/mapa, serve Android, Desktop e iOS.
 */
class GeoLogTrack private constructor(
    val points: List<GeoLogPoint>,
    private val series: Map<String, DoubleArray>
) {
    private val times: DoubleArray = DoubleArray(points.size) { points[it].timeSec }

    val isEmpty: Boolean get() = points.isEmpty()
    val startSec: Double get() = times.firstOrNull() ?: 0.0
    val endSec: Double get() = times.lastOrNull() ?: 0.0
    val durationSec: Double get() = endSec - startSec
    val signalNames: List<String> get() = series.keys.toList()

    fun hasSignal(name: String): Boolean = series.containsKey(name)

    /** Índice do último ponto com tempo <= [timeSec] (limitado ao intervalo do trajeto). */
    fun indexAt(timeSec: Double): Int {
        if (points.isEmpty()) return -1
        if (timeSec <= times.first()) return 0
        if (timeSec >= times.last()) return times.lastIndex
        var lo = 0
        var hi = times.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (times[mid] <= timeSec) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Posição interpolada no instante [timeSec]. */
    fun positionAt(timeSec: Double): GeoPosition? {
        val i = indexAt(timeSec)
        if (i < 0) return null
        val a = points[i]
        val b = points.getOrNull(i + 1) ?: return GeoPosition(a.latitude, a.longitude)
        val f = fraction(a.timeSec, b.timeSec, timeSec)
        return GeoPosition(
            a.latitude + (b.latitude - a.latitude) * f,
            a.longitude + (b.longitude - a.longitude) * f
        )
    }

    /** Valor do sensor interpolado no instante [timeSec]; null se o sensor não existe ou não há dado ali. */
    fun valueAt(signal: String, timeSec: Double): Double? {
        val data = series[signal] ?: return null
        val i = indexAt(timeSec)
        if (i < 0) return null
        val a = data[i]
        val b = data.getOrNull(i + 1) ?: return a.takeUnless { it.isNaN() }
        return when {
            a.isNaN() && b.isNaN() -> null
            a.isNaN() -> null
            b.isNaN() -> a
            else -> a + (b - a) * fraction(times[i], times[i + 1], timeSec)
        }
    }

    /** Valores brutos do sensor por ponto (NaN onde não há dado); null se o sensor não existe. */
    fun seriesValues(signal: String): DoubleArray? = series[signal]?.copyOf()

    /** Faixa [min,max] do sensor ignorando os extremos (percentis), para o mapa de calor não ser distorcido por outliers. */
    fun valueRange(signal: String, lowerPercentile: Double = 0.02, upperPercentile: Double = 0.98): ClosedFloatingPointRange<Double>? {
        val sorted = series[signal]?.filter { !it.isNaN() }?.sorted() ?: return null
        if (sorted.isEmpty()) return null
        fun pick(p: Double) = sorted[(p.coerceIn(0.0, 1.0) * sorted.lastIndex).toInt()]
        return pick(lowerPercentile)..pick(upperPercentile)
    }

    fun bounds(): GeoBounds? {
        if (points.isEmpty()) return null
        return GeoBounds(
            minLatitude = points.minOf { it.latitude },
            maxLatitude = points.maxOf { it.latitude },
            minLongitude = points.minOf { it.longitude },
            maxLongitude = points.maxOf { it.longitude }
        )
    }

    /** Distância total percorrida em metros. */
    fun totalDistanceMeters(): Double =
        points.zipWithNext().sumOf { (a, b) -> haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude) }

    /** Instante do ponto do trajeto mais próximo de uma coordenada (toque no mapa). */
    fun nearestTimeSec(latitude: Double, longitude: Double): Double? =
        points.minByOrNull { squaredDegrees(it.latitude, it.longitude, latitude, longitude) }?.timeSec

    /**
     * Reduz o trajeto a no máximo [maxPoints] índices para desenho, sempre mantendo primeiro e último.
     * Retorna índices crescentes de [points].
     */
    fun decimatedIndices(maxPoints: Int): List<Int> {
        require(maxPoints >= 2) { "maxPoints deve ser >= 2" }
        if (points.size <= maxPoints) return points.indices.toList()
        val step = (points.size - 1).toDouble() / (maxPoints - 1)
        return (0 until maxPoints).map { (it * step).toInt().coerceAtMost(points.lastIndex) }.distinct()
    }

    private fun fraction(t0: Double, t1: Double, t: Double): Double =
        if (t1 <= t0) 0.0 else ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0)

    private fun squaredDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = lat1 - lat2
        val dLon = (lon1 - lon2) * cos(lat2 * PI_OVER_180)
        return dLat * dLat + dLon * dLon
    }

    companion object {
        private const val PI_OVER_180 = 0.017453292519943295
        private const val EARTH_RADIUS_M = 6_371_000.0

        /** Acima disso entre dois fixes consecutivos o salto é tratado como erro de GPS (≈ 430 km/h). */
        const val DEFAULT_MAX_SPEED_MPS = 120.0

        val EMPTY = GeoLogTrack(emptyList(), emptyMap())

        fun isValidCoordinate(latitude: Double?, longitude: Double?): Boolean {
            if (latitude == null || longitude == null) return false
            if (latitude.isNaN() || longitude.isNaN()) return false
            if (abs(latitude) > 90.0 || abs(longitude) > 180.0) return false
            // (0,0) é o valor "sem fix" típico; ninguém roda no Golfo da Guiné.
            return !(abs(latitude) < 1e-6 && abs(longitude) < 1e-6)
        }

        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = (lat2 - lat1) * PI_OVER_180
            val dLon = (lon2 - lon1) * PI_OVER_180
            val a = sin(dLat / 2).pow(2) +
                cos(lat1 * PI_OVER_180) * cos(lat2 * PI_OVER_180) * sin(dLon / 2).pow(2)
            return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
        }

        /**
         * Monta o trajeto a partir de amostras cruas (em ordem de tempo):
         *  1. descarta coordenadas inválidas e fixes com salto impossível;
         *  2. a posição só muda quando chega um fix novo (GPS ~1 Hz contra log em 10+ Hz), então
         *     interpola linearmente entre fixes para evitar o trajeto "em degraus";
         *  3. alinha os sensores às amostras mantidas (NaN onde faltou valor).
         * Amostras antes do primeiro fix válido são descartadas.
         */
        fun build(samples: List<GeoLogSample>, maxSpeedMps: Double = DEFAULT_MAX_SPEED_MPS): GeoLogTrack {
            val ordered = samples.filter { it.timeSec.isFinite() }.sortedBy { it.timeSec }

            // Índices (em `ordered`) onde começa um fix novo e válido.
            val fixes = mutableListOf<Int>()
            var lastLat = Double.NaN
            var lastLon = Double.NaN
            for ((i, s) in ordered.withIndex()) {
                if (!isValidCoordinate(s.latitude, s.longitude)) continue
                val lat = s.latitude!!
                val lon = s.longitude!!
                if (fixes.isNotEmpty() && lat == lastLat && lon == lastLon) continue
                if (fixes.isNotEmpty()) {
                    val prev = ordered[fixes.last()]
                    val dt = s.timeSec - prev.timeSec
                    val dist = haversineMeters(prev.latitude!!, prev.longitude!!, lat, lon)
                    if (dist > 0.0 && (dt <= 0.0 || dist / dt > maxSpeedMps)) continue
                }
                fixes += i
                lastLat = lat
                lastLon = lon
            }
            if (fixes.isEmpty()) return EMPTY

            val kept = ordered.subList(fixes.first(), ordered.size)
            val offset = fixes.first()
            val points = ArrayList<GeoLogPoint>(kept.size)
            var fixPos = 0
            for ((rel, s) in kept.withIndex()) {
                val idx = rel + offset
                while (fixPos + 1 < fixes.size && fixes[fixPos + 1] <= idx) fixPos++
                val a = ordered[fixes[fixPos]]
                val b = ordered.getOrNull(fixes.getOrNull(fixPos + 1) ?: -1)
                val lat: Double
                val lon: Double
                if (b == null) {
                    lat = a.latitude!!; lon = a.longitude!!
                } else {
                    val f = if (b.timeSec <= a.timeSec) 0.0 else ((s.timeSec - a.timeSec) / (b.timeSec - a.timeSec)).coerceIn(0.0, 1.0)
                    lat = a.latitude!! + (b.latitude!! - a.latitude) * f
                    lon = a.longitude!! + (b.longitude!! - a.longitude) * f
                }
                points += GeoLogPoint(s.timeSec, lat, lon)
            }

            val names = LinkedHashSet<String>()
            kept.forEach { names.addAll(it.values.keys) }
            val series = names.associateWith { name ->
                DoubleArray(kept.size) { kept[it].values[name] ?: Double.NaN }
            }
            return GeoLogTrack(points, series)
        }
    }
}
