package io.ecucore.tuning

import io.ecucore.model.AfrTable
import io.ecucore.model.VeTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TuningAssistantAnalyzerTest {

    private fun ve(
        rpmBins: List<Int> = listOf(2000),
        loadBins: List<Int> = listOf(60),
        value: Int = 100,
        loadType: VeTable.LoadType = VeTable.LoadType.MAP,
    ) = VeTable(rpmBins, loadBins, List(loadBins.size) { List(rpmBins.size) { value } }, loadType)

    private fun afr(
        rpmBins: List<Int> = listOf(2000),
        loadBins: List<Int> = listOf(60),
        target: Int = 147,
        loadType: VeTable.LoadType = VeTable.LoadType.MAP,
    ) = AfrTable(rpmBins, loadBins, List(loadBins.size) { List(rpmBins.size) { target } }, loadType)

    /** Linhas de log estáveis: [count] amostras a cada [stepMs]. */
    private fun steady(
        count: Int,
        rpm: Int = 2000,
        map: Int = 60,
        afrValue: Double,
        clt: Int = 90,
        startMs: Long = 0,
        stepMs: Long = 100,
    ) = (0 until count).map { "${startMs + it * stepMs},$rpm,$map,10,$afrValue,$clt" }

    private val header = "timestamp,rpm,map,tps,afr,clt"

    private fun analyze(
        lines: List<String>,
        veTable: VeTable = ve(),
        afrTable: AfrTable = afr(),
        strategy: TuningStrategy = TuningStrategy.STANDARD,
        settings: AnalyzerSettings = AnalyzerSettings(),
        withHeader: Boolean = true,
    ) = TuningAssistantAnalyzer.analyzeLines(
        logName = "log",
        lines = (if (withHeader) listOf(header) + lines else lines).asSequence(),
        veTable = veTable,
        afrTable = afrTable,
        strategy = strategy,
        settings = settings,
    )

    // ---- modelos ---------------------------------------------------------------------------------------

    @Test
    fun `strategies expose their limits and analytics names`() {
        assertEquals("conservative", TuningStrategy.CONSERVATIVE.analyticsValue())
        assertEquals("standard", TuningStrategy.STANDARD.analyticsValue())
        assertEquals("aggressive", TuningStrategy.AGGRESSIVE.analyticsValue())
        assertTrue(TuningStrategy.CONSERVATIVE.maxChangePct < TuningStrategy.AGGRESSIVE.maxChangePct)
        assertTrue(TuningStrategy.CONSERVATIVE.minHits > TuningStrategy.AGGRESSIVE.minHits)
    }

    @Test
    fun `signal status is ready only with every signal`() {
        assertTrue(AnalyzerSignalStatus(true, true, true, true).isReady)
        assertFalse(AnalyzerSignalStatus(false, true, true, true).isReady)
        assertFalse(AnalyzerSignalStatus(true, false, true, true).isReady)
        assertFalse(AnalyzerSignalStatus(true, true, false, true).isReady)
        assertFalse(AnalyzerSignalStatus(true, true, true, false).isReady)
    }

    @Test
    fun `cell suggestion knows whether it is lean`() {
        assertTrue(CellSuggestion(0, 0, 5, 0.1, 0.1, 14.7, 16.0, 108).isLean)
        assertFalse(CellSuggestion(0, 0, 5, -0.1, -0.1, 14.7, 13.0, 92).isLean)
    }

    // ---- entrada vazia / sinais ---------------------------------------------------------------------------

    @Test
    fun `empty log yields an empty result`() {
        val result = analyze(emptyList(), withHeader = false)
        assertEquals(0, result.summary.totalSamples)
        assertEquals("kPa", result.summary.loadLabel)
        assertFalse(result.signalStatus.isReady)
        assertTrue(result.clusters.isEmpty())
        assertNull(result.cellSuggestions[0][0])
        assertEquals(ve(), result.suggestedVeTable)
        assertEquals("%", analyze(emptyList(), ve(loadType = VeTable.LoadType.TPS), afr(loadType = VeTable.LoadType.TPS), withHeader = false).summary.loadLabel)
    }

    @Test
    fun `missing columns make the log unusable`() {
        val result = TuningAssistantAnalyzer.analyzeLines(
            "log", sequenceOf("timestamp,rpm,map", "0,2000,60", "100,2000,60", ""), ve(), afr(), TuningStrategy.STANDARD,
        )
        assertFalse(result.signalStatus.hasAfr)
        assertEquals(2, result.summary.totalSamples) // linha em branco não conta
        assertEquals(0, result.summary.usedSamples)
        assertNull(result.summary.rpmRange)
        assertEquals(0f, result.summary.durationSeconds)
    }

    @Test
    fun `afr target table must share the load type of the ve table`() {
        val result = analyze(steady(6, afrValue = 14.7), afrTable = afr(loadType = VeTable.LoadType.TPS))
        assertFalse(result.signalStatus.hasAfrTarget)
        assertEquals(0, result.summary.usedSamples)
    }

    @Test
    fun `tps based tables read the tps column`() {
        val tpsVe = ve(loadBins = listOf(10), loadType = VeTable.LoadType.TPS)
        val tpsAfr = afr(loadBins = listOf(10), loadType = VeTable.LoadType.TPS)
        val lines = (0 until 6).map { "${it * 100},2000,60,10,16.0,90" }
        val result = analyze(lines, tpsVe, tpsAfr)
        assertTrue(result.signalStatus.isReady)
        assertEquals("%", result.summary.loadLabel)
        assertEquals(6, result.summary.usedSamples)
        assertEquals(10..10, result.summary.loadRange)
        // sem coluna tps, o log não serve para essa tabela
        val noTps = TuningAssistantAnalyzer.analyzeLines("l", sequenceOf("timestamp,rpm,map,afr", "0,2000,60,14.7"), tpsVe, tpsAfr, TuningStrategy.STANDARD)
        assertFalse(noTps.signalStatus.hasLoad)
    }

    // ---- sugestões --------------------------------------------------------------------------------------

    @Test
    fun `lean cell gets a clamped increase`() {
        val result = analyze(steady(6, afrValue = 16.17)) // 10% mais pobre que 14.7
        val s = assertNotNull(result.cellSuggestions[0][0])
        assertEquals(6, s.hitCount)
        assertEquals(0.10, s.rawDeltaPct, 1e-6)
        assertEquals(0.08, s.deltaPct, 1e-9) // STANDARD limita a 8%
        assertEquals(108, s.suggestedValue)
        assertTrue(s.isLean)
        assertEquals(108, result.suggestedVeTable.values[0][0])
        assertEquals(5f / 10f, result.summary.durationSeconds, 1e-4f)
        assertEquals(2000..2000, result.summary.rpmRange)
        assertEquals(60..60, result.summary.loadRange)
        assertEquals("Lean average", result.clusters.single().reason)
    }

    @Test
    fun `rich cell gets a decrease and a rich cluster`() {
        val result = analyze(steady(6, afrValue = 13.2))
        val s = assertNotNull(result.cellSuggestions[0][0])
        assertTrue(s.deltaPct < 0)
        assertTrue(s.suggestedValue < 100)
        assertEquals("Rich average", result.clusters.single().reason)
    }

    @Test
    fun `suggested values stay inside the byte range`() {
        val result = analyze(steady(6, afrValue = 16.17), ve(value = 250), strategy = TuningStrategy.AGGRESSIVE)
        assertEquals(255, result.cellSuggestions[0][0]!!.suggestedValue)
    }

    @Test
    fun `cells below the minimum hit count get no suggestion`() {
        val result = analyze(steady(4, afrValue = 16.17), strategy = TuningStrategy.STANDARD)
        assertNull(result.cellSuggestions[0][0])
        assertTrue(result.clusters.isEmpty())
        val aggressive = analyze(steady(4, afrValue = 16.17), strategy = TuningStrategy.AGGRESSIVE)
        assertNotNull(aggressive.cellSuggestions[0][0])
        val conservative = analyze(steady(7, afrValue = 16.17), strategy = TuningStrategy.CONSERVATIVE)
        assertNull(conservative.cellSuggestions[0][0])
    }

    // ---- filtros -------------------------------------------------------------------------------------------------

    @Test
    fun `invalid samples are filtered out`() {
        val lines = steady(6, afrValue = 16.17) + listOf(
            "700,2000,60,10,16.0,50", // motor frio
            "800,2000,60,10,25.0,90", // AFR fora da faixa
            "900,0,60,10,16.0,90", // rpm zero
            ",2000,60,10,16.0,90", // sem timestamp
            "1000,abc,60,10,16.0,90", // rpm inválido
            "1100,2000,60,10,abc,90", // afr inválido
            "1200,2000,,10,16.0,90", // sem MAP
            "",
        )
        val result = analyze(lines)
        assertEquals(6, result.summary.usedSamples)
        assertEquals(13, result.summary.totalSamples)
    }

    @Test
    fun `rapid transients are skipped`() {
        val settings = AnalyzerSettings()
        val rpmJump = steady(6, afrValue = 16.17) + listOf("700,3000,60,10,16.0,90", "800,3000,60,10,16.0,90")
        // 2000 -> 3000 rpm em 0.2 s é descartado; a amostra seguinte já é estável e conta
        assertEquals(7, analyze(rpmJump, settings = settings).summary.usedSamples)
        val mapJump = steady(6, afrValue = 16.17) + listOf("700,2000,99,10,16.0,90")
        assertEquals(6, analyze(mapJump).summary.usedSamples)
        val tpsJump = steady(6, afrValue = 16.17) + listOf("700,2000,60,50,16.0,90")
        assertEquals(6, analyze(tpsJump).summary.usedSamples)
    }

    @Test
    fun `targets outside the valid range or missing are ignored`() {
        assertEquals(0, analyze(steady(6, afrValue = 16.17), afrTable = afr(target = 0)).summary.usedSamples)
        assertEquals(0, analyze(steady(6, afrValue = 16.17), afrTable = afr(target = 250)).summary.usedSamples)
        val emptyAfr = AfrTable(listOf(2000), listOf(60), emptyList())
        assertEquals(0, analyze(steady(6, afrValue = 16.17), afrTable = emptyAfr).summary.usedSamples)
    }

    @Test
    fun `error ratio is clamped by the analyzer settings`() {
        val result = analyze(steady(6, afrValue = 19.0), settings = AnalyzerSettings(errorRatioMax = 1.05), strategy = TuningStrategy.AGGRESSIVE)
        assertEquals(0.05, result.cellSuggestions[0][0]!!.rawDeltaPct, 1e-6)
    }

    // ---- timestamps e cabeçalhos --------------------------------------------------------------------------------

    @Test
    fun `timestamps in seconds and in decimals are converted`() {
        val sec = (0 until 6).map { "${it * 0.1},2000,60,10,16.0,90" }
        val result = TuningAssistantAnalyzer.analyzeLines(
            "l", (listOf("timestamp_sec,rpm,map,tps,afr,clt") + sec).asSequence(), ve(), afr(), TuningStrategy.STANDARD,
        )
        assertEquals(6, result.summary.usedSamples)
        assertEquals(0.5f, result.summary.durationSeconds, 1e-3f)

        val wholeSeconds = (0 until 6).map { "$it,2000,60,10,16.0,90" }
        val whole = TuningAssistantAnalyzer.analyzeLines(
            "l", (listOf("timestamp_sec,rpm,map,tps,afr,clt") + wholeSeconds).asSequence(), ve(), afr(), TuningStrategy.STANDARD,
        )
        assertEquals(5f, whole.summary.durationSeconds, 1e-3f)

        val decimalMs = (0 until 6).map { "${it * 100}.5,2000,60,10,16.0,90" }
        assertEquals(6, analyze(decimalMs).summary.usedSamples)
    }

    @Test
    fun `alternative column names are recognised`() {
        val lines = listOf("timestamp,rpm,map_kpa,tps,o2,coolantraw") + (0 until 6).map { "${it * 100},2000,60,10,16.0,90" }
        val result = TuningAssistantAnalyzer.analyzeLines("l", lines.asSequence(), ve(), afr(), TuningStrategy.STANDARD)
        assertEquals(6, result.summary.usedSamples)
        val noTimestamp = listOf("rpm,map,tps,afr,clt") + (0 until 6).map { "2000,60,10,16.0,90" }
        val r2 = TuningAssistantAnalyzer.analyzeLines("l", noTimestamp.asSequence(), ve(), afr(), TuningStrategy.STANDARD)
        assertTrue(r2.signalStatus.hasRpm)
    }

    @Test
    fun `log without a coolant column is still analysed`() {
        val lines = listOf("timestamp,rpm,map,afr") + (0 until 6).map { "${it * 100},2000,60,16.0" }
        val result = TuningAssistantAnalyzer.analyzeLines("l", lines.asSequence(), ve(), afr(), TuningStrategy.STANDARD)
        assertEquals(6, result.summary.usedSamples)
    }

    // ---- suavização e clusters ----------------------------------------------------------------------------------

    private val rpm3 = listOf(1000, 2000, 3000)

    private fun grid(afrByRpm: Map<Int, Double>, samples: Int = 6): List<String> {
        var t = 0L
        val lines = mutableListOf<String>()
        for ((rpm, afrValue) in afrByRpm) {
            // transição lenta de rpm entre células para não disparar o filtro de taxa
            repeat(samples) { lines += "$t,$rpm,60,10,$afrValue,90"; t += 100 }
        }
        return lines
    }

    @Test
    fun `adjacent cells with the same sign and close deltas form one cluster`() {
        val settings = AnalyzerSettings(maxRpmRate = 1_000_000.0)
        val result = analyze(
            grid(mapOf(1000 to 16.0, 2000 to 16.0, 3000 to 16.0)),
            ve(rpmBins = rpm3), afr(rpmBins = rpm3), settings = settings,
        )
        assertEquals(1, result.clusters.size)
        assertEquals(3, result.clusters.single().cells.size)
        assertEquals(1000..3000, result.clusters.single().rpmRange)
    }

    @Test
    fun `opposite signs and distant deltas split clusters`() {
        val settings = AnalyzerSettings(maxRpmRate = 1_000_000.0, clusterDeltaTolerancePct = 0.001)
        val result = analyze(
            grid(mapOf(1000 to 16.0, 2000 to 13.0, 3000 to 15.4)),
            ve(rpmBins = rpm3), afr(rpmBins = rpm3), settings = settings, strategy = TuningStrategy.AGGRESSIVE,
        )
        assertTrue(result.clusters.size >= 2)
        // ordenados pelo maior ajuste médio
        val magnitudes = result.clusters.map { kotlin.math.abs(it.avgDeltaPct) }
        assertEquals(magnitudes.sortedDescending(), magnitudes)
    }

    @Test
    fun `at most eight clusters are reported`() {
        val bins = (1..20).map { it * 500 }
        val settings = AnalyzerSettings(maxRpmRate = 1_000_000.0)
        val afrByRpm = bins.withIndex().associate { (i, rpm) -> rpm to if (i % 2 == 0) 16.0 else 13.0 }
        val result = analyze(grid(afrByRpm), ve(rpmBins = bins), afr(rpmBins = bins), settings = settings, strategy = TuningStrategy.AGGRESSIVE)
        assertEquals(8, result.clusters.size)
    }

    @Test
    fun `cluster labels describe the operating region`() {
        fun label(rpm: Int, load: Int): String {
            val lines = (0 until 6).map { "${it * 100},$rpm,$load,10,16.0,90" }
            val r = analyze(lines, ve(rpmBins = listOf(rpm), loadBins = listOf(load)), afr(rpmBins = listOf(rpm), loadBins = listOf(load)))
            return r.clusters.single().label
        }
        assertTrue(label(3000, 90).startsWith("High load"))
        assertTrue(label(1500, 30).startsWith("Cruise"))
        assertTrue(label(800, 60).startsWith("Idle"))
        assertTrue(label(3000, 60).startsWith("Mid load"))
        assertTrue(label(3000, 60).contains("(3000-3000 rpm / 60-60 kPa)"))
        val tps = analyze(
            (0 until 6).map { "${it * 100},3000,60,10,16.0,90" },
            ve(rpmBins = listOf(3000), loadBins = listOf(10), loadType = VeTable.LoadType.TPS),
            afr(rpmBins = listOf(3000), loadBins = listOf(10), loadType = VeTable.LoadType.TPS),
        )
        assertTrue(tps.clusters.single().label.endsWith("%)"))
    }

    // ---- aplicação de clusters ----------------------------------------------------------------------------------

    @Test
    fun `only the chosen clusters are applied to the ve table`() {
        val settings = AnalyzerSettings(maxRpmRate = 1_000_000.0, clusterDeltaTolerancePct = 0.001)
        val table = ve(rpmBins = rpm3)
        val result = analyze(
            grid(mapOf(1000 to 16.0, 2000 to 13.0, 3000 to 16.0)),
            table, afr(rpmBins = rpm3), settings = settings, strategy = TuningStrategy.AGGRESSIVE,
        )
        val first = result.clusters.first()
        val applied = TuningAssistantAnalyzer.applyClustersToVe(table, result.cellSuggestions, setOf(first.id), result.clusters)
        for (cell in first.cells) {
            assertEquals(result.cellSuggestions[cell.row][cell.col]!!.suggestedValue, applied.values[cell.row][cell.col])
        }
        // nenhum cluster escolhido: tabela inalterada
        assertEquals(table, TuningAssistantAnalyzer.applyClustersToVe(table, result.cellSuggestions, emptySet(), result.clusters))
        // célula do cluster sem sugestão correspondente mantém o valor
        val missing = TuningAssistantAnalyzer.applyClustersToVe(table, emptyList(), setOf(first.id), result.clusters)
        assertEquals(table, missing)
    }

    // ---- consistência ------------------------------------------------------------------------------------------------

    @Test
    fun `analysis does not modify the input tables`() {
        val table = ve()
        val copy = table.copy()
        analyze(steady(6, afrValue = 16.17), veTable = table)
        assertEquals(copy, table)
    }

    @Test
    fun `duplicate timestamps do not break the rate computation`() {
        val lines = (0 until 6).map { "0,2000,60,10,16.0,90" }
        val result = analyze(lines)
        assertEquals(6, result.summary.usedSamples)
        assertEquals(0f, result.summary.durationSeconds)
    }

    // ---- colunas ausentes, linhas curtas e tabelas 2D --------------------------------------------------------------

    @Test
    fun `logs without rpm or without the load column are not ready`() {
        val noRpm = TuningAssistantAnalyzer.analyzeLines("l", sequenceOf("timestamp,map,afr", "0,60,14.7"), ve(), afr(), TuningStrategy.STANDARD)
        assertFalse(noRpm.signalStatus.hasRpm)
        val noMap = TuningAssistantAnalyzer.analyzeLines("l", sequenceOf("timestamp,rpm,afr", "0,2000,14.7"), ve(), afr(), TuningStrategy.STANDARD)
        assertFalse(noMap.signalStatus.hasLoad)
        assertEquals(0, noMap.summary.usedSamples)
    }

    @Test
    fun `tps tables work without a map column`() {
        val tpsVe = ve(loadBins = listOf(10), loadType = VeTable.LoadType.TPS)
        val tpsAfr = afr(loadBins = listOf(10), loadType = VeTable.LoadType.TPS)
        val lines = listOf("timestamp,rpm,tps,afr") + (0 until 6).map { "${it * 100},2000,10,16.0" } + listOf("700,2000,60,16.0", "800,2000,60,16.0")
        val result = TuningAssistantAnalyzer.analyzeLines("l", lines.asSequence(), tpsVe, tpsAfr, TuningStrategy.STANDARD)
        assertTrue(result.signalStatus.isReady)
        // salto de TPS (10 -> 60 em 0.1 s) é descartado; a amostra seguinte já é estável
        assertEquals(7, result.summary.usedSamples)
    }

    @Test
    fun `map tables work without a tps column`() {
        val lines = listOf("timestamp,rpm,map,afr") + (0 until 6).map { "${it * 100},2000,60,16.0" } + listOf("700,2000,99,16.0")
        val result = TuningAssistantAnalyzer.analyzeLines("l", lines.asSequence(), ve(), afr(), TuningStrategy.STANDARD)
        assertEquals(6, result.summary.usedSamples)
    }

    @Test
    fun `short rows afr below the minimum and unparsable timestamps are ignored`() {
        val lines = steady(6, afrValue = 16.17) + listOf(
            "700,2000", // linha curta: faltam MAP e AFR
            "800,2000,60,10,5.0,90", // AFR abaixo do mínimo
            "abc,2000,60,10,16.0,90", // timestamp ilegível
        )
        val result = analyze(lines)
        assertEquals(6, result.summary.usedSamples)
        assertEquals(9, result.summary.totalSamples)
    }

    @Test
    fun `timestamp header with both sec and ms is not treated as seconds`() {
        val lines = listOf("timestampsecms,rpm,map,tps,afr,clt") + (0 until 6).map { "${it * 100},2000,60,10,16.0,90" }
        val result = TuningAssistantAnalyzer.analyzeLines("l", lines.asSequence(), ve(), afr(), TuningStrategy.STANDARD)
        assertEquals(0.5f, result.summary.durationSeconds, 1e-3f)
    }

    @Test
    fun `afr table without bins has no target`() {
        val empty = AfrTable(emptyList(), emptyList(), emptyList())
        assertEquals(0, analyze(steady(6, afrValue = 16.17), afrTable = empty).summary.usedSamples)
    }

    @Test
    fun `clusters can span several rows and columns`() {
        val rpm2 = listOf(1000, 2000)
        val load3 = listOf(40, 60, 80)
        val table = VeTable(rpm2, load3, List(3) { List(2) { 100 } })
        val target = AfrTable(rpm2, load3, List(3) { List(2) { 147 } })
        val lines = mutableListOf<String>()
        var t = 0L
        for (load in load3) for (rpm in rpm2) repeat(6) { lines += "$t,$rpm,$load,10,16.0,90"; t += 100 }
        val result = analyze(lines, table, target, settings = AnalyzerSettings(maxRpmRate = 1e9, maxMapRate = 1e9))
        val cluster = result.clusters.single()
        assertEquals(6, cluster.cells.size)
        assertEquals(1000..2000, cluster.rpmRange)
        assertEquals(40..80, cluster.loadRange)
        assertTrue(result.cellSuggestions.flatten().all { it != null })
    }
}
