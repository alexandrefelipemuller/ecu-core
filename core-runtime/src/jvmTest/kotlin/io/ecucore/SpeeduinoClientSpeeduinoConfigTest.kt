package io.ecucore

import io.ecucore.model.EcuFamily
import io.ecucore.model.FirmwareEra
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** API pública do [SpeeduinoClient] sobre uma ECU Speeduino 202501 em memória. */
class SpeeduinoClientSpeeduinoConfigTest {

    private fun client(ecu: ClientFakeEcu) = SpeeduinoClient(
        connection = ecu,
        onDataReceived = {},
        onConnectionStateChanged = {},
        onError = {},
    )

    private suspend fun connected(kind: ClientFakeEcu.Kind = ClientFakeEcu.Kind.SPEEDUINO_2025): Pair<SpeeduinoClient, ClientFakeEcu> {
        val ecu = ClientFakeEcu(kind)
        val c = client(ecu)
        c.connect()
        return c to ecu
    }

    @Test
    fun `connect exposes firmware info and definitions`() = runBlocking {
        val (c, ecu) = connected()
        val info = assertNotNull(c.getFirmwareInfoCached())
        assertEquals(EcuFamily.SPEEDUINO, info.family)
        assertEquals(FirmwareEra.MODERN_2025, info.era)
        assertEquals(EcuFamily.SPEEDUINO, c.getEcuFamily())
        assertNotNull(c.getEcuCapabilities())
        assertNotNull(c.getTableDefinitions())
        assertNotNull(c.getEcuDefinition())
        assertNotNull(c.getOutputChannelFields())
        assertTrue(c.getEcuPageCatalog().isNotEmpty())
        assertTrue(c.isConnected())
        assertEquals("tcp:10.0.0.5:5555", c.getConnectionInfo())
        assertNull(c.getConnectionProfileTag())
        assertNull(c.getLegacyConfigBlockSizeOverride())
        assertFalse(c.isStreaming())
        assertFalse(c.isReadOnlySafeMode())
        assertNull(c.getManualFirmwareProfile())
        assertNotNull(c.getPinLayoutInfoCached()) // detectado no connect (page 1)
        c.cachePinLayoutInfo(null)
        assertNull(c.getPinLayoutInfoCached())
        // segundo connect é ignorado
        val before = ecu.commands.size
        c.connect()
        assertEquals(before, ecu.commands.size)
        c.disconnect()
        assertFalse(c.isConnected())
        assertNull(c.getFirmwareInfoCached())
        assertEquals(EcuFamily.UNKNOWN, c.getEcuFamily())
    }

    @Test
    fun `protocol commands are forwarded`() = runBlocking {
        val (c, _) = connected()
        assertTrue(c.getFirmwareInfo().contains("202501"))
        assertTrue(c.getProductString().contains("202501"))
        val cap = c.getSerialCapability()
        assertEquals(2, cap.protocolVersion)
        assertEquals(0x11223344L, c.getPageCRC(2))
        assertEquals(0x11223344L, c.getPageCRC(2.toByte()))
        assertFailsWithIae { c.getPageCRC(0x1FF) }
    }

    private fun assertFailsWithIae(block: suspend () -> Unit) {
        kotlin.test.assertFailsWith<IllegalArgumentException> { runBlocking { block() } }
    }

    @Test
    fun `serial capability by family`() = runBlocking {
        for (kind in listOf(ClientFakeEcu.Kind.MS3, ClientFakeEcu.Kind.MS2, ClientFakeEcu.Kind.MEGASPEED)) {
            val (c, _) = connected(kind)
            assertEquals(256, c.getSerialCapability().blockingFactor, kind.name)
        }
        val (rusefi, _) = connected(ClientFakeEcu.Kind.RUSEFI)
        assertEquals(1024, rusefi.getSerialCapability().blockingFactor)
        val offline = client(ClientFakeEcu(ClientFakeEcu.Kind.MS3))
        assertEquals(256, offline.getSerialCapability().tableBlockingFactor)
    }

    @Test
    fun `engine constants round trip with burn`() = runBlocking {
        val (c, ecu) = connected()
        val constants = c.readEngineConstants()
        c.writeEngineConstants(constants)
        assertTrue(ecu.writes.any { it.id == 1 })
        assertTrue(ecu.burns.isNotEmpty())
    }

