package com.speeduino.manager

object ByteArrayParser {
    fun tempRawToC(raw: Int): Int {
        return (((raw / 10.0) - 32.0) * 5.0 / 9.0).toInt()
    }

    fun u8(data: ByteArray, index: Int): Int {
        return data.getOrNull(index)?.toInt()?.and(0xFF) ?: 0
    }

    fun u16be(data: ByteArray, index: Int): Int {
        val msb = u8(data, index)
        val lsb = u8(data, index + 1)
        return (msb shl 8) or lsb
    }

    fun s16be(data: ByteArray, index: Int): Int {
        return u16be(data, index).let { raw ->
            if (raw and 0x8000 != 0) raw - 0x10000 else raw
        }
    }
}
