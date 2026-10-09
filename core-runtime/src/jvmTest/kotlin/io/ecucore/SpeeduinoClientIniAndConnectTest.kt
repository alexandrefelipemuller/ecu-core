package io.ecucore

import io.ecucore.definition.IniParser
import io.ecucore.model.AfrTable
import io.ecucore.model.EcuFamily
import io.ecucore.model.IgnitionTable
import io.ecucore.model.UnsupportedFirmwareException
import io.ecucore.model.VeTable
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Definições .ini aplicadas ao client e caminhos de falha/variação do connect. */
class SpeeduinoClientIniAndConnectTest {

    private fun client(ecu: ClientFakeEcu) = SpeeduinoClient(ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})

    private suspend fun connect(kind: ClientFakeEcu.Kind): Pair<SpeeduinoClient, ClientFakeEcu> {
        val ecu = ClientFakeEcu(kind)
        return client(ecu).also { it.connect() } to ecu
    }

    private val d = '$'

    private val speeduinoIni = """
        [MegaTune]
        signature = "speeduino 202501"

        [Constants]
        nPages = 15
        pageIdentifier = "${d}tsCanId\x01", "${d}tsCanId\x02", "${d}tsCanId\x03", "${d}tsCanId\x04", "${d}tsCanId\x05"
        pageSize = 128, 288, 288, 128, 288

        page = 2
        veTable = array, U08, 0, [16x16], "%", 1.0, 0.0, 0.0, 255.0, 0
        rpmBins = array, U08, 256, [16], "RPM", 100.0, 0.0, 100.0, 25500.0, 0
        fuelLoadBins = array, U08, 272, [16], "kPa", 2.0, 0.0, 0.0, 511.0, 0

        page = 3
        advTable1 = array, U08, 0, [16x16], "deg", 1.0, -40.0, -40.0, 70.0, 0
        rpmBins2 = array, U08, 256, [16], "RPM", 100.0, 0.0, 100.0, 25500.0, 0
        mapBins1 = array, U08, 272, [16], "kPa", 2.0, 0.0, 0.0, 511.0, 0

        page = 5
        lambdaTable = array, U08, 0, [16x16], "Lambda", 0.01, 0.0, 0.0, 2.0, 2
        afrTable = array, U08, lastOffset, [16x16], "AFR", 0.1, 0.0, 7.0, 25.5, 1
        rpmBinsAFR = array, U08, 256, [16], "RPM", 100.0, 0.0, 100.0, 25500.0, 0
        loadBinsAFR = array, U08, 272, [16], "kPa", 2.0, 0.0, 0.0, 511.0, 0

        page = 8
        veTable2 = array, U08, 0, [16x16], "%", 1.0, 0.0, 0.0, 255.0, 0
        advTable2 = array, U08, 288, [16x16], "deg", 1.0, -40.0, -40.0, 70.0, 0
        fuel2Mode = bits, U08, 600, [0:2], "Off", "Multiplicative", "Additive"
        spark2Mode = bits, U08, 601, [0:2], "Off", "Multiplicative", "Additive"
        fuel2Algorithm = bits, U08, 602, [0:2], "MAP", "TPS", "IMAP/EMAP"
        spark2Algorithm = bits, U08, 603, [0:2], "MAP", "TPS", "IMAP/EMAP"
        fuel3Mode = U16, 604, "Off"

        [OutputChannels]
        ochBlockSize = 130
        secl = scalar, U08, 0, "sec", 1.0, 0.0
        map = scalar, U16, 4, "kPa", 1.0, 0.0
        rpm = scalar, U16, 14, "rpm", 1.0, 0.0
        tps = scalar, U08, 25, "%", 0.5, 0.0
    """.trimIndent()

    private val ms1Ini = """
        [MegaTune]
        signature = "MS/Extra format hr_10 **********"

        [Constants]
        page = 1
        veBins1 = array, U08, 0, [2x2], "%", 1.0, 0.0, 0.0, 255.0, 0
        rpmBins1 = array, U08, 4, [2], "RPM", 100.0, 0.0, 100, 25500, 0
        mapBins1 = array, U08, 6, [2], "kPa", 1.0, 0.0, 0.0, 255.0, 0

        page = 3
        advTable1 = array, U08, 0, [2x2], "deg", 0.352, -28.4, -10.0, 80.0, 0
        rpmBins3 = array, U08, 4, [2], "RPM", 100.0, 0.0, 100, 25500, 0
        tpsBins3 = array, U08, 6, [2], "%", 1.0, 0.0, 0.0, 255.0, 0
    """.trimIndent()

    private val megaSpeedIni = """
        [MegaTune]
        queryCommand = "Q"
        signature = "MS2Extra MegaSpeed "

        [Constants]
        endianness = big
        nPages = 7
        pageSize = 1024,1024,1024,1024,1024,1024,1024
        pageIdentifier = "${d}tsCanId\x04","${d}tsCanId\x05","${d}tsCanId\x0a","${d}tsCanId\x08","${d}tsCanId\x09","${d}tsCanId\x0b","${d}tsCanId\x0c"
        ochBlockSize = 219

        page = 1
        afrTable1 = array, U08, 48, [12x12], "AFR", 0.1, 0, 1, 25, 1
        arpm_table1 = array, U16, 374, [12], "RPM", 1, 0, 0, 15000, 0
        amap_table1 = array, S16, 422, [12], "kPa", 0.1, 0, 0, 700, 1

        page = 3
        advanceTable1 = array, S16, 0, [12x12], "grau", 0.1, 0, -10, 90, 1
        srpm_table1 = array, U16, 576, [12], "RPM", 1, 0, 0, 15000, 0
        smap_table1 = array, S16, 624, [12], "kPa", 0.1, 0, 0, 700, 1

        page = 5
        veTable1 = array, U08, 0, [16x16], "%", 1, 0, 0, 255, 0
        ve1RpmBins = array, U16, 768, [16], "RPM", 1, 0, 0, 15000, 0
        ve1LoadBins = array, S16, 864, [16], "kPa", 0.1, 0, 0, 700, 1
    """.trimIndent()

    private val rusefiIni = """
        [MegaTune]
        signature = "rusEFI master.2026.03.02.proteus_f7.123456"

        [Constants]
        nPages = 3
        pageIdentifier = "\x00\x00", "\x00\x01", "\x00\x02"
        pageSize = 64020, 256, 2048
        ochBlockSize = 2084
        page = 1
        ignitionTable = array, S16, 56296, [16x16], "deg", 0.1, 0, -20, 90, 1
        ignLoadBins = array, U16, 56808, [16], "kPa", 1, 0, 0, 1000, 0
        ignRpmBins = array, U16, 56840, [16], "RPM", 1, 0, 0, 18000, 0
        veTable = array, U16, 56872, [16x16], "%", 0.1, 0, 0, 999, 1
        veLoadBins = array, U16, 57384, [16], "kPa", 1, 0, 0, 1000, 0
        veRpmBins = array, U16, 57416, [16], "RPM", 1, 0, 0, 18000, 0
        lambdaTable = array, U08, 57448, [16x16], "AFR", 0.1, 0, 0, 25, 1
        lambdaLoadBins = array, U16, 57704, [16], "kPa", 1, 0, 0, 1000, 0
        lambdaRpmBins = array, U16, 57736, [16], "RPM", 1, 0, 0, 18000, 0

        [OutputChannels]
        RPMValue = scalar, U16, 4, "RPM", 1, 0
        VBatt = scalar, U16, 40, "V", 0.001, 0
    """.trimIndent()

    // ---- .ini -----------------------------------------------------------------------------------------

    @Test
    fun `speeduino ini replaces layouts and drives multi-map tables`() = runBlocking<Unit> {
        val (c, ecu) = connect(ClientFakeEcu.Kind.SPEEDUINO_2025)
        val definition = IniParser.parse("speeduino.ini", speeduinoIni)
        assertTrue(c.applyIniDefinition(definition))
        assertEquals(130, c.getTableDefinitions()!!.ochBlockSize)
        assertTrue(c.getOutputChannelFields()!!.isNotEmpty())

        // fuel2/spark2 ligados
        ecu.mem(8)[600] = 1
        ecu.mem(8)[601] = 2
        val support = c.readMapSelectionSupport()
        assertEquals(listOf(1, 2), support.veMapIndices)
        assertEquals(listOf(1, 2), support.ignitionMapIndices)

        assertNotNull(c.readVeTable(2))
        assertNotNull(c.readIgnitionTable(2))
        c.writeVeTable(VeTable.createDefault(), 2)
        c.writeIgnitionTable(IgnitionTable.createDefault(), 2)
        c.writeAfrTable(AfrTable.createDefault())
        assertTrue(ecu.writes.any { it.id == 8 })
    }

    @Test
    fun `incomplete or mismatched ini is not applied`() = runBlocking<Unit> {
        val (c, _) = connect(ClientFakeEcu.Kind.SPEEDUINO_2025)
        assertFalse(c.applyIniDefinition(IniParser.parse("empty.ini", "[MegaTune]\nsignature = \"speeduino 202501\"\n")))
        val (ms3, _) = connect(ClientFakeEcu.Kind.MS3)
        assertFalse(ms3.applyIniDefinition(IniParser.parse("x.ini", speeduinoIni)))
        val offline = client(ClientFakeEcu(ClientFakeEcu.Kind.MS3))
        assertFalse(offline.applyIniDefinition(IniParser.parse("x.ini", speeduinoIni)))
        // famílias com ini próprio devolvem false para ini vazio
        for (kind in listOf(ClientFakeEcu.Kind.MS1, ClientFakeEcu.Kind.MEGASPEED, ClientFakeEcu.Kind.RUSEFI)) {
            val (other, _) = connect(kind)
            assertFalse(other.applyIniDefinition(IniParser.parse("empty.ini", "[MegaTune]\nsignature = \"x\"\n")), kind.name)
        }
    }

    @Test
    fun `ms1 reads tables through the ini and rejects unmapped features`() = runBlocking<Unit> {
        val (c, ecu) = connect(ClientFakeEcu.Kind.MS1)
        assertFailsWith<UnsupportedOperationException> { c.readVeTable(1) } // sem ini
        assertFailsWith<UnsupportedOperationException> { c.readIgnitionTable(1) }
        assertTrue(c.applyIniDefinition(IniParser.parse("ms1.ini", ms1Ini)))
        ecu.mem(1).let { it[0] = 10; it[1] = 20; it[2] = 30; it[3] = 40 }
        assertEquals(listOf(listOf(10, 20), listOf(30, 40)), c.readVeTable(1).values)
        assertNotNull(c.readIgnitionTable(1)) // tpsBins -> carga TPS
        assertFailsWith<UnsupportedOperationException> { c.readAfrTable() }
        assertFailsWith<UnsupportedOperationException> { c.readEngineConstants() }
        assertFailsWith<UnsupportedOperationException> { c.writeEngineConstants(io.ecucore.model.EngineConstants.fromPage1(ByteArray(128))) }
    }

    @Test
    fun `megaspeed tables go through the ini layout`() = runBlocking<Unit> {
        val (c, ecu) = connect(ClientFakeEcu.Kind.MEGASPEED)
        assertTrue(c.applyIniDefinition(IniParser.parse("megaspeed.ini", megaSpeedIni)))
        assertEquals(219, c.getTableDefinitions()!!.ochBlockSize)
        assertNotNull(c.readVeTable(1))
        assertNotNull(c.readIgnitionTable(1))
        assertNotNull(c.readAfrTable())
        val ign = IgnitionTable(
            rpmBins = (1..12).map { it * 500 },
            loadBins = (1..12).map { it * 10 },
            values = List(12) { List(12) { 20 } },
        )
        c.writeVeTable(VeTable.createDefault(), 1)
        c.writeIgnitionTable(ign, 1)
        c.writeAfrTable(AfrTable(rpmBins = (1..12).map { it * 500 }, loadBins = (1..12).map { it * 10 }, values = List(12) { List(12) { 147 } }))
        assertTrue(ecu.writes.isNotEmpty())
    }

    @Test
    fun `rusefi tables go through the ini layout`() = runBlocking<Unit> {
        val (c, ecu) = connect(ClientFakeEcu.Kind.RUSEFI)
        assertTrue(c.applyIniDefinition(IniParser.parse("rusefi.ini", rusefiIni)))
        assertNotNull(c.readVeTable(1))
        assertNotNull(c.readIgnitionTable(1))
        assertNotNull(c.readAfrTable())
        val ve = VeTable.createDefault()
        c.writeVeTable(ve, 1)
        assertEquals(ve, c.readVeTable(1))
        val ign = IgnitionTable.createDefault()
        c.writeIgnitionTable(ign, 1)
        assertEquals(ign, c.readIgnitionTable(1))
        c.writeAfrTable(AfrTable.createDefault())
        assertTrue(ecu.writes.isNotEmpty())
    }

    // ---- connect ----------------------------------------------------------------------------------------

    @Test
    fun `too old firmware is rejected with the supported list`() = runBlocking<Unit> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025).apply { signature = "speeduino 201501"; product = "speeduino 201501" }
        val e = assertFailsWith<UnsupportedFirmwareException> { client(ecu).connect() }
        assertTrue(e.message!!.contains("Firmware não suportado"), e.message)
        assertFalse(ecu.connected)
    }

    @Test
    fun `unreadable signature is reported as a channel quality problem`() = runBlocking<Unit> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025, modernFallback = false).apply { signature = "lixo ilegivel"; product = "lixo" }
        assertFailsWith<Exception> { client(ecu).connect() }
    }

    @Test
    fun `modern transports use the modern handshake`() = runBlocking<Unit> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025, prefersLegacy = false, modernProtocol = true)
        val c = client(ecu)
        c.connect()
        assertEquals(EcuFamily.SPEEDUINO, c.getEcuFamily())
        assertTrue(ecu.commands.take(3).all { it == 'Q' || it == 'S' })
    }

    @Test
    fun `modern handshake gives up on unreadable signatures`() = runBlocking<Unit> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025, prefersLegacy = false, modernProtocol = true).apply { signature = "lixo ilegivel"; product = "lixo" }
        assertFailsWith<Exception> { client(ecu).connect() }
    }

    @Test
    fun `legacy handshake falls back to modern when it fails`() = runBlocking<Unit> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025)
        // legacy 'Q'/'S' mudos; só o envelope responde
        ecu.onLegacy = { cmd, _ -> if (cmd == 'Q' || cmd == 'S') ByteArray(0) else null }
        val c = client(ecu)
        c.connect()
        assertEquals(EcuFamily.SPEEDUINO, c.getEcuFamily())
    }

    @Test
    fun `reconnect after a rusefi session waits for the ecu to settle`() = runBlocking<Unit> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.RUSEFI)
        val c = client(ecu)
        c.connect()
        c.disconnect()
        val started = System.currentTimeMillis()
        c.connect()
        assertTrue(System.currentTimeMillis() - started >= 500)
        assertTrue(c.isConnected())
    }
}