    @Test
    fun `tuning tables read and write on speeduino`() = runBlocking {
        val (c, ecu) = connected()
        val ve = c.readVeTable(1)
        val ign = c.readIgnitionTable(1)
        val afr = c.readAfrTable()
        val dwell = c.readDwellTable()
        assertEquals(16, ve.values.size)
        assertEquals(16, ign.values.size)
        assertTrue(afr.values.isNotEmpty())
        assertTrue(dwell.values.isNotEmpty())
        c.writeDwellTable(dwell)
        assertTrue(ecu.writes.any { it.id == 12 })
    }

    @Test
    fun `calibrations idle and secondary serial round trip`() = runBlocking {
        val (c, ecu) = connected()
        // A ECU falsa começa zerada; calibração 0/0 é rejeitada pela validação, então grava valores válidos.
        c.readPressureCalibration()
        c.writePressureCalibration(io.ecucore.model.PressureCalibration(10, 260, 10, 260, 0, 0), burn = true)
        c.readTpsCalibration()
        c.writeTpsCalibration(io.ecucore.model.TpsCalibration(10, 240), burn = false)
        val idle = c.readIdleControlSettings()
        c.writeIdleControlSettings(idle, burn = true)
        val serial = c.readSecondarySerialConfig()
        c.writeSecondarySerialConfig(serial, burn = true)
        c.writeSecondarySerialConfig(serial, burn = false)
        // Missing Tooth com 0 dentes (página zerada) é inválido: usa uma roda 36-1.
        val trigger = c.readTriggerSettings().copy(primaryBaseTeeth = 36, missingTeeth = 1)
        c.writeTriggerSettings(trigger, burn = true)
        c.writeTriggerSettings(trigger, burn = false)
        assertTrue(ecu.burns.size >= 4)
    }

    @Test
    fun `protection and closed loop pages`() = runBlocking {
        val (c, ecu) = connected()
        val protection = c.readEngineProtectionConfig()
        c.writeEngineProtectionConfig(protection, burn = true)
        c.writeEngineProtectionConfig(protection, burn = false)
        val closed = c.readClosedLoopCorrectionConfig()
        c.writeClosedLoopCorrectionConfig(closed, burn = true)
        c.writeClosedLoopCorrectionConfig(closed, burn = false)
        assertTrue(ecu.writes.count { it.id == 6 } >= 4)
    }

    @Test
    fun `full page and chunk reads`() = runBlocking {
        val (c, ecu) = connected()
        ecu.load(7, 0, ByteArray(300) { it.toByte() })
        val page = c.readFullPage(7, 300, 128)
        assertEquals(300, page.size)
        assertEquals(44, page[44].toInt())
        assertEquals(128, c.readConfigChunk(7, 0, 128).size)
        assertEquals(128, c.readConfigChunk(7.toByte(), 0, 128).size)
        assertEquals(128, c.readPage(7.toByte(), 0, 128).size)
        assertEquals(300, c.readFullPage(7.toByte(), 300, 128).size)
    }

    @Test
    fun `raw page writes`() = runBlocking {
        val (c, ecu) = connected()
        val data = ByteArray(64) { it.toByte() }
        c.writeRawPage(5, data)
        c.writeRawPage(5.toByte(), data)
        c.writeRawPageWithoutBurn(5, data)
        c.writeRawPageWithoutBurn(5.toByte(), data)
        c.writeRawPageChunkedWithoutBurn(5, data, chunkSize = 16, startOffset = 0)
        c.writeRawPageChunkedWithoutBurn(5.toByte(), data)
        // páginas 11/14 de 288 bytes são "modern table pages": readback em cache
        val table = ByteArray(288) { 1 }
        c.writeRawPage(11, table)
        assertEquals(1, c.readPage(11, 0, 288)[0].toInt())
        c.writeRawPageWithoutBurn(14, table)
        c.burnConfigs()
        c.burnLastWrittenLegacyPage()
        assertTrue(ecu.burns.size >= 4)
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            c.writeRawPageChunkedWithoutBurn(5, data, chunkSize = 0, startOffset = 0)
        }
        Unit
    }

    @Test
    fun `manual firmware profile makes the client read only`() = runBlocking {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025)
        val c = client(ecu)
        c.setManualFirmwareProfile("speeduino 202501", readOnly = true)
        assertEquals("speeduino 202501", c.getManualFirmwareProfile())
        c.connect()
        assertTrue(c.isReadOnlySafeMode())
        kotlin.test.assertFailsWith<IllegalStateException> { c.writeRawPage(5, ByteArray(4)) }
        kotlin.test.assertFailsWith<IllegalStateException> { c.writeDwellTable(c.readDwellTable()) }
        c.clearManualFirmwareProfile()
        assertFalse(c.isReadOnlySafeMode())
        Unit
    }
}
