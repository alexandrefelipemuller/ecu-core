package com.speeduino.manager.model

import com.speeduino.manager.shared.Logger


/**
 * AFR (Air-Fuel Ratio) Target Table
 * 3D interpolated table that uses RPM and engine load to lookup the desired AFR target
 * for closed-loop fuel correction with wideband O2 sensor
 */
data class AfrTable(
    override val rpmBins: List<Int>,        // RPM values (columns) - e.g., [500, 1000, 1500, ...]
    override val loadBins: List<Int>,       // Load values (rows) - MAP kPa or TPS %
    override val values: List<List<Int>>,   // AFR target values (multiplied by 10, e.g., 14.7 = 147)
    override val loadType: TableModelBase.LoadType = TableModelBase.LoadType.MAP
) : TableModel {
    typealias LoadType = TableModelBase.LoadType
    typealias StorageFormat = TableModelBase.StorageFormat

    companion object {
        private const val TAG = "AfrTable"

        /**
         * Create a default 16x16 AFR target table for testing
         */
        fun createDefault(): AfrTable {
            val rpmBins = listOf(600, 900, 1200, 1500, 1800, 2100, 2400, 2700,
                                3000, 3500, 4000, 4500, 5000, 5500, 6000, 6500)
            val loadBins = listOf(15, 20, 25, 30, 35, 40, 45, 50,
                                 60, 70, 80, 90, 100, 110, 120, 130)

            // Create sample AFR target values (realistic curve)
            val values = loadBins.mapIndexed { rowIndex, load ->
                rpmBins.mapIndexed { colIndex, rpm ->
                    // Simulate realistic AFR targets:
                    // - Light load (cruise): Lean (15.0-15.5 AFR)
                    // - Medium load: Stoichiometric (14.7 AFR)
                    // - High load (WOT): Rich (12.5-13.5 AFR)
                    val baseAFR = when {
                        load < 30 -> 155   // 15.5 - cruise (lean for economy)
                        load < 50 -> 150   // 15.0 - light load
                        load < 70 -> 147   // 14.7 - stoichiometric
                        load < 90 -> 135   // 13.5 - medium-high load
                        else -> 125        // 12.5 - WOT (rich for power/cooling)
                    }

                    // Slightly richer at higher RPM under load for safety
                    val rpmAdjustment = when {
                        rpm > 5000 && load > 80 -> -5   // Richer at high RPM/load
                        rpm < 1000 && load < 40 -> 5    // Leaner at idle/cruise
                        else -> 0
                    }

                    (baseAFR + rpmAdjustment).coerceIn(100, 200)
                }
            }

            return AfrTable(rpmBins, loadBins, values, LoadType.MAP)
        }

        /**
         * Parse AFR table from Speeduino page data (Page 5 - 304 bytes)
         *
         * Format:
         * - Offset 0-31: 16 RPM bins (U16 big-endian, value in hundreds of RPM)
         * - Offset 32-47: 16 Load bins (U08, MAP kPa or TPS %)
         * - Offset 48-303: 16x16 AFR values (U08, scale 0.1, stored as AFR * 10)
         *
         * Example: Raw value 147 → 14.7 AFR (stoichiometric)
         */
        fun fromPageData(
            data: ByteArray,
            formatHint: StorageFormat? = null,
            loadType: LoadType = LoadType.MAP
        ): AfrTable {
            val format = TableModelBase.detectFormat(data, formatHint) ?: return createDefault()

            val (rpmBins, loadBins, values) = when (format) {
                StorageFormat.MODERN_288 -> {
                    if (data.size < StorageFormat.MODERN_288.totalSize) {
                        return createDefault()
                    }
                    TableModelBase.parseModernFormat(data, loadType)
                }
                StorageFormat.LEGACY_304 -> {
                    if (data.size < StorageFormat.LEGACY_304.totalSize) {
                        return createDefault()
                    }
                    TableModelBase.parseLegacyFormat(data, loadType)
                }
            }

            val table = AfrTable(rpmBins, loadBins, values, loadType)
            if (TableModelBase.isBlankAxisPage(table.rpmBins, table.loadBins)) {
                Logger.w(TAG, "Blank AFR page detected; falling back to default axes.")
                return createDefault()
            }

            return table
        }

        /**
         * Get color for AFR target value (heatmap)
         * Lower AFR (richer) = blue/purple colors
         * Higher AFR (leaner) = yellow/orange/red colors
         */
        fun getColorForValue(value: Int): Color {
            return when {
                value < 115 -> Color(0xFF0D47A1.toInt())  // Deep Blue - very rich (< 11.5)
                value < 120 -> Color(0xFF1976D2.toInt())  // Blue - rich for power (11.5-12.0)
                value < 130 -> Color(0xFF42A5F5.toInt())  // Light Blue - rich (12.0-13.0)
                value < 140 -> Color(0xFF26C6DA.toInt())  // Cyan - slightly rich (13.0-14.0)
                value < 147 -> Color(0xFF66BB6A.toInt())  // Green - approaching stoich (14.0-14.7)
                value < 150 -> Color(0xFF9CCC65.toInt())  // Light Green - stoich (14.7-15.0)
                value < 155 -> Color(0xFFFFEE58.toInt())  // Yellow - slightly lean (15.0-15.5)
                value < 160 -> Color(0xFFFFCA28.toInt())  // Amber - lean (15.5-16.0)
                value < 170 -> Color(0xFFFFA726.toInt())  // Orange - lean (16.0-17.0)
                else -> Color(0xFFFF7043.toInt())         // Deep Orange - very lean (> 17.0)
            }
        }

        /**
         * Format AFR value for display (147 -> "14.7")
         */
        fun formatValue(value: Int): String {
            val wholePart = value / 10
            val decimalPart = value % 10
            return "$wholePart.$decimalPart"
        }

        /**
         * Parse AFR value from string ("14.7" -> 147)
         */
        fun parseValue(text: String): Int? {
            return try {
                val floatValue = text.toFloat()
                (floatValue * 10).toInt()
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Get value at specific row/column (defaults to stoichiometric 14.7 if not found)
     */
    fun getValue(row: Int, col: Int): Int {
        return values.getOrNull(row)?.getOrNull(col) ?: 147
    }

    /**
     * Update value at specific row/column
     * AFR values range from 100-200 (10.0 to 20.0 AFR, stored as value * 10)
     */
    fun setValue(row: Int, col: Int, newValue: Int): AfrTable {
        return setTableValue(this, row, col, newValue, { it.coerceIn(100, 200) }) { newValues ->
            copy(values = newValues)
        }
    }

    /**
     * Update RPM bin at specific index (0-15)
     * @param newRpm RPM value (100-25500 range, stored as hundredths)
     */
    fun setRpmBin(index: Int, newRpm: Int): AfrTable {
        return setTableRpmBin(this, index, newRpm) { newRpmBins ->
            copy(rpmBins = newRpmBins)
        }
    }

    /**
     * Update Load bin at specific index (0-15)
     * @param newLoad Load value (0-510 range for MAP kPa, 0-255 for TPS %)
     */
    fun setLoadBin(index: Int, newLoad: Int): AfrTable {
        return setTableLoadBin(this, index, newLoad) { newLoadBins ->
            copy(loadBins = newLoadBins)
        }
    }

    /**
     * Convert load type from MAP to TPS or vice versa
     */
    fun withLoadType(newLoadType: LoadType): AfrTable {
        return convertTableLoadType(this, newLoadType) { convertedLoadBins, newType ->
            copy(loadBins = convertedLoadBins, loadType = newType)
        }
    }

    /**
     * Convert to byte array for writing to ECU (Page 5 - 304 bytes)
     *
     * Format (same as fromPageData):
     * - Offset 0-31: 16 RPM bins (U16 big-endian, value / 100)
     * - Offset 32-47: 16 Load bins (U08)
     * - Offset 48-303: 16x16 AFR values (U08, already in AFR * 10 format)
     */
    fun toByteArray(format: StorageFormat = StorageFormat.MODERN_288): ByteArray {
        return when (format) {
            StorageFormat.MODERN_288 -> TableModelBase.serializeModernFormat(rpmBins, loadBins, values, loadType)
            StorageFormat.LEGACY_304 -> TableModelBase.serializeLegacyFormat(rpmBins, loadBins, values, loadType)
        }
    }
}
