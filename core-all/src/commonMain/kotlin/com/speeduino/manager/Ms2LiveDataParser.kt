package com.speeduino.manager

object Ms2LiveDataParser {
    private const val MIN_OUTPUT_BLOCK_SIZE = 212

    fun fromOutputChannels(data: ByteArray): SpeeduinoLiveData {
        require(data.size >= MIN_OUTPUT_BLOCK_SIZE) {
            "MS2 output channels incompletos: esperado $MIN_OUTPUT_BLOCK_SIZE, recebido ${data.size}"
        }

        with(ByteArrayParser) {
            val secl = u16be(data, 0)
            val rpm = u16be(data, 6)
            val advance = s16be(data, 8) / 10
            val sparkStatus = u8(data, 10)
            val engineStatus = u8(data, 11)
            val mapPressure = s16be(data, 18) / 10
            val intakeTemp = tempRawToC(s16be(data, 20))
            val coolantTemp = tempRawToC(s16be(data, 22))
            val tps = s16be(data, 24) / 10
            val batteryVoltage = s16be(data, 26) / 10.0
            val o2 = s16be(data, 28) / 10

            return SpeeduinoLiveData(
                secl = secl,
                rpm = rpm,
                coolantTemp = coolantTemp,
                intakeTemp = intakeTemp,
                mapPressure = mapPressure,
                tps = tps,
                batteryVoltage = batteryVoltage,
                advance = advance,
                o2 = o2,
                engineStatus = engineStatus,
                sparkStatus = sparkStatus,
                outputChannelBlockSize = data.size,
                outputChannelData = data.copyOf()
            )
        }
    }
}
