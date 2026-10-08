package io.ecucore.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TableChangeAnalyzerTest {

    private fun grid(value: Int) = List(4) { List(4) { value } }

    private fun grid(value: Int, withCell: Int) = grid(value).mapIndexed { r, row ->
        row.mapIndexed { c, v -> if (r == 1 && c == 2) withCell else v }
    }

    @Test
    fun identicalTableHasNoChange() {
        val a = TableChangeAnalyzer.assess(TableChangeKind.VE, grid(60), grid(60))
        assertEquals(0, a.changedCells)
        assertFalse(a.requiresConfirmation)
    }

    @Test
    fun smallVeChangesDoNotRequireConfirmation() {
        val a = TableChangeAnalyzer.assess(TableChangeKind.VE, grid(60), grid(60, withCell = 70))
        assertEquals(1, a.changedCells)
        assertEquals(10, a.maxAbsDelta)
        assertFalse(a.requiresConfirmation)
    }

    @Test
    fun largeVeChangeRequiresConfirmationAndPointsToTheCell() {
        val a = TableChangeAnalyzer.assess(TableChangeKind.VE, grid(60), grid(60, withCell = 20))
        assertTrue(a.requiresConfirmation)
        assertEquals(1, a.largeChangeCells)
        assertEquals(1 to 2, a.largestChangeCell)
        assertFalse(a.bulkReplacement)
    }

    @Test
    fun veAbsoluteJumpOnHighValueBelowRelativeLimitIsNotLarge() {
        // 15 pontos em cima de 100 = 15% (<25%)
        val a = TableChangeAnalyzer.assess(TableChangeKind.VE, grid(100), grid(100, withCell = 115))
        assertFalse(a.requiresConfirmation)
    }

    @Test
    fun ignitionSixDegreesIsLargeFiveIsNot() {
        assertTrue(TableChangeAnalyzer.assess(TableChangeKind.IGNITION, grid(20), grid(20, withCell = 26)).requiresConfirmation)
        assertFalse(TableChangeAnalyzer.assess(TableChangeKind.IGNITION, grid(20), grid(20, withCell = 25)).requiresConfirmation)
    }

    @Test
    fun afrOnePointFiveIsLarge() {
        assertTrue(TableChangeAnalyzer.assess(TableChangeKind.AFR, grid(147), grid(147, withCell = 162)).requiresConfirmation)
        assertFalse(TableChangeAnalyzer.assess(TableChangeKind.AFR, grid(147), grid(147, withCell = 160)).requiresConfirmation)
    }

    @Test
    fun wholeTableReplacementIsBulk() {
        val a = TableChangeAnalyzer.assess(TableChangeKind.IGNITION, grid(10), grid(30))
        assertTrue(a.bulkReplacement)
        assertEquals(16, a.largeChangeCells)
    }

    @Test
    fun differentShapeAlwaysRequiresConfirmation() {
        val a = TableChangeAnalyzer.assess(TableChangeKind.VE, grid(60), List(2) { List(2) { 60 } })
        assertTrue(a.requiresConfirmation && a.bulkReplacement)
        assertTrue(a.summary().contains("different shape"))
    }
}
