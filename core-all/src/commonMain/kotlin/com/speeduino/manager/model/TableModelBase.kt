package com.speeduino.manager.model

/**
 * Shared base classes and utilities for 3D table models (VE, Ignition, AFR).
 * Eliminates duplication of enums, constants, and parsing/serialization logic.
 */
/**
 * Generic table data model interface for 3D lookup tables (VE, Ignition, AFR).
 * Provides methods for axis-aligned access and transformation without requiring
 * specific subtype knowledge. Implemented by VeTable, IgnitionTable, AfrTable.
 */
interface TableModel {
    val rpmBins: List<Int>
    val loadBins: List<Int>
    val values: List<List<Int>>
    val loadType: TableModelBase.LoadType
}

object TableModelBase {
    const val MAP_LOAD_SCALE = 2
    const val TPS_LOAD_DIVISOR = 2

    enum class LoadType {
        MAP,    // Speed Density (MAP kPa)
        TPS     // Alpha-N (TPS %)
    }

    enum class StorageFormat(val totalSize: Int) {
        MODERN_288(288),
        LEGACY_304(304);

        companion object {
            fun fromTotalSize(size: Int): StorageFormat? =
                values().firstOrNull { it.totalSize == size }
        }
    }

    // ============ Load conversion helpers ============

    fun rawLoadToUser(rawValue: Int, loadType: LoadType): Int {
        return if (loadType == LoadType.MAP) rawValue * MAP_LOAD_SCALE else rawValue / TPS_LOAD_DIVISOR
    }

    fun userLoadToRaw(userValue: Int, loadType: LoadType): Int {
        return if (loadType == LoadType.MAP) userValue / MAP_LOAD_SCALE else userValue * TPS_LOAD_DIVISOR
    }

    fun maxLoadForType(loadType: LoadType): Int {
        return if (loadType == LoadType.MAP) MAP_LOAD_SCALE * 255 else 100
    }

    // ============ Format detection ============

    fun detectFormat(data: ByteArray, formatHint: StorageFormat?): StorageFormat? {
        formatHint?.let { hint ->
            if (data.size >= hint.totalSize) {
                return hint
            }
        }

        return when {
            data.size >= StorageFormat.LEGACY_304.totalSize -> StorageFormat.LEGACY_304
            data.size >= StorageFormat.MODERN_288.totalSize -> StorageFormat.MODERN_288
            else -> null
        }
    }

    // ============ Axis validation ============

    fun isBlankAxisPage(rpmBins: List<Int>, loadBins: List<Int>): Boolean {
        return rpmBins.none { it > 0 } && loadBins.none { it > 0 }
    }

    // ============ Generic parsing (with value transformation callback) ============

    /**
     * Parse modern 288-byte format.
     * @param valueTransform Lambda to transform raw values (e.g., ignition subtracts 40)
     * @return Triple of (rpmBins, loadBins, values)
     */
    fun parseModernFormat(
        data: ByteArray,
        loadType: LoadType,
        valueTransform: (Int) -> Int = { it }
    ): Triple<List<Int>, List<Int>, List<List<Int>>> {
        val rpmBins = List(16) { i ->
            val raw = data[256 + i].toInt() and 0xFF
            raw * 100
        }

        val loadBins = List(16) { i ->
            rawLoadToUser(data[272 + i].toInt() and 0xFF, loadType)
        }

        val values = MutableList(16) { row ->
            MutableList(16) { col ->
                val offset = (row * 16) + col
                val rawValue = data[offset].toInt() and 0xFF
                valueTransform(rawValue)
            }
        }

        return Triple(rpmBins, loadBins, values)
    }

    /**
     * Parse legacy 304-byte format.
     * @param valueTransform Lambda to transform raw values (e.g., ignition subtracts 40)
     * @return Triple of (rpmBins, loadBins, values)
     */
    fun parseLegacyFormat(
        data: ByteArray,
        loadType: LoadType,
        valueTransform: (Int) -> Int = { it }
    ): Triple<List<Int>, List<Int>, List<List<Int>>> {
        val rpmBins = List(16) { i ->
            val offset = i * 2
            val raw = ((data[offset].toInt() and 0xFF) shl 8) or
                (data[offset + 1].toInt() and 0xFF)
            raw * 100
        }

        val loadBins = List(16) { i ->
            rawLoadToUser(data[32 + i].toInt() and 0xFF, loadType)
        }

        val values = MutableList(16) { row ->
            MutableList(16) { col ->
                val offset = 48 + (row * 16) + col
                val rawValue = data[offset].toInt() and 0xFF
                valueTransform(rawValue)
            }
        }

        return Triple(rpmBins, loadBins, values)
    }

    // ============ Generic serialization (with value transformation callback) ============

