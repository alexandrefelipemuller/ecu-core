package io.ecucore

import io.ecucore.definition.IniParser
import io.ecucore.model.SpeeduinoAntiTheft
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Antifurto no Speeduino: `tpsflood` (página 4, offset 18) em 0 com marca e original na página 9. */
class EcuClientSpeeduinoAntiTheftTest {

    private val d = '$'

    // Linhas do speeduino_202501.ini (páginas 2, 3 e 5 só para o layout de tabelas ser aceito).
    private fun ini(markerLine: String = """unused10_110 = scalar, U08, 110, "", 1, 0, 0, 255, 0""") = """
        [MegaTune]
        signature = "speeduino 202501"

        [Constants]
        nPages = 15
        pageIdentifier = "${d}tsCanId\x01", "${d}tsCanId\x02", "${d}tsCanId\x03", "${d}tsCanId\x04", "${d}tsCanId\x05", "${d}tsCanId\x06", "${d}tsCanId\x07", "${d}tsCanId\x08", "${d}tsCanId\x09"
        pageSize = 128, 288, 288, 128, 288, 128, 240, 384, 192

        page = 2
        veTable = array, U08, 0, [16x16], "%", 1.0, 0.0, 0.0, 255.0, 0
        rpmBins = array, U08, 256, [16], "RPM", 100.0, 0.0, 100.0, 25500.0, 0
        fuelLoadBins = array, U08, 272, [16], "kPa", 2.0, 0.0, 0.0, 511.0, 0

        page = 3
        advTable1 = array, U08, 0, [16x16], "deg", 1.0, -40.0, -40.0, 70.0, 0
        rpmBins2 = array, U08, 256, [16], "RPM", 100.0, 0.0, 100.0, 25500.0, 0
        mapBins1 = array, U08, 272, [16], "kPa", 2.0, 0.0, 0.0, 511.0, 0

        page = 4
        tpsflood = scalar, U08, 18, "%", 0.5, 0.0, 0.0, 100.0, 1

        page = 5
        afrTable = array, U08, 0, [16x16], "AFR", 0.1, 0.0, 7.0, 25.5, 1
        rpmBinsAFR = array, U08, 256, [16], "RPM", 100.0, 0.0, 100.0, 25500.0, 0
        loadBinsAFR = array, U08, 272, [16], "kPa", 2.0, 0.0, 0.0, 511.0, 0

        page = 9
        $markerLine
        unused10_111 = scalar, U08, 111, "", 1, 0, 0, 255, 0
    """.trimIndent()

    private suspend fun connect(running: Boolean = false, iniText: String = ini()): Pair<EcuClient, ClientFakeEcu> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025)
        val client = EcuClient(ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        client.connect()
        client.applyIniDefinition(IniParser.parse("speeduino.ini", iniText))
        // Bloco ao vivo zerado = motor parado; tudo 0xFF = rotação diferente de zero.
        ecu.liveBytes = if (running) ByteArray(256) { 0xFF.toByte() } else null
        ecu.load(4, 18, byteArrayOf(180.toByte())) // desafogador em 90 %
        ecu.writes.clear()
        ecu.burns.clear()
        return client to ecu
    }

    private fun ClientFakeEcu.flood() = mem(4)[18].toInt() and 0xFF
    private fun ClientFakeEcu.marker() = mem(9)[110].toInt() and 0xFF
    private fun ClientFakeEcu.stored() = mem(9)[111].toInt() and 0xFF

    @Test
    fun `travar grava a marca com o original e depois zera o desafogador`() = runBlocking<Unit> {
        val (c, ecu) = connect()

        assertEquals(180, c.lockAntiTheft())

        assertEquals(listOf(9, 4), ecu.writes.map { it.id })
        assertEquals(SpeeduinoAntiTheft.MARKER, ecu.marker())
        assertEquals(180, ecu.stored())
        assertEquals(0, ecu.flood())
        assertEquals(2, ecu.burns.size)
        assertTrue(c.readAntiTheftLocked())
    }

    @Test
    fun `destravar sem original salvo usa o da marca e apaga a marca depois`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        c.lockAntiTheft()
        ecu.writes.clear()

        c.unlockAntiTheft()

        assertEquals(listOf(4, 9), ecu.writes.map { it.id })
        assertEquals(180, ecu.flood())
        assertEquals(0, ecu.marker())
        assertFalse(c.readAntiTheftLocked())
    }

    @Test
    fun `desafogador em 0 sem a marca nao e trava do app`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        ecu.load(4, 18, byteArrayOf(0))
        assertFalse(c.readAntiTheftLocked())
        c.unlockAntiTheft()
        assertTrue(ecu.writes.isEmpty())
        // E não trava por cima: o original seria 0.
        assertFailsWith<IllegalArgumentException> { c.lockAntiTheft() }
        assertTrue(ecu.writes.isEmpty())
    }

    @Test
    fun `motor girando nao trava`() = runBlocking<Unit> {
        val (c, ecu) = connect(running = true)
        assertFailsWith<IllegalStateException> { c.lockAntiTheft() }
        assertTrue(ecu.writes.isEmpty())
    }

    @Test
    fun `ini sem os bytes livres recusa`() = runBlocking<Unit> {
        val (c, ecu) = connect(iniText = ini(markerLine = ""))
        assertFailsWith<UnsupportedOperationException> { c.readAntiTheftLocked() }
        assertFailsWith<UnsupportedOperationException> { c.lockAntiTheft() }
        assertTrue(ecu.writes.isEmpty())
    }
}
