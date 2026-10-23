package io.ecucore.geolog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun s(t: Double, lat: Double?, lon: Double?, vararg v: Pair<String, Double>) =
    GeoLogSample(t, lat, lon, mapOf(*v))

class GeoLogTrackTest {

    @Test
    fun descartaCoordenadasInvalidasEAntesDoPrimeiroFix() {
        val track = GeoLogTrack.build(
            listOf(
                s(0.0, null, null),
                s(0.1, 0.0, 0.0),
                s(0.2, 95.0, 10.0),
                s(0.3, -23.0, -46.0),
                s(0.4, -23.0, -46.0)
            )
        )
        assertEquals(2, track.points.size)
        assertEquals(0.3, track.startSec, 1e-9)
    }

    @Test
    fun semGpsResultaEmTrajetoVazio() {
        val track = GeoLogTrack.build(listOf(s(0.0, null, null), s(1.0, 0.0, 0.0)))
        assertTrue(track.isEmpty)
        assertNull(track.positionAt(0.5))
        assertNull(track.bounds())
    }

    @Test
    fun interpolaPosicaoEntreFixesParaRemoverDegraus() {
        // Fix a 1 Hz, amostras a 4 Hz: posição repetida deve virar rampa.
        val samples = (0..8).map { i ->
            val fixSec = i / 4
            s(i * 0.25, -23.0 + fixSec * 0.001, -46.0)
        }
        val track = GeoLogTrack.build(samples)
        val mid = track.positionAt(0.5)!!
        assertEquals(-23.0 + 0.0005, mid.latitude, 1e-9)
    }

    @Test
    fun descartaSaltoImpossivelDeGps() {
        val track = GeoLogTrack.build(
            listOf(
                s(0.0, -23.0, -46.0),
                s(1.0, -23.0001, -46.0),
                s(2.0, 10.0, 10.0), // salto de milhares de km em 1 s
                s(3.0, -23.0003, -46.0)
            )
        )
        assertTrue(track.points.all { it.latitude < -22.0 })
    }

    @Test
    fun valueAtInterpolaEIgnoraAusencia() {
        val track = GeoLogTrack.build(
            listOf(
                s(0.0, -23.0, -46.0, "rpm" to 1000.0),
                s(1.0, -23.0001, -46.0, "rpm" to 3000.0),
                s(2.0, -23.0002, -46.0)
            )
        )
        assertEquals(2000.0, track.valueAt("rpm", 0.5)!!, 1e-9)
        assertEquals(3000.0, track.valueAt("rpm", 1.0)!!, 1e-9)
        assertNull(track.valueAt("rpm", 2.0))
        assertNull(track.valueAt("inexistente", 0.5))
    }

    @Test
    fun indexAtLimitaAosExtremos() {
        val track = GeoLogTrack.build((0..4).map { s(it.toDouble(), -23.0 + it * 0.0001, -46.0) })
        assertEquals(0, track.indexAt(-5.0))
        assertEquals(4, track.indexAt(99.0))
        assertEquals(2, track.indexAt(2.4))
    }

    @Test
    fun valueRangeUsaPercentisParaIgnorarOutliers() {
        val samples = (0..99).map { s(it.toDouble(), -23.0 + it * 0.0001, -46.0, "v" to if (it == 50) 10_000.0 else it.toDouble()) }
        val range = GeoLogTrack.build(samples).valueRange("v")!!
        assertTrue(range.endInclusive < 100.0)
    }

    @Test
    fun decimatedIndicesMantemPrimeiroEUltimo() {
        val track = GeoLogTrack.build((0..999).map { s(it.toDouble(), -23.0 + it * 1e-5, -46.0) })
        val idx = track.decimatedIndices(50)
        assertTrue(idx.size <= 50)
        assertEquals(0, idx.first())
        assertEquals(999, idx.last())
        assertEquals(idx.sorted(), idx)
    }

    @Test
    fun distanciaETocaNoMapa() {
        val track = GeoLogTrack.build(listOf(s(0.0, 0.0, 1.0), s(1.0, 0.001, 1.0), s(2.0, 0.002, 1.0)))
        assertEquals(222.4, track.totalDistanceMeters(), 1.0)
        assertEquals(2.0, track.nearestTimeSec(0.0021, 1.0)!!, 1e-9)
        assertNotNull(track.bounds())
    }
}

class HeatmapScaleTest {

    @Test
    fun extremosUsamPrimeiraEUltimaCor() {
        val scale = HeatmapScale(0.0, 100.0)
        assertEquals(0xFF2166AC.toInt(), scale.colorAt(0.0))
        assertEquals(0xFFD32F2F.toInt(), scale.colorAt(100.0))
    }

    @Test
    fun valoresForaDaFaixaSaoLimitados() {
        val scale = HeatmapScale(10.0, 20.0)
        assertEquals(scale.colorAt(10.0), scale.colorAt(-500.0))
        assertEquals(scale.colorAt(20.0), scale.colorAt(500.0))
    }

    @Test
    fun invertTrocaOsExtremos() {
        val normal = HeatmapScale(0.0, 100.0)
        val inv = HeatmapScale(0.0, 100.0, invert = true)
        assertEquals(normal.colorAt(0.0), inv.colorAt(100.0))
        assertEquals(1.0, inv.normalize(0.0), 1e-9)
    }

    @Test
    fun faixaDegeneradaNaoQuebra() {
        val scale = HeatmapScale(5.0, 5.0)
        assertEquals(0.5, scale.normalize(5.0), 1e-9)
        assertEquals(0xFF, scale.colorAt(5.0) ushr 24)
    }

    @Test
    fun autoUsaFaixaDoSensor() {
        val track = GeoLogTrack.build((0..9).map { s(it.toDouble(), -23.0 + it * 1e-4, -46.0, "v" to it.toDouble()) })
        assertNotNull(HeatmapScale.auto(track, "v"))
        assertNull(HeatmapScale.auto(track, "nada"))
    }
}

class GeoLogCsvParserTest {

    private val csv = listOf(
        "timestamp_ms,rpm,gps_speed_kmh,latitude,longitude",
        "1000,2000,10.0,-23.00000,-46.00000",
        "1250,2500,10.0,-23.00000,-46.00000",
        "2000,3000,20.0,-23.00010,-46.00000",
        "2250,3500,,,"
    )

    @Test
    fun leTrajetoESensores() {
        val track = GeoLogCsvParser.parse(csv)
        assertFalse(track.isEmpty)
        assertEquals(0.0, track.startSec, 1e-9)
        assertTrue(track.hasSignal("rpm"))
        assertTrue(track.hasSignal("gps_speed_kmh"))
        assertFalse(track.hasSignal("latitude"))
        assertEquals(2000.0, track.valueAt("rpm", 0.0)!!, 1e-9)
    }

    @Test
    fun logSemColunasDeGpsEVazio() {
        val track = GeoLogCsvParser.parse(listOf("timestamp_ms,rpm", "0,1000", "100,1100"))
        assertTrue(track.isEmpty)
        assertFalse(GeoLogCsvParser.hasGpsColumns("timestamp_ms,rpm"))
        assertTrue(GeoLogCsvParser.hasGpsColumns("timestamp_ms,rpm,latitude,longitude"))
    }

    @Test
    fun colunasGpsVaziasResultamEmTrajetoVazio() {
        val track = GeoLogCsvParser.parse(listOf("timestamp_ms,latitude,longitude", "0,,", "100,,"))
        assertTrue(track.isEmpty)
    }
}
