package io.ecucore.model

import kotlin.math.abs

enum class TableChangeKind { VE, IGNITION, AFR }

/**
 * Resumo da diferença entre a tabela atual da ECU e a que está para ser gravada.
 * [requiresConfirmation] indica que ao menos uma célula mudou além do limite "grande" do tipo.
 */
data class TableChangeAssessment(
    val kind: TableChangeKind,
    val totalCells: Int,
    val changedCells: Int,
    val largeChangeCells: Int,
    val maxAbsDelta: Int,
    val largestChangeCell: Pair<Int, Int>?,
    val shapeChanged: Boolean,
) {
    val requiresConfirmation: Boolean get() = shapeChanged || largeChangeCells > 0

    /** Mais da metade das células com mudança grande: substituição em bloco (ex.: base map). */
    val bulkReplacement: Boolean
        get() = shapeChanged || (totalCells > 0 && largeChangeCells * 2 >= totalCells)

    fun summary(): String = when {
        shapeChanged -> "${kind.name} table has a different shape than the one in the ECU"
        else -> "$largeChangeCells of $totalCells ${kind.name} cells change by a large amount " +
            "(max ${maxAbsDelta}${kind.unitLabel()}${largestChangeCell?.let { " at [${it.first},${it.second}]" } ?: ""}); " +
            "$changedCells cells changed in total"
    }

    private fun TableChangeKind.unitLabel() = when (this) {
        TableChangeKind.VE -> "% VE"
        TableChangeKind.IGNITION -> "°"
        TableChangeKind.AFR -> " (AFR x10)"
    }
}

/**
 * Compara a tabela atual com a nova e classifica o tamanho da mudança (HARA H1/H2).
 * Limites "grandes" (por célula):
 *  - VE: ≥15 pontos e ≥25% do valor atual (célula atual 0 conta só os 15 pontos)
 *  - Ignição: ≥6°
 *  - AFR: ≥1,5 (15 no valor x10)
 */
object TableChangeAnalyzer {
    const val VE_LARGE_ABS = 15
    const val VE_LARGE_RELATIVE = 0.25
    const val IGNITION_LARGE_DEG = 6
    const val AFR_LARGE_X10 = 15

    fun assess(kind: TableChangeKind, current: List<List<Int>>, updated: List<List<Int>>): TableChangeAssessment {
        val sameShape = current.size == updated.size && current.indices.all { current[it].size == updated[it].size }
        if (!sameShape) {
            return TableChangeAssessment(kind, updated.sumOf { it.size }, 0, 0, 0, null, shapeChanged = true)
        }
        var total = 0
        var changed = 0
        var large = 0
        var maxDelta = 0
        var largest: Pair<Int, Int>? = null
        current.forEachIndexed { row, cells ->
            cells.forEachIndexed { col, old ->
                total++
                val delta = abs(updated[row][col] - old)
                if (delta > 0) changed++
                if (isLarge(kind, old, delta)) large++
                if (delta > maxDelta) {
                    maxDelta = delta
                    largest = row to col
                }
            }
        }
        return TableChangeAssessment(kind, total, changed, large, maxDelta, largest, shapeChanged = false)
    }

    private fun isLarge(kind: TableChangeKind, old: Int, delta: Int): Boolean = when (kind) {
        TableChangeKind.VE -> delta >= VE_LARGE_ABS && (old == 0 || delta >= old * VE_LARGE_RELATIVE)
        TableChangeKind.IGNITION -> delta >= IGNITION_LARGE_DEG
        TableChangeKind.AFR -> delta >= AFR_LARGE_X10
    }
}
