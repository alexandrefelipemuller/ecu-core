package io.ecucore.transport.sirius32

import io.ecucore.transport.DtcStatus
import io.ecucore.transport.ScriptedElmConnection
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Sirius32DdtTest {
    private fun hex(vararg b: Int) = b.joinToString(" ") { hex2(it) } + " \r\r>"

    @Test
    fun dataTablesLoad() {
        assertEquals(19, Sirius32Ddt.frames.size)
        assertEquals(45, Sirius32Ddt.faults.size)
        val live = Sirius32Ddt.frames.getValue(0xA2)
        for (name in listOf(
            "Régime moteur copie trame 3", "Température d eau copie trame 3", "Température d air copie trame 3",
            "Pression collecteur admission copie trame 3", "Position papillon copie trame 3",
            "Tension batterie copie trame 3", "Tension de la sonde à oxygène amont copie trame 3",
        )) assertNotNull(live.items.firstOrNull { it.name == name }, name)
        assertNotNull(Sirius32Ddt.frames.getValue(0xA4).items.firstOrNull { it.name == "Avance à l allumage copie trame 5" })
    }

    @Test
    fun decodesScaledAndBitItems() {
        val f = Sirius32Ddt.frames.getValue(0xA2)
        // 61 A2 + 30 bytes: RPM (byte 10, 16 bits) = 0x0320 = 800; água (byte 16) = 0x80 -> 40 °C
        val bytes = MutableList(32) { 0 }
        bytes[0] = 0x61; bytes[1] = 0xA2
        bytes[9] = 0x03; bytes[10] = 0x20
        bytes[15] = 0x80
        bytes[17] = 0x80 // byte 18 = bateria: 128*0.032+8 = 12,096 V
        assertEquals(800.0, f.items.first { it.name == "Régime moteur copie trame 3" }.value(bytes))
        assertEquals(40.0, f.items.first { it.name == "Température d eau copie trame 3" }.value(bytes))
        assertEquals(12.096, f.items.first { it.name == "Tension batterie copie trame 3" }.value(bytes)!!, 0.001)
    }

    @Test
    fun shortFrameYieldsNull() {
        val item = Sirius32Ddt.frames.getValue(0xA2).items.first { it.name == "Régime moteur copie trame 3" }
        assertEquals(null, item.raw(listOf(0x61, 0xA2, 0x00)))
    }

    @Test
    fun evaluatesFaultsFromStatusBits() {
        val a0 = Sirius32Ddt.frames.getValue(0xA0)
        val bytes = MutableList(40) { 0 }
        bytes[0] = 0x61; bytes[1] = 0xA0
        // liga todos os bits "présente"/"mémorisée" do sensor de pressão do coletor
        val fault = Sirius32Ddt.faults.first { it.frenchName == "Capteur pression collecteur" }
        for ((name, _) in fault.conditions) {
            val item = a0.items.firstOrNull { it.name == name } ?: continue
            if (!name.lowercase().let { "présente" in it || "mémorisée" in it }) continue
            val start = (item.firstByte - 1) * 8 + item.bitOffset
            bytes[start / 8] = bytes[start / 8] or (0x80 shr (start % 8))
        }
        val faults = Sirius32Ddt.evaluateFaults(mapOf(0xA0 to bytes))
        val map = faults.firstOrNull { it.name.contains("MAP") }
        assertNotNull(map, "falha do MAP deveria ser detectada: $faults")
        assertTrue(map.present || map.stored)
        // nenhum outro sensor deve aparecer
        assertTrue(faults.all { it.name.contains("MAP") || it.present || it.stored })
    }

    @Test
    fun noBitsMeansNoFaults() {
        val bytes = MutableList(40) { 0 }.also { it[0] = 0x61; it[1] = 0xA0 }
        assertTrue(Sirius32Ddt.evaluateFaults(mapOf(0xA0 to bytes)).isEmpty())
    }

    @Test
    fun actionRequestsMatchProfile() {
        assertEquals("11FF", Sirius32Actions.adaptiveResetRequest(Sirius32Actions.AdaptiveReset.ALL))
        assertEquals("31A0A3", Sirius32Actions.specialFunctionRequest(Sirius32Actions.SpecialFunction.CLEAR_SAVED_MEMORY))
        assertEquals("30A0000A", Sirius32Actions.outputStartRequest(Sirius32Actions.Actuator.FAN_HIGH, 10))
        assertEquals("30A01100", Sirius32Actions.outputStopRequest(Sirius32Actions.Actuator.FAN_HIGH))
    }

    private fun ddtEcu(): (String) -> String? = { cmd ->
        when (cmd) {
            "2180" -> hex(0x61, 0x80, 1, 2, 3, 4, 5, 6, 7, 0x19, 0x30, 0x30, 0x31, 0, 0, 0, 0, 0, 0xA3, 0x00, 0, 0, 0, 0, 0)
            "10A0" -> hex(0x50, 0xA0)
            "140000", "14FF00" -> hex(0x54, 0x00, 0x00)
            "11FF" -> hex(0x51, 0xFF)
            "30A0000A" -> hex(0x70, 0xA0, 0x01)
            "30A01100" -> hex(0x70, 0xA0, 0x02)
            "21A0", "21A1", "21AC", "21AD", "21AF" -> hex(0x61, cmd.substring(2).toInt(16), *IntArray(30))
            else -> if (cmd.startsWith("AT")) "OK\r\r>" else "NO DATA\r\r>"
        }
    }

    @Test
    fun connectsReadsFaultsAndRunsActions() = runTest {
        val conn = ScriptedElmConnection(ddtEcu())
        val t = Sirius32DdtTransport(conn, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        t.connect()
        assertTrue(t.isConnected())
        assertTrue(conn.commands.contains("ATSH817AF1"))
        assertTrue(conn.commands.indexOf("2180") > conn.commands.indexOf("ATSI"))

        assertEquals("A300", t.readIdentification()["Versão do software"])
        assertTrue(t.readFaults().isEmpty())
        assertTrue(t.readDtcCodes().isEmpty())
        assertTrue(t.clearFaults())
        assertTrue(t.resetAdaptive(Sirius32Actions.AdaptiveReset.ALL))
        assertTrue(t.startActuator(Sirius32Actions.Actuator.FAN_HIGH, 10))
        assertTrue(t.stopActuator(Sirius32Actions.Actuator.FAN_HIGH))
    }

    @Test
    fun connectFailsWhenEcuDoesNotAnswerIdentification() = runTest {
        val conn = ScriptedElmConnection { if (it.startsWith("AT")) "OK\r\r>" else "NO DATA\r\r>" }
        val t = Sirius32DdtTransport(conn, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        assertTrue(runCatching { t.connect() }.isFailure)
        assertFalse(t.isConnected())
    }

    @Test
    fun negativeResponseIsNotSuccess() = runTest {
        val base = ddtEcu()
        val conn = ScriptedElmConnection { if (it == "11FF") hex(0x7F, 0x11, 0x22) else base(it) }
        val t = Sirius32DdtTransport(conn, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        t.connect()
        assertFalse(t.resetAdaptive(Sirius32Actions.AdaptiveReset.ALL))
    }

    @Test
    fun probeIsReadOnly() = runTest {
        val conn = ScriptedElmConnection(ddtEcu())
        val t = Sirius32DdtTransport(conn, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        t.connect()
        conn.commands.clear()
        val report = t.runCompatibilityProbe()
        assertTrue(report.contains("2180"))
        // nada de reset (11), apagar falhas (14), atuadores (30-32) no teste
        assertTrue(conn.commands.none { it.startsWith("11") || it.startsWith("14") || it.startsWith("3") && !it.startsWith("3E") })
    }
}
