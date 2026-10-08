package io.ecucore.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TableValidatorTest {

    @Test
    fun nonMonotonicLoadBinsAreWarningsOnly() {
        val metadata = SpeeduinoTableDefinitions.VE_TABLE_MODERN
        val baseTable = VeTable.createDefault()
        val tableWithLoadIssues = baseTable.copy(
            loadBins = baseTable.loadBins.toMutableList().apply {
                this[12] = 200
                this[13] = 195
                this[14] = 250
                this[15] = 250
            }
        )

        val result = TableValidator(metadata).validate(tableWithLoadIssues)

        assertTrue(result.isValid)
        assertTrue(result.warnings.any { it.contains("Load bins are not strictly increasing") })
        assertEquals(0, result.errors.size)
    }

    @Test
    fun nonMonotonicRpmBinsAreWarningsOnly() {
        val metadata = SpeeduinoTableDefinitions.VE_TABLE_MODERN
        val baseTable = VeTable.createDefault()
        val tableWithRpmIssues = baseTable.copy(
            rpmBins = baseTable.rpmBins.toMutableList().apply {
                this[5] = this[4]
                this[6] = this[4]
            }
        )

        val result = TableValidator(metadata).validate(tableWithRpmIssues)

        assertTrue(result.isValid)
        assertTrue(result.warnings.any { it.contains("RPM bins are not strictly increasing") })
        assertEquals(0, result.errors.size)
    }

    private fun veWith(noFuelCells: Int): VeTable {
        val base = VeTable.createDefault()
        var left = noFuelCells
        return base.copy(values = base.values.map { row ->
            row.map { if (left-- > 0) 0 else 60 }
        })
    }

    @Test
    fun veWithManyNoFuelCellsBlocksWrite() {
        val metadata = SpeeduinoTableDefinitions.VE_TABLE_MODERN

        val result = TableValidator(metadata).validateBeforeWrite(veWith(noFuelCells = 26))

        assertTrue(!result.isValid)
        assertTrue(result.errors.any { it.contains("no fuel") })
    }

    @Test
    fun veWithFewNoFuelCellsStillWrites() {
        val metadata = SpeeduinoTableDefinitions.VE_TABLE_MODERN

        val result = TableValidator(metadata).validateBeforeWrite(veWith(noFuelCells = 5))

        assertTrue(result.isValid, result.errors.toString())
    }

    private fun afrWith(raw: Int): AfrTable {
        val base = AfrTable.createDefault()
        return base.copy(values = base.values.map { row -> row.map { raw } })
    }

    @Test
    fun afrDangerouslyLeanBlocksWrite() {
        val metadata = SpeeduinoTableDefinitions.AFR_TABLE_MODERN

        val result = TableValidator(metadata).validateBeforeWrite(afrWith(raw = 185))

        assertTrue(!result.isValid)
        assertTrue(result.errors.any { it.contains("dangerously lean") })
    }

    @Test
    fun afrModeratelyLeanOnlyWarns() {
        val metadata = SpeeduinoTableDefinitions.AFR_TABLE_MODERN

        val result = TableValidator(metadata).validateBeforeWrite(afrWith(raw = 170))

        assertTrue(result.isValid, result.errors.toString())
        assertTrue(result.warnings.any { it.contains("leaner than") })
    }

    @Test
    fun afrOutOfRangeBlocksWrite() {
        val metadata = SpeeduinoTableDefinitions.AFR_TABLE_MODERN

        assertTrue(!TableValidator(metadata).validateBeforeWrite(afrWith(raw = 30)).isValid)
        assertTrue(!TableValidator(metadata).validateBeforeWrite(afrWith(raw = 255 + 1)).isValid)
    }

    @Test
    fun afrDefaultTableIsValid() {
        val metadata = SpeeduinoTableDefinitions.AFR_TABLE_MODERN

        val result = TableValidator(metadata).validateBeforeWrite(AfrTable.createDefault())

        assertTrue(result.isValid, result.errors.toString())
    }
}
