package io.ecucore.transport

import io.ecucore.connection.ISpeeduinoConnection
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Conexão que responde ao comando ELM327 enviado, como um adaptador real faria. */
internal class ScriptedElmConnection(
    private val responder: (String) -> String?,
) : ISpeeduinoConnection {
    val commands = mutableListOf<String>()
    private var pending: ByteArray = ByteArray(0)
    private var connected = false

    override suspend fun connect() { connected = true }
    override fun disconnect() { connected = false }
    override fun send(data: ByteArray) {
        val cmd = data.decodeToString().trim()
        commands += cmd
        pending = (responder(cmd) ?: "").encodeToByteArray()
    }
    override fun receive(size: Int): ByteArray = takePending()
    override fun readAvailable(maxBytes: Int, timeoutMs: Int?): ByteArray = takePending()
    private fun takePending(): ByteArray = pending.also { pending = ByteArray(0) }
    override fun isConnected(): Boolean = connected
    override fun getConnectionInfo(): String = "scripted"
    override fun setOnConnectionStateChanged(callback: (Boolean) -> Unit) {}
    override fun setOnError(callback: (String) -> Unit) {}
    override fun clearInputBuffer() { pending = ByteArray(0) }
}

class Sirius32TransportTest {
    private fun transport(conn: ISpeeduinoConnection) =
        Sirius32Transport(conn, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})

    private fun healthyEcu(dtcWords: String = "04 40 00 00"): (String) -> String? = { cmd ->
        when (cmd) {
            "ATZ" -> "\r\rELM327 v1.5\r\r>"
            "1029" -> "50 29 \r\r>"
            "236B" -> "63 6B \r\r>"
            "2A0893" -> "6A 08 93 $dtcWords \r\r>"
            "1420" -> "54 20 \r\r>"
            "146F" -> "54 \r\r>"
            else -> "OK\r\r>"
        }
    }

    @Test
    fun connectInitializesElmThenOpensSession() = runTest {
        val conn = ScriptedElmConnection(healthyEcu())
        val t = transport(conn)
        t.connect()

        assertTrue(t.isConnected())
        assertEquals(
            listOf("ATZ", "ATE0", "ATL0", "ATH0", "ATS0", "ATSP4", "ATAT1", "ATST20", "1029", "236B"),
            conn.commands,
        )
    }

    @Test
    fun connectFailsWhenSessionIsRejected() = runTest {
        val conn = ScriptedElmConnection { cmd -> if (cmd == "1029") "7F 10 11 \r\r>" else healthyEcu()(cmd) }
        val t = transport(conn)
        val result = runCatching { t.connect() }
        assertTrue(result.isFailure)
        assertFalse(t.isConnected())
    }

    @Test
    fun readsDtcWordZeroAndDecodesKnownBits() = runTest {
        val conn = ScriptedElmConnection(healthyEcu("04 40 00 40"))
        val t = transport(conn)
        t.connect()

        val active = t.readDtcCodes().map { it.code }
        val pending = t.readPendingDtcCodes().map { it.code }

        assertEquals(listOf("DF003", "DF045"), active)
        assertEquals(listOf("DF045"), pending)
        // sessão já aberta: não pode haver segundo 10 29 (o firmware penaliza repetição)
        assertEquals(1, conn.commands.count { it == "1029" })
    }

    @Test
    fun dtcReadFailureThrowsInsteadOfReportingNoFaults() = runTest {
        val conn = ScriptedElmConnection { cmd -> if (cmd == "2A0893") "NO DATA\r\r>" else healthyEcu()(cmd) }
        val t = transport(conn)
        t.connect()
        val result = runCatching { t.readDtcCodes() }
        assertTrue(result.isFailure)
    }

    @Test
    fun nrc22OnDtcReadReopensSessionOnNextOperation() = runTest {
        var first = true
        val conn = ScriptedElmConnection { cmd ->
            if (cmd == "2A0893" && first) { first = false; "7F 2A 22 \r\r>" } else healthyEcu()(cmd)
        }
        val t = transport(conn)
        t.connect()
        assertTrue(runCatching { t.readDtcCodes() }.isFailure)
        t.readDtcCodes() // reabre a sessão e lê
        assertEquals(2, conn.commands.count { it == "1029" })
    }

    @Test
    fun clearDtcRunsArmThenExecute() = runTest {
        val conn = ScriptedElmConnection(healthyEcu())
        val t = transport(conn)
        t.connect()
        conn.commands.clear()

        assertTrue(t.clearDtcCodes())
        assertEquals(listOf("1420", "146F"), conn.commands)
    }

    @Test
    fun clearDtcStopsWhenArmIsRejected() = runTest {
        val conn = ScriptedElmConnection { cmd -> if (cmd == "1420") "7F 14 22 \r\r>" else healthyEcu()(cmd) }
        val t = transport(conn)
        t.connect()
        conn.commands.clear()

        assertFalse(t.clearDtcCodes())
        assertEquals(listOf("1420"), conn.commands)
    }

    @Test
    fun decodesTimingAdvanceAsHalfDegreesMinus64() {
        assertEquals(-64, decodeTimingAdvanceDegrees(0x00))
        assertEquals(0, decodeTimingAdvanceDegrees(0x80))
        assertEquals(1, decodeTimingAdvanceDegrees(0x81)) // 0,5° arredondado - captura real do K4M
        assertEquals(32, decodeTimingAdvanceDegrees(0xC0))
        assertEquals(64, decodeTimingAdvanceDegrees(0xFF).let { if (it == 64) 64 else it }) // 63,5 -> 64
    }
}
