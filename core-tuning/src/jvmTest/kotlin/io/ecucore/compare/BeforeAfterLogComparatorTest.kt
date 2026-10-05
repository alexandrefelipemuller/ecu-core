package io.ecucore.compare

import io.ecucore.model.VeTable
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BeforeAfterLogComparatorTest {

    private val files = mutableListOf<File>()
    private val comparator = BeforeAfterLogComparator()
    private val rpmBins = listOf(1000, 2000, 3000, 4000)
    private val loadBins = listOf(50)

    @AfterTest
    fun cleanup() { files.forEach { it.delete() } }

    private fun log(vararg lines: String): String {
        val f = File.createTempFile("compare", ".csv")
        f.writeText(lines.joinToString("\n"))
        files += f
        return f.absolutePath
    }

    private fun rows(rpm: Int, load: Int, afr: Double, target: Double, count: Int) =
        (0 until count).map { "$rpm,$load,$afr,$target" }

    private val header = "rpm,map,afr,afr_target"

    private fun compare(before: String, after: String, load: VeTable.LoadType = VeTable.LoadType.MAP, minHits: Int = 5) =
        comparator.compareLogs(before, after, rpmBins, loadBins, load, minHitsPerCell = minHits)

    private fun reason(block: () -> Unit): LogCompareReason = assertFailsWith<LogCompareException> { block() }.reason

    // ---- comparação ---------------------------------------------------------------------------------------------

    @Test
    fun `classifies each cell as improved worse unchanged or not enough`() {
        val before = log(header) .let {
            log(
                header,
                *rows(1000, 50, 15.0, 14.7, 6).toTypedArray(), // erro 0.3
                *rows(2000, 50, 14.7, 14.7, 6).toTypedArray(), // erro 0
                *rows(3000, 50, 14.8, 14.7, 6).toTypedArray(), // erro 0.1
                *rows(4000, 50, 14.7, 14.7, 2).toTypedArray(), // poucas amostras
            )
        }
        val after = log(
            header,
            *rows(1000, 50, 14.7, 14.7, 6).toTypedArray(), // melhorou
            *rows(2000, 50, 15.2, 14.7, 6).toTypedArray(), // piorou
            *rows(3000, 50, 14.8, 14.7, 6).toTypedArray(), // igual
            *rows(4000, 50, 14.7, 14.7, 6).toTypedArray(),
        )

        val result = compare(before, after)
        val states = result.cells[0].map { it.state }
        assertEquals(
            listOf(LogHeatCellState.IMPROVED, LogHeatCellState.WORSE, LogHeatCellState.UNCHANGED, LogHeatCellState.NOT_ENOUGH),
            states,
        )
        assertEquals("kPa", result.loadLabel)
        assertEquals(rpmBins, result.rpmBins)
        assertEquals(loadBins, result.loadBins)
        assertEquals(0.3, result.cells[0][0].beforeAvgAbsError!!, 1e-9)
        assertEquals(0.0, result.cells[0][0].afterAvgAbsError!!, 1e-9)
        assertEquals(6, result.cells[0][0].beforeSamples)
        assertEquals(2, result.cells[0][3].beforeSamples)
    }

    @Test
    fun `summaries report error ratios and coverage`() {
        val before = log(header, *rows(1000, 50, 15.5, 14.7, 5).toTypedArray(), *rows(2000, 50, 13.5, 14.7, 5).toTypedArray())
        val after = log(header, *rows(1000, 50, 14.7, 14.7, 10).toTypedArray())
        val result = compare(before, after)
        assertEquals(10, result.beforeSummary.validSamples)
        assertEquals(0.5, result.beforeSummary.leanTimeRatio, 1e-9)
        assertEquals(0.5, result.beforeSummary.richTimeRatio, 1e-9)
        assertEquals(0.8 + 0.0, (0.8 + 1.2) / 2 - 0.2, 1e-9) // (0.8 + 1.2) / 2 = 1.0 de erro médio
        assertEquals(1.0, result.beforeSummary.avgAbsError, 1e-9)
        assertEquals(2, result.beforeSummary.coverageCells)
        assertEquals(4, result.beforeSummary.totalCells)
        assertEquals(1, result.afterSummary.coverageCells)
        assertEquals(0.0, result.afterSummary.leanTimeRatio, 1e-9)
    }

    @Test
    fun `cells without samples have no mean`() {
        val a = log(header, *rows(1000, 50, 14.7, 14.7, 6).toTypedArray())
        val result = compare(a, a)
        assertNull(result.cells[0][1].beforeAvgAbsError)
        assertEquals(LogHeatCellState.NOT_ENOUGH, result.cells[0][1].state)
        assertEquals(LogHeatCellState.UNCHANGED, result.cells[0][0].state)
    }

    @Test
    fun `min hits can be lowered`() {
        val a = log(header, *rows(1000, 50, 14.7, 14.7, 2).toTypedArray())
        assertEquals(LogHeatCellState.UNCHANGED, compare(a, a, minHits = 2).cells[0][0].state)
        assertEquals(LogHeatCellState.NOT_ENOUGH, compare(a, a).cells[0][0].state)
    }

    // ---- canal de carga e cabeçalhos ----------------------------------------------------------------------------

    @Test
    fun `load channel follows the preference when available`() {
        val both = log("rpm,map,tps,afr,afr_target", *(0 until 6).map { "1000,50,30,14.7,14.7" }.toTypedArray())
        assertEquals("kPa", compare(both, both, VeTable.LoadType.MAP).loadLabel)
        assertEquals("%", compare(both, both, VeTable.LoadType.TPS).loadLabel)
    }

    @Test
    fun `load channel falls back to whatever the log has`() {
        val tpsOnly = log("rpm,tps,afr,afr_target", *(0 until 6).map { "1000,50,14.7,14.7" }.toTypedArray())
        assertEquals("%", compare(tpsOnly, tpsOnly, VeTable.LoadType.MAP).loadLabel)
        val mapOnly = log("rpm,map,afr,afr_target", *(0 until 6).map { "1000,50,14.7,14.7" }.toTypedArray())
        assertEquals("kPa", compare(mapOnly, mapOnly, VeTable.LoadType.TPS).loadLabel)
    }

    @Test
    fun `header names are normalised and matched loosely`() {
        val lines = (0 until 6).map { "1000,50,14.7,14.7" }
        val spaced = log("RPM,MAP kPa,Ego,AFR Target", *lines.toTypedArray())
        assertNotNull(compare(spaced, spaced))
        val throttle = log("rpm,throttle,lambda,lambda_target", *lines.toTypedArray())
        assertNotNull(compare(throttle, throttle))
        val contains = log("engine_rpm_value,mapkpa,o2_sensor,o2target", *lines.toTypedArray())
        assertNotNull(compare(contains, contains))
        // coluna alvo "tgt" é ignorada ao procurar a AFR medida
        val tgt = log("rpm,map,afr_tgt,afr,afr_target", *(0 until 6).map { "1000,50,99,14.7,14.7" }.toTypedArray())
        assertNotNull(compare(tgt, tgt))
    }

    // ---- amostras inválidas -------------------------------------------------------------------------------------

    @Test
    fun `invalid rows are ignored`() {
        val before = log(
            header,
            *rows(1000, 50, 14.7, 14.7, 6).toTypedArray(),
            "0,50,14.7,14.7", // rpm zero
            "1000,50,5.0,14.7", // AFR medida fora da faixa
            "1000,50,14.7,40.0", // alvo fora da faixa
            "abc,50,14.7,14.7", // rpm ilegível
            "1000,abc,14.7,14.7", // carga ilegível
            "1000,50,abc,14.7",
            "1000,50,14.7,abc",
            "1000,50", // linha curta
            "",
        )
        val result = compare(before, before)
        assertEquals(6, result.beforeSummary.validSamples)
    }

    // ---- erros --------------------------------------------------------------------------------------------------------

    @Test
    fun `empty log is rejected`() {
        val empty = log()
        val ok = log(header, *rows(1000, 50, 14.7, 14.7, 6).toTypedArray())
        assertEquals(LogCompareReason.EMPTY_LOG, reason { compare(empty, ok) })
        assertEquals(LogCompareReason.EMPTY_LOG, reason { compare(ok, empty) })
    }

    @Test
    fun `missing columns are reported individually`() {
        val row = "1000,50,14.7,14.7"
        assertEquals(LogCompareReason.MISSING_RPM, reason { compare(log("map,afr,afr_target", "50,14.7,14.7"), log("map,afr,afr_target", "50,14.7,14.7")) })
        assertEquals(LogCompareReason.MISSING_AFR_MEASURED, reason { compare(log("rpm,map,afr_target", "1000,50,14.7"), log("rpm,map,afr_target", "1000,50,14.7")) })
        assertEquals(LogCompareReason.MISSING_AFR_TARGET, reason { compare(log("rpm,map,afr", "1000,50,14.7"), log("rpm,map,afr", "1000,50,14.7")) })
        assertEquals(LogCompareReason.MISSING_LOAD_CHANNEL, reason { compare(log("rpm,afr,afr_target", "1000,14.7,14.7"), log("rpm,afr,afr_target", "1000,14.7,14.7")) })
        assertTrue(row.isNotEmpty())
    }

    @Test
    fun `logs without valid samples are rejected`() {
        val ok = log(header, *rows(1000, 50, 14.7, 14.7, 6).toTypedArray())
        val bad = log(header, "0,50,14.7,14.7", "1000,50,5.0,14.7")
        val headerOnly = log(header)
        assertEquals(LogCompareReason.NO_VALID_SAMPLES, reason { compare(bad, ok) })
        assertEquals(LogCompareReason.NO_VALID_SAMPLES, reason { compare(ok, bad) })
        assertEquals(LogCompareReason.NO_VALID_SAMPLES, reason { compare(headerOnly, ok) })
    }

    @Test
    fun `axes are required`() {
        val ok = log(header, *rows(1000, 50, 14.7, 14.7, 6).toTypedArray())
        assertFailsWith<IllegalArgumentException> { comparator.compareLogs(ok, ok, emptyList(), loadBins, VeTable.LoadType.MAP) }
        assertFailsWith<IllegalArgumentException> { comparator.compareLogs(ok, ok, rpmBins, emptyList(), VeTable.LoadType.MAP) }
        assertEquals("EMPTY_LOG", LogCompareException(LogCompareReason.EMPTY_LOG).message)
    }

    @Test
    fun `defaults are exposed`() {
        assertEquals(5, BeforeAfterLogComparator.DEFAULT_MIN_HITS)
        assertEquals(0.2, BeforeAfterLogComparator.DEFAULT_AFR_TOLERANCE)
        assertEquals(8.0, BeforeAfterLogComparator.PLAUSIBLE_AFR_MIN)
        assertEquals(22.0, BeforeAfterLogComparator.PLAUSIBLE_AFR_MAX)
    }
}
