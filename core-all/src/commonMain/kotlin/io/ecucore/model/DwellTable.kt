package io.ecucore.model

import io.ecucore.shared.Logger
import io.ecucore.shared.formatDecimal
import kotlin.math.roundToInt

/**
 * Dwell map used by Speeduino page 12.
 *
 * The map is small on purpose: 4x4 with RPM and load axes, and dwell values
 * stored in 0.1 ms units.
 */
data class DwellTable(
    val rpmBins: List<Int>,
    val loadBins: List<Int>,
    val values: List<List<Int>>,
    val loadType: IgnitionTable.LoadType = IgnitionTable.LoadType.MAP,
) {
    enum class StorageFormat(val totalSize: Int) {
        PAGE_12_192(192);

        companion object {
            fun fromTotalSize(size: Int): StorageFormat? =
                values().firstOrNull { it.totalSize == size }
        }
    }

    companion object {
        private const val TAG = "DwellTable"
        private const val MAP_LOAD_SCALE = 2
        private const val TPS_LOAD_DIVISOR = 2
        private const val VALUE_SCALE = 10

        fun createDefault(): DwellTable {
            val rpmBins = listOf(1000, 2000, 3000, 4000)
            val loadBins = listOf(20, 40, 60, 80)
            val values = listOf(
                listOf(28, 26, 24, 22),
                listOf(30, 28, 26, 24),
                listOf(32, 30, 28, 26),
                listOf(34, 32, 30, 28),
            )
            return DwellTable(rpmBins, loadBins, values, IgnitionTable.LoadType.MAP)
        }

        fun fromPageData(
            data: ByteArray,
            formatHint: StorageFormat? = null,
            loadType: IgnitionTable.LoadType = IgnitionTable.LoadType.MAP,
        ): DwellTable {
            val format = detectFormat(data, formatHint) ?: return createDefault()
            val table = when (format) {
                StorageFormat.PAGE_12_192 -> parsePage12(data, loadType)
            }

            if (table.isBlankAxisPage()) {
                Logger.w(TAG, "Blank dwell page detected; falling back to default axes.")
                return createDefault()
            }

            return table
        }

        private fun detectFormat(
            data: ByteArray,
            hint: StorageFormat?,
        ): StorageFormat? {
            hint?.let { expected ->
                if (data.size >= expected.totalSize) return expected
            }
            return if (data.size >= StorageFormat.PAGE_12_192.totalSize) {
                StorageFormat.PAGE_12_192
            } else {
                null
            }
        }

        private fun parsePage12(
            data: ByteArray,
            loadType: IgnitionTable.LoadType,
        ): DwellTable {
            if (data.size < StorageFormat.PAGE_12_192.totalSize) {
                return createDefault()
            }

            val values = MutableList(4) { row ->
                MutableList(4) { col ->
                    val offset = 160 + (row * 4) + col
                    ((data[offset].toInt() and 0xFF) / VALUE_SCALE).coerceIn(1, 80)
                }
            }

            val rpmBins = List(4) { i ->
                val raw = data[176 + i].toInt() and 0xFF
                raw * 100
            }

            val loadBins = List(4) { i ->
                rawLoadToUser(data[180 + i].toInt() and 0xFF, loadType)
            }

            return DwellTable(rpmBins, loadBins, values, loadType)
        }

        private fun rawLoadToUser(rawValue: Int, loadType: IgnitionTable.LoadType): Int {
            return if (loadType == IgnitionTable.LoadType.MAP) {
                rawValue * MAP_LOAD_SCALE
            } else {
                rawValue / TPS_LOAD_DIVISOR
            }
        }

        private fun userLoadToRaw(userValue: Int, loadType: IgnitionTable.LoadType): Int {
            return if (loadType == IgnitionTable.LoadType.MAP) {
                userValue / MAP_LOAD_SCALE
            } else {
                userValue * TPS_LOAD_DIVISOR
            }
        }

        fun getColorForValueArgb(value: Int): Int {
            return when {
                value < 20 -> 0xFF1E88E5.toInt()
                value < 24 -> 0xFF26A69A.toInt()
                value < 28 -> 0xFF66BB6A.toInt()
                value < 32 -> 0xFFFFEE58.toInt()
                value < 36 -> 0xFFFFA726.toInt()
                else -> 0xFFE53935.toInt()
            }
        }

        fun formatValue(value: Int): String {
            return formatDecimal((value / VALUE_SCALE.toFloat()).toDouble(), 1)
        }

        fun parseValue(text: String): Int? {
            return text.trim().replace(',', '.').toFloatOrNull()?.let { (it * VALUE_SCALE).roundToInt() }
        }
    }

    fun getValue(row: Int, col: Int): Int {
        return values.getOrNull(row)?.getOrNull(col) ?: 0
    }

    fun setValue(row: Int, col: Int, newValue: Int): DwellTable {
        val newValues = values.mapIndexed { r, rowValues ->
            if (r == row) {
                rowValues.mapIndexed { c, value ->
                    if (c == col) newValue.coerceIn(1, 80) else value
                }
            } else {
                rowValues
            }
        }
        return copy(values = newValues)
    }

    fun setRpmBin(index: Int, newRpm: Int): DwellTable {
        if (index !in 0..3) return this
        val newRpmBins = rpmBins.toMutableList()
        newRpmBins[index] = newRpm.coerceIn(100, 25500)
        return copy(rpmBins = newRpmBins)
    }

    fun setLoadBin(index: Int, newLoad: Int): DwellTable {
        if (index !in 0..3) return this
        val newLoadBins = loadBins.toMutableList()
        newLoadBins[index] = newLoad.coerceIn(0, maxLoadForType())
        return copy(loadBins = newLoadBins)
    }

    fun withLoadType(newLoadType: IgnitionTable.LoadType): DwellTable {
        if (loadType == newLoadType) return this
        val convertedLoadBins = loadBins.map { load ->
            val rawLoad = userLoadToRaw(load, loadType)
            rawLoadToUser(rawLoad, newLoadType)
        }
        return copy(loadBins = convertedLoadBins, loadType = newLoadType)
    }

    fun toByteArray(format: StorageFormat = StorageFormat.PAGE_12_192): ByteArray {
        return when (format) {
            StorageFormat.PAGE_12_192 -> serializePage12()
        }
    }

    private fun serializePage12(): ByteArray {
        val data = ByteArray(StorageFormat.PAGE_12_192.totalSize)

        values.forEachIndexed { row, rowValues ->
            rowValues.forEachIndexed { col, value ->
                val offset = 160 + (row * 4) + col
                data[offset] = value.coerceIn(1, 80).toByte()
            }
        }

        rpmBins.forEachIndexed { i, rpm ->
            data[176 + i] = (rpm / 100).coerceIn(0, 255).toByte()
        }

        loadBins.forEachIndexed { i, load ->
            data[180 + i] = userLoadToRaw(load, loadType).coerceIn(0, 255).toByte()
        }

        return data
    }

    private fun isBlankAxisPage(): Boolean {
        return rpmBins.none { it > 0 } && loadBins.none { it > 0 }
    }

    private fun maxLoadForType(): Int {
        return if (loadType == IgnitionTable.LoadType.MAP) 510 else 255
    }
}
