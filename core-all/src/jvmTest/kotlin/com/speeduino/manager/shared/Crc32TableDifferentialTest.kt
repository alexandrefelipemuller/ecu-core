package com.speeduino.manager.shared

import java.util.zip.CRC32
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class Crc32TableDifferentialTest {

    private fun jvmCrc32(data: ByteArray): Long {
        val crc = CRC32()
        crc.update(data)
        return crc.value
    }

    @Test
    fun matchesJavaUtilZipOnKnownVectors() {
        val vectors = listOf(
            ByteArray(0),
            byteArrayOf(0x00),
            byteArrayOf(0xFF.toByte()),
            "123456789".encodeToByteArray(),
            ByteArray(304) { it.toByte() },
            ByteArray(127) { (it * 7).toByte() },
        )
        for (v in vectors) {
            assertEquals(jvmCrc32(v), Crc32Table.compute(v), "CRC mismatch for ${v.size}-byte vector")
        }
    }

    @Test
    fun matchesJavaUtilZipOnRandomPayloads() {
        val random = Random(42)
        repeat(200) {
            val data = random.nextBytes(random.nextInt(1, 2048))
            assertEquals(jvmCrc32(data), Crc32Table.compute(data))
        }
    }
}
