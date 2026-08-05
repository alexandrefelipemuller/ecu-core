package com.speeduino.manager.model

import com.speeduino.manager.shared.Logger


/**
 * VE (Volumetric Efficiency) Table
 * 3D interpolated table that uses RPM and fuel load to lookup the desired VE value
 */
data class VeTable(
    override val rpmBins: List<Int>,        // RPM values (columns) - e.g., [500, 1000, 1500, ...]
    override val loadBins: List<Int>,       // Load values (rows) - MAP kPa or TPS %
    override val values: List<List<Int>>,   // VE values (percentage of Required Fuel)
    override val loadType: TableModelBase.LoadType = TableModelBase.LoadType.MAP
) : TableModel {
    typealias LoadType = TableModelBase.LoadType
    typealias StorageFormat = TableModelBase.StorageFormat

    companion object {
        private const val TAG = "VeTable"

        /**
         * Create a default 16x16 VE table for testing
         */
        fun createDefault(): VeTable {
            val rpmBins = listOf(500, 1000, 1500, 2000, 2500, 3000, 3500, 4000,
                                4500, 5000, 5500, 6000, 6500, 7000, 7500, 8000)
            val loadBins = listOf(10, 20, 30, 40, 50, 60, 70, 80,
                                 90, 100, 110, 120, 130, 140, 150, 160)

            // Create sample VE values (realistic curve)
            val values = loadBins.mapIndexed { rowIndex, load ->
                rpmBins.mapIndexed { colIndex, rpm ->
                    // Simulate realistic VE curve (peaks around mid-range)
                    val baseVE = 75
                    val rpmFactor = when {
                        rpm < 2000 -> -10
                        rpm in 2000..4000 -> 10
                        rpm in 4000..6000 -> 5
                        else -> -5
                    }
                    val loadFactor = (load / 10)
                    (baseVE + rpmFactor + loadFactor).coerceIn(40, 110)
                }
            }

            return VeTable(rpmBins, loadBins, values, LoadType.MAP)
        }

        fun fromPageData(
            data: ByteArray,
            formatHint: StorageFormat? = null,
            loadType: LoadType = LoadType.MAP
        ): VeTable {
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

            val table = VeTable(rpmBins, loadBins, values, loadType)
            if (TableModelBase.isBlankAxisPage(table.rpmBins, table.loadBins)) {
                Logger.w(TAG, "Blank VE page detected; falling back to default axes.")
                return createDefault()
            }

            return table
        }

        /**
         * Get color for VE value (heatmap)
         */
        fun getColorForValue(value: Int): Color {
            return when {
                value < 50 -> Color(0xFF0000FF.toInt()) // Blue - lean
                value < 60 -> Color(0xFF00FFFF.toInt()) // Cyan
                value < 70 -> Color(0xFF00FF00.toInt()) // Green
                value < 80 -> Color(0xFFFFFF00.toInt()) // Yellow
                value < 90 -> Color(0xFFFFA500.toInt()) // Orange
                value < 100 -> Color(0xFFFF4500.toInt()) // Orange-Red
                else -> Color(0xFFFF0000.toInt())        // Red - rich
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
     * VE values range from 0-255 (percentage of required fuel)
     */
    fun setValue(row: Int, col: Int, newValue: Int): VeTable {
        return setTableValue(this, row, col, newValue, { it.coerceIn(0, 255) }) { newValues ->
            copy(values = newValues)
        }
    }

    /**
     * Update RPM bin at specific index (0-15)
     * @param newRpm RPM value (100-25500 range, stored as hundredths)
     */
    fun setRpmBin(index: Int, newRpm: Int): VeTable {
        return setTableRpmBin(this, index, newRpm) { newRpmBins ->
            copy(rpmBins = newRpmBins)
        }
    }

    /**
     * Update Load bin at specific index (0-15)
     * @param newLoad Load value (0-510 range for MAP kPa, 0-255 for TPS %)
     */
    fun setLoadBin(index: Int, newLoad: Int): VeTable {
        return setTableLoadBin(this, index, newLoad) { newLoadBins ->
            copy(loadBins = newLoadBins)
        }
    }

    /**
     * Convert load type from MAP to TPS or vice versa
     */
    fun withLoadType(newLoadType: LoadType): VeTable {
        return convertTableLoadType(this, newLoadType) { convertedLoadBins, newType ->
            copy(loadBins = convertedLoadBins, loadType = newType)
        }
    }

    /**
     * Convert to byte array for writing to ECU (Page 1 format)
     *
     * Format (304 bytes):
     * - Offset 0-31: 16 RPM bins (U16 big-endian, value in hundreds)
     * - Offset 32-47: 16 Load bins (U08, MAP kPa or TPS %)
     * - Offset 48-303: 16x16 VE values (U08, percentage 0-255)
     */
    fun toByteArray(format: StorageFormat = StorageFormat.MODERN_288): ByteArray {
        return when (format) {
            StorageFormat.MODERN_288 -> TableModelBase.serializeModernFormat(rpmBins, loadBins, values, loadType)
            StorageFormat.LEGACY_304 -> TableModelBase.serializeLegacyFormat(rpmBins, loadBins, values, loadType)
        }
    }
}
