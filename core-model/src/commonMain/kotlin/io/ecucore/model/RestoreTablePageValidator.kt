package io.ecucore.model

/**
 * Defensive fixer for table pages restored from backups.
 *
 * Some Speeduino firmware builds reject table pages with RANGE_ERR when axis bins
 * are not strictly increasing or when raw values fall outside the expected raw range.
 *
 * This helper only touches known table pages and only clamps them to the raw layout
 * expected by the ECU. It is used as a fallback during restore, not during normal writes.
 */
object RestoreTablePageValidator {
    data class Result(
        val data: ByteArray,
        val changed: Boolean,
    )

    fun sanitize(pageNum: Int, data: ByteArray): Result? {
        return when (pageNum) {
            // Page 2 = VE table (16x16 modern layout). Page 1 in this app's numbering
            // is "Engine Constants" (128-byte settings page, not a table) and must
            // never be run through the table sanitizer.
            2 -> sanitizeModernTable(
                data = data,
                valueMin = 0,
                valueMax = 255,
            )

            3 -> sanitizeModernTable(
                data = data,
                valueMin = 0,
                valueMax = 110,
            )

            5 -> sanitizeModernTable(
                data = data,
                valueMin = 70,
                valueMax = 255,
            )

            else -> null
        }
    }

    private fun sanitizeModernTable(
        data: ByteArray,
        valueMin: Int,
        valueMax: Int,
    ): Result {
        if (data.size < 288) {
            return Result(data = data.copyOf(), changed = false)
        }

        val result = data.copyOf()
        var changed = false

        fun readU8(offset: Int): Int {
            if (offset !in result.indices) return 0
            return result[offset].toInt() and 0xFF
        }

        fun writeU8(offset: Int, value: Int) {
            if (offset !in result.indices) return
            val clamped = value.coerceIn(0, 255)
            if ((result[offset].toInt() and 0xFF) != clamped) {
                result[offset] = clamped.toByte()
                changed = true
            }
        }

        fun clampValueAxis(offset: Int) {
            for (i in 0 until 256) {
                val index = offset + i
                if (index !in result.indices) break
                val current = readU8(index)
                val clamped = current.coerceIn(valueMin, valueMax)
                if (current != clamped) {
                    writeU8(index, clamped)
                }
            }
        }

        fun normalizeStrictlyIncreasingAxis(offset: Int, length: Int, min: Int) {
            var previous = min - 1
            for (i in 0 until length) {
                val index = offset + i
                if (index !in result.indices) break
                val current = readU8(index)
                var normalized = current.coerceIn(min, 255)
                val floor = if (i == 0) min else previous + 1
                if (normalized < floor) {
                    normalized = floor
                }
                if (normalized != current) {
                    writeU8(index, normalized)
                }
                previous = normalized
            }
        }

        // Values first, then RPM bins, then load bins.
        clampValueAxis(0)
        normalizeStrictlyIncreasingAxis(offset = 256, length = 16, min = 1)
        normalizeStrictlyIncreasingAxis(offset = 272, length = 16, min = 0)

        return Result(data = result, changed = changed)
    }
}
