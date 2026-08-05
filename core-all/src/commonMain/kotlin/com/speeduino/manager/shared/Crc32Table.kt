package com.speeduino.manager.shared

/**
 * CRC32 (ISO-3309, mesma variante de java.util.zip.CRC32) em Kotlin puro para
 * compilar em todos os targets (Android/JVM/iOS). Verificado contra
 * java.util.zip.CRC32 em Crc32TableDifferentialTest (jvmTest).
 */
internal object Crc32Table {
    private val table = IntArray(256).also { t ->
        for (n in 0 until 256) {
            var c = n
            repeat(8) {
                c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1)
            }
            t[n] = c
        }
    }

    fun compute(data: ByteArray): Long {
        var crc = 0xFFFFFFFF.toInt()
        for (byte in data) {
            val index = (crc xor byte.toInt()) and 0xFF
            crc = (table[index] xor (crc ushr 8))
        }
        return (crc.toLong() xor 0xFFFFFFFFL) and 0xFFFFFFFFL
    }
}
