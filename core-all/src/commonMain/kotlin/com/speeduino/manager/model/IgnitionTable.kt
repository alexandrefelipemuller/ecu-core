package com.speeduino.manager.model

import com.speeduino.manager.shared.Logger


/**
 * Ignition Table (Spark Advance)
 * 3D interpolated table that uses RPM and engine load to lookup the desired ignition timing
 */
data class IgnitionTable(
    override val rpmBins: List<Int>,        // RPM values (columns) - e.g., [500, 1000, 1500, ...]
    override val loadBins: List<Int>,       // Load values (rows) - MAP kPa or TPS %
    override val values: List<List<Int>>,   // Ignition advance values (degrees BTDC)
    override val loadType: TableModelBase.LoadType = TableModelBase.LoadType.MAP
) : TableModel {
    typealias LoadType = TableModelBase.LoadType
    typealias StorageFormat = TableModelBase.StorageFormat

    companion object {
        private const val TAG = "IgnitionTable"

        /**
         * Create a default 16x16 Ignition table for testing
         */
        fun createDefault(): IgnitionTable {
            val rpmBins = listOf(500, 650, 790, 930, 1100, 1270, 1440, 1610,
                                1780, 1950, 2360, 2770, 3530, 4290, 5050, 6000)
            val loadBins = listOf(20, 27, 34, 41, 48, 55, 62, 69,
                                 76, 83, 90, 96, 100, 105, 110, 120)

            // Create sample Ignition advance values (realistic spark curve)
            val values = loadBins.mapIndexed { rowIndex, load ->
                rpmBins.mapIndexed { colIndex, rpm ->
                    // Simulate realistic ignition curve
                    // Lower RPM and load = more advance
                    // Higher load = less advance (avoid knock)
                    val baseAdvance = when {
                        rpm < 1000 -> 15   // Idle timing
                        rpm < 2000 -> 25   // Low RPM
                        rpm < 4000 -> 32   // Mid-range
                        else -> 35         // High RPM
                    }

                    val loadReduction = when {
                        load < 40 -> 0      // Light load - no reduction
                        load < 60 -> 2      // Medium load
                        load < 80 -> 5      // Heavy load
                        else -> 10          // Full load - significant reduction
                    }

                    (baseAdvance - loadReduction).coerceIn(5, 45)
                }
            }

            return IgnitionTable(rpmBins, loadBins, values, LoadType.MAP)
        }

        fun fromPageData(
            data: ByteArray,
            formatHint: StorageFormat? = null,
            loadType: LoadType = LoadType.MAP
        ): IgnitionTable {
            val format = TableModelBase.detectFormat(data, formatHint) ?: return createDefault()

            val (rpmBins, loadBins, values) = when (format) {
                StorageFormat.MODERN_288 -> {
                    if (data.size < StorageFormat.MODERN_288.totalSize) {
                        return createDefault()
                    }
                    // Ignition values are stored as (user_value + 40), so subtract 40 to get user value
                    TableModelBase.parseModernFormat(data, loadType) { rawValue -> rawValue - 40 }
                }
                StorageFormat.LEGACY_304 -> {
                    if (data.size < StorageFormat.LEGACY_304.totalSize) {
                        return createDefault()
                    }
                    // Ignition values are stored as (user_value + 40), so subtract 40 to get user value
                    TableModelBase.parseLegacyFormat(data, loadType) { rawValue -> rawValue - 40 }
                }
            }

            val table = IgnitionTable(rpmBins, loadBins, values, loadType)
            if (TableModelBase.isBlankAxisPage(table.rpmBins, table.loadBins)) {
                Logger.w(TAG, "Blank ignition page detected; falling back to default axes.")
                return createDefault()
            }

            return table
        }

        /**
         * Get color for ignition advance value (heatmap)
         */
        fun getColorForValue(value: Int): Color {
            return when {
                value < 10 -> Color(0xFF4A148C.toInt())  // Deep Purple - very little advance
                value < 15 -> Color(0xFF6A1B9A.toInt())  // Purple
                value < 20 -> Color(0xFF1976D2.toInt())  // Blue
                value < 25 -> Color(0xFF0288D1.toInt())  // Light Blue
                value < 30 -> Color(0xFF00ACC1.toInt())  // Cyan
                value < 35 -> Color(0xFF00897B.toInt())  // Teal
                value < 40 -> Color(0xFFFBC02D.toInt())  // Yellow
                else -> Color(0xFFE65100.toInt())        // Deep Orange - high advance
            }
        }
    }

    /**
     * Get value at specific row/column
     */
    fun getValue(row: Int, col: Int): Int {
        return values.getOrNull(row)?.getOrNull(col) ?: 0
    }

    /**
     * Update value at specific row/column
     * Ignition advance values range from -40 to +70 degrees BTDC
     */
    fun setValue(row: Int, col: Int, newValue: Int): IgnitionTable {
        return setTableValue(this, row, col, newValue, { it.coerceIn(-40, 70) }) { newValues ->
            copy(values = newValues)
        }
    }

    /**
     * Update RPM bin at specific index (0-15)
     * @param newRpm RPM value (100-25500 range, stored as hundredths)
     */
    fun setRpmBin(index: Int, newRpm: Int): IgnitionTable {
        return setTableRpmBin(this, index, newRpm) { newRpmBins ->
            copy(rpmBins = newRpmBins)
        }
    }

    /**
     * Update Load bin at specific index (0-15)
     * @param newLoad Load value (0-510 range for MAP kPa, 0-255 for TPS %)
     */
    fun setLoadBin(index: Int, newLoad: Int): IgnitionTable {
        return setTableLoadBin(this, index, newLoad) { newLoadBins ->
            copy(loadBins = newLoadBins)
        }
    }

    /**
     * Convert load type from MAP to TPS or vice versa
     */
    fun withLoadType(newLoadType: LoadType): IgnitionTable {
        return convertTableLoadType(this, newLoadType) { convertedLoadBins, newType ->
            copy(loadBins = convertedLoadBins, loadType = newType)
        }
    }

    /**
     * Convert to byte array for writing to ECU (Page 3 - 304 bytes)
     *
     * Format (same as fromPageData):
     * - Offset 0-31: 16 RPM bins (U16 big-endian, value / 100)
     * - Offset 32-47: 16 Load bins (U08)
     * - Offset 48-303: 16x16 Ignition values (U08, degrees + 40)
     *
     * CRITICAL: Must apply translate offset (+40) when converting to raw bytes
     */
    fun toByteArray(format: StorageFormat = StorageFormat.MODERN_288): ByteArray {
        return when (format) {
            StorageFormat.MODERN_288 -> {
                // Ignition values are stored as (user_value + 40), so add 40 when serializing
                TableModelBase.serializeModernFormat(rpmBins, loadBins, values, loadType) { userValue -> userValue + 40 }
            }
            StorageFormat.LEGACY_304 -> {
                // Ignition values are stored as (user_value + 40), so add 40 when serializing
                TableModelBase.serializeLegacyFormat(rpmBins, loadBins, values, loadType) { userValue -> userValue + 40 }
            }
        }
    }
}