    /**
     * Serialize to modern 288-byte format.
     * @param valueTransform Lambda to transform user values before storing (e.g., ignition adds 40)
     */
    fun serializeModernFormat(
        rpmBins: List<Int>,
        loadBins: List<Int>,
        values: List<List<Int>>,
        loadType: LoadType,
        valueTransform: (Int) -> Int = { it }
    ): ByteArray {
        val data = ByteArray(StorageFormat.MODERN_288.totalSize)

        // Store values
        for (row in 0 until 16) {
            for (col in 0 until 16) {
                val offset = (row * 16) + col
                val rawValue = valueTransform(values[row][col]).coerceIn(0, 255)
                data[offset] = rawValue.toByte()
            }
        }

        // Store RPM bins
        for (i in 0 until 16) {
            val rpmHundreds = (rpmBins[i] / 100).coerceIn(0, 255)
            data[256 + i] = rpmHundreds.toByte()
        }

        // Store load bins
        for (i in 0 until 16) {
            val rawLoad = userLoadToRaw(loadBins[i], loadType).coerceIn(0, 255)
            data[272 + i] = rawLoad.toByte()
        }

        return data
    }

    /**
     * Serialize to legacy 304-byte format.
     * @param valueTransform Lambda to transform user values before storing (e.g., ignition adds 40)
     */
    fun serializeLegacyFormat(
        rpmBins: List<Int>,
        loadBins: List<Int>,
        values: List<List<Int>>,
        loadType: LoadType,
        valueTransform: (Int) -> Int = { it }
    ): ByteArray {
        val data = ByteArray(StorageFormat.LEGACY_304.totalSize)

        // Store RPM bins (2 bytes each, big-endian)
        for (i in 0 until 16) {
            val rpmHundreds = rpmBins[i] / 100
            val offset = i * 2
            data[offset] = ((rpmHundreds shr 8) and 0xFF).toByte()
            data[offset + 1] = (rpmHundreds and 0xFF).toByte()
        }

        // Store load bins
        for (i in 0 until 16) {
            val rawLoad = userLoadToRaw(loadBins[i], loadType).coerceIn(0, 255)
            data[32 + i] = rawLoad.toByte()
        }

        // Store values
        for (row in 0 until 16) {
            for (col in 0 until 16) {
                val offset = 48 + (row * 16) + col
                val rawValue = valueTransform(values[row][col]).coerceIn(0, 255)
                data[offset] = rawValue.toByte()
            }
        }

        return data
    }
}

// ============ Generic helper functions for TableModel implementations ============

/**
 * Set value at specific row/column with custom validation.
 * @param row Row index (0-15)
 * @param col Column index (0-15)
 * @param newValue Raw value to set
 * @param factory Lambda to create new table instance with updated values
 * @param validate Lambda to apply value validation/coercion
 */
fun <T> setTableValue(
    table: TableModel,
    row: Int,
    col: Int,
    newValue: Int,
    validate: (Int) -> Int,
    factory: (List<List<Int>>) -> T
): T {
    val newValues = table.values.mapIndexed { r, rowValues ->
        if (r == row) {
            rowValues.mapIndexed { c, value ->
                if (c == col) validate(newValue) else value
            }
        } else {
            rowValues
        }
    }
    return factory(newValues)
}

/**
 * Update RPM bin at specific index (0-15) to hundredths value (100-25500).
 * @param factory Lambda to create new table instance with updated RPM bins
 */
fun <T> setTableRpmBin(
    table: TableModel,
    index: Int,
    newRpm: Int,
    factory: (List<Int>) -> T
): T {
    if (index !in 0..15) return factory(table.rpmBins)
    val newRpmBins = table.rpmBins.toMutableList()
    newRpmBins[index] = newRpm.coerceIn(100, 25500)
    return factory(newRpmBins)
}

/**
 * Update Load bin at specific index (0-15).
 * @param factory Lambda to create new table instance with updated load bins
 */
fun <T> setTableLoadBin(
    table: TableModel,
    index: Int,
    newLoad: Int,
    factory: (List<Int>) -> T
): T {
    if (index !in 0..15) return factory(table.loadBins)
    val newLoadBins = table.loadBins.toMutableList()
    newLoadBins[index] = newLoad.coerceIn(0, TableModelBase.maxLoadForType(table.loadType))
    return factory(newLoadBins)
}

/**
 * Convert load bins from one type to another (MAP <-> TPS).
 * @param factory Lambda to create new table instance with converted load bins and new load type
 */
fun <T> convertTableLoadType(
    table: TableModel,
    newLoadType: TableModelBase.LoadType,
    factory: (List<Int>, TableModelBase.LoadType) -> T
): T {
    if (table.loadType == newLoadType) return factory(table.loadBins, table.loadType)
    val convertedLoadBins = table.loadBins.map { load ->
        val rawLoad = TableModelBase.userLoadToRaw(load, table.loadType)
        TableModelBase.rawLoadToUser(rawLoad, newLoadType)
    }
    return factory(convertedLoadBins, newLoadType)
}
