package io.ecucore.transport

import io.ecucore.connection.IEcuConnection
import io.ecucore.transport.chrysler.ChryslerSciProtocol
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ponte + PCM simulada: banner/PROBE/GO I em texto, depois eco byte a byte como a PCM real. */
internal class FakeSciBridge(
    private val swap: Boolean = false,
    private val idleHigh: Int = 0,
    var codes: MutableList<Int> = mutableListOf(0x1C, 0x29),
    private val values: Map<Int, Int> = mapOf(
        0x0A to 0xC6, 0x11 to 25, 0x05 to 190, 0x1B to 160, 0x07 to 40, 0x0B to 80,
        0x02 to 20, 0x15 to 24, 0x41 to 10, 0x0E to 110, 0x12 to 0x10,
    ),
) : IEcuConnection {
    private var connected = false
    private val out = ArrayDeque<Int>()
    private var bridging = false
    private var line = StringBuilder()
    private var state = "cmd"
    private var pending = mutableListOf<Int>()
    val sent = mutableListOf<Int>()
    private val memory = mapOf(0x8002 to 0x56, 0x8003 to 0x03, 0x8004 to 0x01, 0x8005 to 0x17)

    override suspend fun connect() { connected = true; "XJBRIDGE 1.0\r\n".forEach { out.addLast(it.code) } }
    override fun disconnect() { connected = false }
    private fun pcm(vararg v: Int) = v.forEach { out.addLast(if (swap) ChryslerSciProtocol.swapNibbles(it) else it) }

    override fun send(data: ByteArray) {
        for (raw in data) {
            val r = raw.toInt() and 0xFF
            if (!bridging) {
                if (r == '\n'.code) {
                    when (line.toString().trim()) {
                        "PROBE" -> "IDLE HIGH% $idleHigh\r\n".forEach { out.addLast(it.code) }
                        "GO I" -> { "OK INVERTED\r\n".forEach { out.addLast(it.code) }; bridging = true }
                        else -> "ERR\r\n".forEach { out.addLast(it.code) }
                    }
                    line = StringBuilder()
                } else line.append(r.toChar())
                continue
            }
            sent += r
            val b = if (swap) ChryslerSciProtocol.swapNibbles(r) else r
            when {
                state == "stream" && b == 0 -> { pcm(0); state = "cmd" }
                state == "stream" -> pcm(b)
                else -> { pcm(b); pending += b; handle() }
            }
        }
    }

    private fun handle() {
        when (pending[0]) {
            0x14 -> if (pending.size == 2) { pcm(values[pending[1]] ?: 0); state = "stream"; pending.clear() }
            0x15 -> if (pending.size == 3) { pcm(memory[(pending[1] shl 8) or pending[2]] ?: 0); pending.clear() }
            0x10 -> { val body = listOf(0x10) + codes + listOf(0xFD); pcm(*(codes + listOf(0xFD, body.sum() and 0xFF)).toIntArray()); pending.clear() }
            0x17 -> { codes.clear(); pcm(0xE0); pending.clear() }
            0x13 -> if (pending.size == 2) pending.clear()
            0x23 -> if (pending.size == 2) { pcm(0xF0); pending.clear() }
            else -> pending.clear()
        }
    }

    override fun receive(size: Int): ByteArray = take(1)
    override fun readAvailable(maxBytes: Int, timeoutMs: Int?): ByteArray = take(if (maxBytes <= 0) 64 else maxBytes)
    private fun take(n: Int): ByteArray { val l = mutableListOf<Byte>(); while (l.size < n && out.isNotEmpty()) l += out.removeFirst().toByte(); return l.toByteArray() }
    override fun isConnected() = connected
    override fun getConnectionInfo() = "fake-sci"
    override fun setOnConnectionStateChanged(callback: (Boolean) -> Unit) {}
    override fun setOnError(callback: (String) -> Unit) {}
    override fun clearInputBuffer() { out.clear() }
}

class ChryslerSciTransportTest {
    private fun transport(conn: IEcuConnection, onData: (io.ecucore.EcuLiveData) -> Unit = {}) =
        ChryslerSciTransport(conn, onDataReceived = onData, onConnectionStateChanged = {}, onError = {})

    @Test
    fun connectReadsPartNumberAndBattery() = runTest {
        val t = transport(FakeSciBridge())
        t.connect()
        assertTrue(t.isConnected())
        assertTrue(t.getFirmwareInfoCached()!!.signature.contains("56030117"))
        assertEquals(0, t.lastProbeHighPercent)
    }

    @Test
    fun connectDetectsNibbleSwappedPcm() = runTest {
        val t = transport(FakeSciBridge(swap = true))
        t.connect()
        assertEquals(0xC6, t.readRawParam(ChryslerSciProtocol.PID_BATTERY_V))
    }

    @Test
    fun connectFailsWhenLineIdlesHigh() = runTest {
        val t = transport(FakeSciBridge(idleHigh = 100))
        assertTrue(runCatching { t.connect() }.isFailure)
        assertFalse(t.isConnected())
    }

    @Test
    fun readsAndClearsCodes() = runTest {
        val t = transport(FakeSciBridge())
        t.connect()
        val codes = t.readDtcCodes()
        assertEquals(listOf("SCI-1C", "SCI-29"), codes.map { it.code })
        assertEquals("THROTTLE BODY TEMP SENSOR VOLTAGE LOW", codes[0].description)
        assertTrue(t.clearDtcCodes())
        assertTrue(t.readDtcCodes().isEmpty())
    }

    @Test
    fun noCodesIsEmptyNotError() = runTest {
        val t = transport(FakeSciBridge(codes = mutableListOf()))
        t.connect()
        assertTrue(t.readDtcCodes().isEmpty())
    }

    @Test
    fun fuelTrimUsesCountsFrom128NotSignBit() {
        assertEquals(-18, ChryslerSciProtocol.fuelTrimCountsFrom128(110))
        assertTrue(ChryslerSciProtocol.fuelTrimPercent(110) < -4.0)
        assertEquals(0.0, ChryslerSciProtocol.fuelTrimPercent(128), 0.001)
    }

    @Test
    fun livePollDecodesChannels() = runTest {
        val got = mutableListOf<io.ecucore.EcuLiveData>()
        val t = transport(FakeSciBridge()) { got += it }
        t.connect()
        t.startLiveDataStream(50)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(800) }
        t.stopLiveDataStream()
        val s = got.first()
        assertEquals(800, s.rpm)
        assertEquals(12.38, s.batteryVoltage, 0.01)
        assertEquals(12, s.advance)
        assertTrue("o2" in s.unavailableChannels)
        assertTrue(s.longFuelTrimPct!! < 0)
    }

    @Test
    fun actuatorTestRejectsUnknownIdAndResetTrimAcks() = runTest {
        val t = transport(FakeSciBridge())
        t.connect()
        assertTrue(runCatching { t.startActuatorTest(0x77) }.isFailure)
        assertTrue(t.resetLearnedFuelTrim())
    }
}
