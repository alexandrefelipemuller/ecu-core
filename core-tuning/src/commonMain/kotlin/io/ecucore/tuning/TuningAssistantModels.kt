package io.ecucore.tuning

import io.ecucore.model.VeTable

enum class TuningStrategy(
    val maxChangePct: Double,
    val minHits: Int
) {
    CONSERVATIVE(maxChangePct = 0.04, minHits = 8),
    STANDARD(maxChangePct = 0.08, minHits = 5),
    AGGRESSIVE(maxChangePct = 0.12, minHits = 3);

    fun analyticsValue(): String = name.lowercase()
}

data class AnalyzerSettings(
    val minCoolantC: Double = 70.0,
    val maxRpmRate: Double = 600.0,
    val maxMapRate: Double = 20.0,
    val maxTpsRate: Double = 15.0,
    val minAfr: Double = 8.0,
    val maxAfr: Double = 20.0,
    val errorRatioMin: Double = 0.85,
    val errorRatioMax: Double = 1.15,
    val clusterDeltaTolerancePct: Double = 0.03
)

data class AnalyzerSignalStatus(
    val hasRpm: Boolean,
    val hasLoad: Boolean,
    val hasAfr: Boolean,
    val hasAfrTarget: Boolean
) {
    val isReady: Boolean = hasRpm && hasLoad && hasAfr && hasAfrTarget
}

data class AnalyzerSummary(
    val logName: String?,
    val durationSeconds: Float,
    val rpmRange: IntRange?,
    val loadRange: IntRange?,
    val totalSamples: Int,
    val usedSamples: Int,
    val loadLabel: String
)

data class CellRef(
    val row: Int,
    val col: Int
)

data class CellSuggestion(
    val row: Int,
    val col: Int,
    val hitCount: Int,
    val rawDeltaPct: Double,
    val deltaPct: Double,
    val meanAfrTarget: Double,
    val meanAfrMeasured: Double,
    val suggestedValue: Int
) {
    val isLean: Boolean = meanAfrMeasured > meanAfrTarget
}

data class SuggestionCluster(
    val id: String,
    val cells: List<CellRef>,
    val avgDeltaPct: Double,
    val avgHits: Int,
    val rpmRange: IntRange,
    val loadRange: IntRange,
    val label: String,
    val reason: String
)

data class AnalyzerResult(
    val summary: AnalyzerSummary,
    val signalStatus: AnalyzerSignalStatus,
    val cellSuggestions: List<List<CellSuggestion?>>,
    val clusters: List<SuggestionCluster>,
    val suggestedVeTable: VeTable
)

/** Textos das regiões/motivos gerados pelo analisador; o app injeta a versão localizada. */
data class TuningAnalyzerLabels(
    val leanAverage: String,
    val richAverage: String,
    val highLoad: String,
    val cruise: String,
    val idle: String,
    val midLoad: String,
) {
    companion object {
        val ENGLISH = TuningAnalyzerLabels("Lean average", "Rich average", "High load", "Cruise", "Idle", "Mid load")
    }
}
