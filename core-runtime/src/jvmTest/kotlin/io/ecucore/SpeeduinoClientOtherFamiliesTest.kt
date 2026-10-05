package io.ecucore

import io.ecucore.model.AfrTable
import io.ecucore.model.EcuFamily
import io.ecucore.model.IgnitionTable
import io.ecucore.model.ValidationException
import io.ecucore.model.VeTable
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Tabelas/config das famílias MS2, MegaSpeed, MS3 e rusEFI (sem .ini), mais guardas por família. */
class SpeeduinoClientOtherFamiliesTest {

    private fun client(ecu: ClientFakeEcu) = SpeeduinoClient(ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})

    private suspend fun connect(kind: ClientFakeEcu.Kind): Pair<SpeeduinoClient, ClientFakeEcu> {
        val ecu = ClientFakeEcu(kind)
        return client(ecu).also { it.connect() } to ecu
    }

    private val badVe = VeTable.createDefault().let { it.copy(values = List(16) { List(16) { -40000 } }) }
    private val badIgn = IgnitionTable.createDefault().let { it.copy(values = List(16) { List(16) { -40000 } }) }

    private fun ignition(n: Int) = IgnitionTable(
        rpmBins = (1..n).map { it * 500 },
        loadBins = (1..n).map { it * 10 },
        values = List(n) { List(n) { 20 } },
    )

    /** AFR 12x12 das tabelas MS (a tabela padrão é 16x16, formato Speeduino). */
    private val msAfr = AfrTable(
        rpmBins = (1..12).map { it * 500 },
        loadBins = (1..12).map { it * 10 },
        values = List(12) { List(12) { 147 } },
    )

    // ---- MS3 / MS2 / MegaSpeed: tabelas -----------------------------------------------------------

    private fun tableFamilies() = listOf(
        ClientFakeEcu.Kind.MS3 to EcuFamily.MS3,
        ClientFakeEcu.Kind.MS2 to EcuFamily.MS2,
        ClientFakeEcu.Kind.MEGASPEED to EcuFamily.MEGASPEED,
    )

    @Test
    fun `ms families read and write the three tuning tables`() = runBlocking {
        for ((kind, family) in tableFamilies()) {
            val (c, ecu) = connect(kind)
            assertEquals(family, c.getEcuFamily(), kind.name)
            assertNotNull(c.readVeTable(1))
            assertNotNull(c.readIgnitionTable(1))
            assertNotNull(c.readAfrTable())
            c.writeVeTable(VeTable.createDefault(), 1)
            c.writeIgnitionTable(ignition(if (kind == ClientFakeEcu.Kind.MS3) 16 else 12), 1)
            c.writeAfrTable(msAfr)
            assertTrue(ecu.writes.size >= 9, "${kind.name} writes=${ecu.writes.size}")
            assertTrue(ecu.burns.isNotEmpty(), kind.name)
        }
    }

    @Test
    fun `ms families reject invalid tables before touching the ecu`() = runBlocking {
        for ((kind, _) in tableFamilies()) {
            val (c, ecu) = connect(kind)
            assertFailsWith<ValidationException> { c.writeVeTable(badVe, 1) }
            assertFailsWith<ValidationException> { c.writeIgnitionTable(badIgn, 1) }
            assertTrue(ecu.writes.isEmpty(), kind.name)
        }
    }

    @Test
    fun `ms engine constants and trigger settings`() = runBlocking {
        for ((kind, _) in tableFamilies()) {
            val (c, ecu) = connect(kind)
            val constants = c.readEngineConstants()
            c.writeEngineConstants(constants)
            assertTrue(ecu.writes.any { it.id == 0x04 }, kind.name)
            if (kind != ClientFakeEcu.Kind.MS3) {
                val trigger = c.readTriggerSettings()
                c.writeTriggerSettings(trigger, burn = true)
                c.writeTriggerSettings(trigger, burn = false)
            }
        }
    }

    @Test
    fun `ms pages are aliased and written through tables`() = runBlocking {
        for ((kind, _) in tableFamilies()) {
            val (c, ecu) = connect(kind)
            assertEquals(288, c.readPage(11, 0, 288).size)
            assertEquals(288, c.readPage(14, 0, 288).size)
            c.writeRawPage(11, ByteArray(288))
            c.writeRawPageWithoutBurn(14, ByteArray(288))
            c.writeRawPageChunkedWithoutBurn(6, ByteArray(16), 8, 0)
            assertTrue(ecu.writes.isNotEmpty(), kind.name)
            assertFailsWith<IllegalStateException> { c.burnConfigs() }
            assertFailsWith<IllegalStateException> { c.burnLastWrittenLegacyPage() }
        }
    }

    @Test
    fun `ms3 burn that the ecu does not support is ignored`() = runBlocking {
        val (c, ecu) = connect(ClientFakeEcu.Kind.MS3)
        ecu.onFrame = { cmd, _ ->
            if (cmd == 'b') listOf(byteArrayOf(0x00, 0x01), byteArrayOf(0x84.toByte()), ByteArray(4)) else null
        }
        c.writeRawPage(0x05, ByteArray(16)) // burn 0x84 em MS3 = ignorado
        val (ms2, ms2Ecu) = connect(ClientFakeEcu.Kind.MS2)
        ms2Ecu.onFrame = { cmd, _ ->
            if (cmd == 'b') listOf(byteArrayOf(0x00, 0x01), byteArrayOf(0x84.toByte()), ByteArray(4)) else null
        }
        // fora do MS3 o mesmo erro é propagado
        assertFailsWith<Exception> { ms2.writeRawPage(0x05, ByteArray(16)) }
        Unit
    }

    @Test
    fun `ms family guards for speeduino only features`() = runBlocking {
        val (c, _) = connect(ClientFakeEcu.Kind.MS3)
        assertFailsWith<UnsupportedOperationException> { c.readDwellTable() }
        assertFailsWith<UnsupportedOperationException> { c.writeDwellTable(io.ecucore.model.DwellTable.createDefault()) }
        assertFailsWith<UnsupportedOperationException> { c.readIdleControlSettings() }
        assertFailsWith<UnsupportedOperationException> { c.writeIdleControlSettings(io.ecucore.model.IdleControlSettings(), true) }
        assertFailsWith<UnsupportedOperationException> { c.readClosedLoopCorrectionConfig() }
        assertFailsWith<UnsupportedOperationException> { c.readRusefiInputOutputSnapshot() }
        assertEquals(io.ecucore.transport.MapSelectionSupport(), c.readMapSelectionSupport())
        Unit
    }

    @Test
    fun `ms3 generic sensors require a connected ms3`() = runBlocking {
        val offline = client(ClientFakeEcu(ClientFakeEcu.Kind.MS3))
        assertFailsWith<IllegalStateException> { offline.readMs3GenericSensors() }
        val (speeduino, _) = connect(ClientFakeEcu.Kind.SPEEDUINO_2025)
        assertFailsWith<UnsupportedOperationException> { speeduino.readMs3GenericSensors() }
        Unit
    }

    // ---- rusEFI -------------------------------------------------------------------------------------

    @Test
    fun `rusefi tables engine constants trigger and io`() = runBlocking {
        val (c, ecu) = connect(ClientFakeEcu.Kind.RUSEFI)
        assertEquals(EcuFamily.RUSEFI, c.getEcuFamily())
        assertNotNull(c.readEngineConstants())
        assertNotNull(c.readTriggerSettings())
        assertNotNull(c.readRusefiInputOutputSnapshot())
        assertNotNull(c.readVeTable(1))
        assertNotNull(c.readIgnitionTable(1))
        assertNotNull(c.readAfrTable())

        val ve = VeTable.createDefault()
        c.writeVeTable(ve, 1)
        assertEquals(ve, c.readVeTable(1)) // devolvido do cache pós-write
        val ign = IgnitionTable.createDefault()
        c.writeIgnitionTable(ign, 1)
        assertEquals(ign, c.readIgnitionTable(1))
        c.writeAfrTable(AfrTable.createDefault())
        assertTrue(ecu.writes.isNotEmpty())
        assertFailsWith<ValidationException> { c.writeVeTable(badVe, 1) }
        assertFailsWith<ValidationException> { c.writeIgnitionTable(badIgn, 1) }
        Unit
    }

    @Test
    fun `rusefi rejects speeduino-only config`() = runBlocking {
        val (c, _) = connect(ClientFakeEcu.Kind.RUSEFI)
        assertFailsWith<UnsupportedOperationException> { c.readEngineProtectionConfig() }
        val protection = connect(ClientFakeEcu.Kind.SPEEDUINO_2025).first.readEngineProtectionConfig()
        assertFailsWith<UnsupportedOperationException> { c.writeEngineProtectionConfig(protection, true) }
        assertFailsWith<UnsupportedOperationException> { c.writeTriggerSettings(c.readTriggerSettings(), true) }
        assertFailsWith<UnsupportedOperationException> { c.writeEngineConstants(c.readEngineConstants()) }
        assertFailsWith<IllegalStateException> { c.burnConfigs() }
        // páginas rusEFI são tabelas
        c.writeRawPage(0x0100, ByteArray(8))
        c.writeRawPageWithoutBurn(0x0100, ByteArray(8))
        Unit
    }

    @Test
    fun `speeduino guards`() = runBlocking {
        val (c, _) = connect(ClientFakeEcu.Kind.SPEEDUINO_2025)
        assertFailsWith<UnsupportedOperationException> { c.readRusefiInputOutputSnapshot() }
        assertEquals(io.ecucore.transport.MapSelectionSupport(), c.readMapSelectionSupport())
        val offline = client(ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025))
        assertFailsWith<IllegalStateException> { offline.readVeTable(1) }
        assertFailsWith<IllegalStateException> { offline.readIgnitionTable(1) }
        assertFailsWith<IllegalStateException> { offline.readAfrTable() }
        assertFailsWith<IllegalStateException> { offline.writeVeTable(VeTable.createDefault(), 1) }
        assertFailsWith<IllegalStateException> { offline.writeIgnitionTable(IgnitionTable.createDefault(), 1) }
        assertFailsWith<IllegalStateException> { offline.writeAfrTable(AfrTable.createDefault()) }
        Unit
    }
}
