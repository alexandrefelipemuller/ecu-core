package io.ecucore.model

/**
 * On Speeduino, page 9 offset 0 (configPage9) holds the secondary-serial/CAN transport bitfields
 * (enable_secondarySerial, intcan_available, enable_intcan, secondarySerialProtocol) — i.e. the
 * very channel the app is talking through. Writing a different value there mid-session has been
 * observed in the field to permanently desync/kill the connection (no recovery via drain, resync,
 * or reconnect). This object centralizes which page bytes must never be included in a factory
 * reset / bulk-restore write or verification, so callers can't accidentally regress the fix.
 */
object TransportCriticalPageBytes {
    const val SPEEDUINO_SECONDARY_SERIAL_PAGE = 9
    const val SPEEDUINO_SECONDARY_SERIAL_OFFSET = 0

    fun protectedOffsets(family: EcuFamily, pageNum: Int): Set<Int> =
        if (family == EcuFamily.SPEEDUINO && pageNum == SPEEDUINO_SECONDARY_SERIAL_PAGE) {
            setOf(SPEEDUINO_SECONDARY_SERIAL_OFFSET)
        } else {
            emptySet()
        }

    /** Contiguous offset ranges of [pageSize] bytes that are safe to write, excluding protected offsets. */
    fun writableRanges(family: EcuFamily, pageNum: Int, pageSize: Int): List<IntRange> {
        val protected = protectedOffsets(family, pageNum)
        if (protected.isEmpty()) return listOf(0 until pageSize)

        val ranges = mutableListOf<IntRange>()
        var rangeStart: Int? = null
        for (offset in 0 until pageSize) {
            if (offset in protected) {
                rangeStart?.let { ranges.add(it until offset) }
                rangeStart = null
            } else if (rangeStart == null) {
                rangeStart = offset
            }
        }
        rangeStart?.let { ranges.add(it until pageSize) }
        return ranges
    }

    /** Index of the first byte where [expected] and [actual] differ, ignoring protected offsets, or -1 if equal. */
    fun firstMeaningfulDiff(family: EcuFamily, pageNum: Int, expected: ByteArray, actual: ByteArray): Int {
        val protected = protectedOffsets(family, pageNum)
        val len = minOf(expected.size, actual.size)
        for (i in 0 until len) {
            if (i in protected) continue
            if (expected[i] != actual[i]) return i
        }
        if (expected.size != actual.size) return len
        return -1
    }

    fun matchesIgnoringProtected(family: EcuFamily, pageNum: Int, expected: ByteArray, actual: ByteArray): Boolean =
        firstMeaningfulDiff(family, pageNum, expected, actual) == -1
}
