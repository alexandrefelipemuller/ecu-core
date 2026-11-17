package io.ecucore.transport

import io.ecucore.EcuLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reproduz as respostas reais de um Clio K4M / Siemens Sirius 32 capturadas em
 * 2026-04-07 (EcuManager/logs/device_obd2_investigation/..._clio_k4m).
 */
class Obd2K4mReplayTest {
    private val k4m: (String) -> String? = { cmd ->
        val body = when (cmd) {
            "ATZ" -> "\r\rELM327 v2.1"
            "ATDP" -> "ISO 14230-4 (KWP 5BAUD)"
            "ATDPN" -> "A4"
            "0100" -> "41 00 BE 3E 80 10 "
            "0105" -> "41 05 3F "
            "0106" -> "41 06 80 "
            "0107" -> "41 07 8C "
            "010B" -> "41 0B 5C "
            "010C" -> "41 0C 03 20 "
            "010D" -> "41 0D 00 "
            "010E" -> "41 0E 81 "
            "010F" -> "41 0F 3F "
            "0111" -> "41 11 19 "
            "0104" -> "41 04 00 "
            "0114" -> "41 14 46 80 "
            "0115" -> "41 15 52 00 "
            "0120", "0140", "0142", "0144", "0110" -> "7F 01 12 "
            "0900", "0902", "0904" -> "7F 09 11 "
            "03", "07", "0A" -> "NO DATA"
            else -> if (cmd.startsWith("AT")) "OK" else "NO DATA"
        }
        "$body\r\r>"
    }

    @Test
    fun livedataFromRealK4mCaptureDecodesAndFlagsMissingChannels() = runTest {
        val samples = mutableListOf<EcuLiveData>()
        val conn = ScriptedElmConnection(k4m)
        val t = Obd2Transport(
            connection = conn,
            onDataReceived = { samples += it },
            onConnectionStateChanged = {},
            onError = {},
            enableFeatureOptimization = false,
        )
        withContext(Dispatchers.Default) {
            t.connect()
            t.startLiveDataStream(50)
            withTimeout(20_000) {
                while (samples.none { it.unavailableChannels.isNotEmpty() }) delay(50)
            }
            t.stopLiveDataStream()
        }

        val last = samples.last { it.unavailableChannels.isNotEmpty() }
        assertEquals(200, last.rpm)
        assertEquals(23, last.coolantTemp)
        assertEquals(92, last.mapPressure)
        assertEquals(1, last.advance) // 0x81 -> 0,5° (antes aparecia 65°)
        assertEquals(0, last.engineLoadPct)
        assertNotNull(last.shortFuelTrimPct)
        assertEquals(9.375, last.longFuelTrimPct!!, 0.01)
        // 0142 responde NRC 12 no K4M: a bateria NÃO é medida
        assertTrue("battery" in last.unavailableChannels)
        assertTrue("advance" !in last.unavailableChannels)
    }
}
