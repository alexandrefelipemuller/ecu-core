package io.ecucore

import io.ecucore.cache.EcuConfigPageCache
import io.ecucore.connection.ConnectionTrace
import io.ecucore.definition.IniParser
import io.ecucore.model.AfrTable
import io.ecucore.model.EcuFamily
import io.ecucore.model.IgnitionTable
import io.ecucore.model.UnsupportedFirmwareException
import io.ecucore.model.ValidationException
import io.ecucore.model.VeTable
import io.ecucore.shared.MonotonicClock
import io.ecucore.tables.TableDomainFacade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Caminhos de borda e de falha do [SpeeduinoClient] que os testes por família não alcançam. */
class SpeeduinoClientEdgeCasesTest {

    private val errorFrame = listOf(byteArrayOf(0x00, 0x01), byteArrayOf(0x84.toByte()), ByteArray(4))
    private val d = '$'

    private fun client(
        ecu: ClientFakeEcu,
        cache: EcuConfigPageCache? = null,
        errors: MutableList<String> = mutableListOf(),
        received: MutableList<SpeeduinoLiveData> = mutableListOf(),
    ) = if (cache == null) {
        SpeeduinoClient(ecu, onDataReceived = { synchronized(received) { received += it } }, onConnectionStateChanged = {}, onError = { synchronized(errors) { errors += it } })
    } else {
        SpeeduinoClient(ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {}, pageCache = cache)
    }

    private suspend fun connected(
        kind: ClientFakeEcu.Kind,
        configReads: Boolean? = null,
        wholePage: Boolean = false,
        prefersLegacy: Boolean = true,
        modernProtocol: Boolean = false,
    ): Pair<SpeeduinoClient, ClientFakeEcu> {
        val ecu = ClientFakeEcu(kind, prefersLegacy = prefersLegacy, modernProtocol = modernProtocol, configReads = configReads, wholePageLegacy = wholePage)
        return client(ecu).also { it.connect() } to ecu
    }

    private suspend fun waitFor(timeoutMs: Long = 8000, condition: () -> Boolean) {
        withTimeout(timeoutMs) { while (!condition()) delay(10) }
    }

    // ---- connect: cancelamento, handshake e falhas ---------------------------------------------------

    @Test
    fun `cancelling connect during the legacy handshake propagates the cancellation`() = runBlocking<Unit> {
        val c = client(ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025))
        val job = launch(Dispatchers.Default) { c.connect() }
        delay(40)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test
    fun `cancelling connect during the modern handshake propagates the cancellation`() = runBlocking<Unit> {
        val c = client(ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025, prefersLegacy = false, modernProtocol = true))
        val job = launch(Dispatchers.Default) { c.connect() }
        delay(40)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test
    fun `modern handshake that loses the link makes the next connect legacy-first`() = runBlocking<Unit> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025, prefersLegacy = false, modernProtocol = true)
        ecu.onFrame = { cmd, _ -> if (cmd == 'Q') { ecu.connected = false; errorFrame } else null }
        val c = client(ecu)
        assertFailsWith<UnsupportedFirmwareException> { c.connect() }
        ecu.onFrame = null
        c.connect() // legacy core + product string pulada
        assertEquals(EcuFamily.SPEEDUINO, c.getEcuFamily())
        assertEquals("Unknown", c.getFirmwareInfoCached()?.productString)
    }

    @Test
    fun `product string and pin layout failures do not break connect`() = runBlocking<Unit> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025)
        ecu.failPrefers = true
        val c = client(ecu)
        c.connect()
        assertEquals("Unknown", c.getFirmwareInfoCached()?.productString)
        assertEquals(null, c.getPinLayoutInfoCached())
    }

    @Test
    fun `modern handshake exhausts its attempts when every query throws`() = runBlocking<Unit> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025, prefersLegacy = false, modernProtocol = true)
        ecu.failPrefers = true
        assertFailsWith<Exception> { client(ecu).connect() }
    }

    @Test
    fun `legacy handshake retries survive a failing retry hook`() = runBlocking<Unit> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025)
        ecu.failHandshakeRetry = true
        // 1ª tentativa dá 1 amostra; as seguintes falham no hook e são ignoradas
        client(ecu).connect()
    }

    @Test
    fun `unreadable signature with modern fallback is rejected`() = runBlocking<Unit> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025, modernFallback = true).apply { signature = "lixo ilegivel"; product = "lixo" }
        assertFailsWith<Exception> { client(ecu).connect() }
    }

    @Test
    fun `pin layout detection tolerates a short cached page`() = runBlocking<Unit> {
        val cache = object : EcuConfigPageCache {
            val invalidated = mutableListOf<String>()
            override fun read(identity: String, pageNum: Int, offset: Int, length: Int, nowMs: Long, ttlMs: Long) = ByteArray(4)
            override fun store(identity: String, pageNum: Int, offset: Int, length: Int, data: ByteArray, nowMs: Long) = Unit
            override fun invalidatePage(identity: String, pageNum: Int) { invalidated += "page$pageNum" }
            override fun invalidateIdentity(identity: String) { invalidated += identity }
            override fun lastSyncMs(identity: String): Long? = null
        }
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025)
        val c = client(ecu, cache)
        c.connect()
        assertEquals(null, c.getPinLayoutInfoCached())
        c.invalidateConfigPageCache()
        c.writeRawPage(5, ByteArray(8)) // invalida a página gravada
        assertTrue(cache.invalidated.size >= 2)
    }

    @Test
    fun `reconnect after the settle window does not wait`() = runBlocking<Unit> {
        val (c, _) = connected(ClientFakeEcu.Kind.RUSEFI)
        c.disconnect()
        delay(3100)
        val started = System.currentTimeMillis()
        c.connect()
        assertTrue(System.currentTimeMillis() - started < 2500)
    }

    // ---- passthrough, ini sem canais e leituras de página -----------------------------------------------

    @Test
    fun `legacy passthrough goes to the protocol`() = runBlocking<Unit> {
        val (c, ecu) = connected(ClientFakeEcu.Kind.SPEEDUINO_2025)
        ecu.onLegacy = { cmd, _ -> if (cmd == 'X') byteArrayOf(7, 8) else null }
        assertContentEquals(byteArrayOf(7, 8), c.sendLegacyPassthrough(byteArrayOf('X'.code.toByte()), expectResponse = true, responseSize = 2))
    }

    @Test
    fun `ini without output channels falls back to the built-in definition`() = runBlocking<Unit> {
        val (speeduino, _) = connected(ClientFakeEcu.Kind.SPEEDUINO_2025)
        val noChannels = """
            [MegaTune]
            signature = "speeduino 202501"

            [Constants]
            nPages = 15
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
            afrTable = array, U08, 0, [16x16], "AFR", 0.1, 0.0, 7.0, 25.5, 1
            rpmBinsAFR = array, U08, 256, [16], "RPM", 100.0, 0.0, 100.0, 25500.0, 0
            loadBinsAFR = array, U08, 272, [16], "kPa", 2.0, 0.0, 0.0, 511.0, 0
        """.trimIndent()
        assertTrue(speeduino.applyIniDefinition(IniParser.parse("s.ini", noChannels)))
        val (rusefi, _) = connected(ClientFakeEcu.Kind.RUSEFI)
        val rusefiNoChannels = """
            [MegaTune]
            signature = "rusEFI master.2026.03.02.proteus_f7.123456"

            [Constants]
            nPages = 3
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
        """.trimIndent()
        assertTrue(rusefi.applyIniDefinition(IniParser.parse("r.ini", rusefiNoChannels)))
    }

    @Test
    fun `stale readback is kept when the read window differs and aliases fall through on speeduino`() = runBlocking<Unit> {
        val (c, _) = connected(ClientFakeEcu.Kind.SPEEDUINO_2025)
        c.writeRawPage(11, ByteArray(288) { 1 })
        assertEquals(10, c.readPage(11, 4, 10).size) // janela diferente: readback continua guardado
        assertEquals(288, c.readPage(11, 0, 288).size) // e é consumido na leitura inteira
        assertEquals(288, c.readPage(11, 0, 288).size) // sem readback: alias de página 11 não existe no Speeduino
        assertEquals(288, c.readPage(14, 0, 288).size)
    }

    // ---- readConfigChunk: classificação de falhas recuperáveis ----------------------------------------------

    private suspend fun chunkRead(error: Throwable, secondError: Throwable? = null): Result<ByteArray> {
        val (c, ecu) = connected(ClientFakeEcu.Kind.SPEEDUINO_2025)
        ecu.sendErrors += error
        if (secondError != null) ecu.sendErrors += secondError
        return runCatching { c.readConfigChunk(7, 0, 16) }
    }

    @Test
    fun `recoverable chunk read failures are retried once`() = runBlocking<Unit> {
        for (message in listOf(
            "Timeout: no data received",
            "Timeout: whatever",
            "Timeout: expected 10 bytes, received 4",
            "short legacy page response: expected=4 received=2",
            "short modern page response: expected=4 received=2",
            "short table response: expected=4 received=2",
            "Timeout: expected 99999999999 bytes, received 1",
            "Timeout: expected 5 bytes, received 99999999999",
        )) {
            assertTrue(chunkRead(Exception(message)).isSuccess, message)
        }
    }

    @Test
    fun `non recoverable chunk read failures are wrapped without retry`() = runBlocking<Unit> {
        for (message in listOf("boom", "Timeout: expected 10 bytes, received 10")) {
            val result = chunkRead(Exception(message), secondError = null)
            assertTrue(result.isFailure, message)
            assertTrue(result.exceptionOrNull()!!.message!!.startsWith("Config chunk read failed"), message)
        }
    }

    @Test
    fun `chunk read gives up after the second recoverable failure`() = runBlocking<Unit> {
        val result = chunkRead(Exception("Timeout: no data received"), Exception("Timeout: no data received"))
        assertTrue(result.isFailure)
    }

    @Test
    fun `rusefi chunk reads retry any failure`() = runBlocking<Unit> {
        val (c, ecu) = connected(ClientFakeEcu.Kind.RUSEFI)
        ecu.sendErrors += Exception("boom")
        assertEquals(16, c.readConfigChunk(0, 0, 16).size)
        assertEquals(150, c.readConfigChunk(0, 0, 150).size) // > 64 bytes: lido em blocos
    }

    // ---- readFullPage -----------------------------------------------------------------------------------------

    @Test
    fun `full page read reports the failing chunk`() = runBlocking<Unit> {
        val (c, ecu) = connected(ClientFakeEcu.Kind.SPEEDUINO_2025)
        ecu.sendErrors += Exception("boom")
        val message = assertFailsWith<Exception> { c.readFullPage(7, 300, 128) }.message!!
        assertTrue(message.contains("chunk=1/3"), message)
    }

    @Test
    fun `legacy page mode reads whole pages in one request`() = runBlocking<Unit> {
        val (c, ecu) = connected(ClientFakeEcu.Kind.SPEEDUINO_2020, configReads = false, wholePage = true)
        ecu.load(7, 0, ByteArray(300) { it.toByte() })
        assertEquals(300, c.readFullPage(7, 300, 128).size)
        ecu.sendErrors += Exception("boom")
        val message = assertFailsWith<Exception> { c.readFullPage(7, 300, 128) }.message!!
        assertTrue(message.contains("chunk=1/1"), message)
    }

    // ---- MS1 (legacy page) ---------------------------------------------------------------------------------------

    @Test
    fun `ms1 reads and writes whole legacy pages`() = runBlocking<Unit> {
        val (c, ecu) = connected(ClientFakeEcu.Kind.MS1)
        ecu.load(1, 0, ByteArray(189) { it.toByte() })
        assertEquals(10, c.readConfigChunk(1, 0, 10).size)
        c.writeRawPage(1, ByteArray(8))
        c.writeRawPageWithoutBurn(1, ByteArray(8))
        assertTrue(ecu.commands.contains('W'))
        assertTrue(ecu.commands.contains('B'))
    }

    @Test
    fun `ms1 ini decides the load axis of each table`() = runBlocking<Unit> {
        val (c, _) = connected(ClientFakeEcu.Kind.MS1)
        val ini = """
            [MegaTune]
            signature = "MS/Extra format hr_10 **********"

            [Constants]
            page = 1
            veBins1 = array, U08, 0, [2x2], "%", 1.0, 0.0, 0.0, 255.0, 0
            rpmBins1 = array, U08, 4, [2], "RPM", 100.0, 0.0, 100, 25500, 0
            tpsBins1 = array, U08, 6, [2], "%", 1.0, 0.0, 0.0, 255.0, 0

            page = 3
            advTable1 = array, U08, 0, [2x2], "deg", 0.352, -28.4, -10.0, 80.0, 0
            rpmBins3 = array, U08, 4, [2], "RPM", 100.0, 0.0, 100, 25500, 0
            mapBins3 = array, U08, 6, [2], "kPa", 1.0, 0.0, 0.0, 255.0, 0
        """.trimIndent()
        assertTrue(c.applyIniDefinition(IniParser.parse("ms1.ini", ini)))
        assertEquals(VeTable.LoadType.TPS, c.readVeTable(1).loadType)
        assertEquals(IgnitionTable.LoadType.MAP, c.readIgnitionTable(1).loadType)
    }

    // ---- escrita: calibrações, mapas e verificação -----------------------------------------------------------------

    @Test
    fun `small config writes with burn`() = runBlocking<Unit> {
        val (c, ecu) = connected(ClientFakeEcu.Kind.SPEEDUINO_2025)
        c.readTpsCalibration()
        c.writeTpsCalibration(io.ecucore.model.TpsCalibration(10, 240), burn = true) // 0/0 da ECU falsa seria rejeitado
        c.writeIgnitionTable(IgnitionTable.createDefault(), 1)
        assertTrue(ecu.burns.isNotEmpty())
        val (ms3, _) = connected(ClientFakeEcu.Kind.MS3)
        assertFailsWith<UnsupportedOperationException> { ms3.writeClosedLoopCorrectionConfig(c.readClosedLoopCorrectionConfig(), true) }
    }

    private suspend fun veWriteSetup(): Triple<SpeeduinoClient, ClientFakeEcu, ByteArray> {
        val (c, ecu) = connected(ClientFakeEcu.Kind.SPEEDUINO_2025)
        val metadata = c.getTableDefinitions()!!.veTable
        val data = TableDomainFacade.prepareVeWrite(metadata, VeTable.createDefault()).data
        ecu.load(metadata.page, metadata.offset, data)
        return Triple(c, ecu, data)
    }

    @Test
    fun `write error with a clean readback is accepted and reported`() = runBlocking<Unit> {
        val (c, ecu, _) = veWriteSetup()
        val anomalies = mutableListOf<PageWriteAnomaly>()
        c.pageWriteAnomalyListener = { anomalies += it }
        // a ECU grava os bytes mas devolve BUSY: o read-back confere, então segue para o burn
        ecu.onFrame = { cmd, p ->
            if (cmd == 'M') {
                val page = p[1].toInt() and 0xFF
                val off = (p[2].toInt() and 0xFF) or ((p[3].toInt() and 0xFF) shl 8)
                val len = (p[4].toInt() and 0xFF) or ((p[5].toInt() and 0xFF) shl 8)
                p.copyInto(ecu.mem(page), off, 6, 6 + len)
                listOf(byteArrayOf(0x00, 0x01), byteArrayOf(0x85.toByte()), ByteArray(4))
            } else null
        }
        c.writeVeTable(VeTable.createDefault(), 1)
        assertTrue(anomalies.single().recovered)
        assertTrue(ecu.burns.isNotEmpty())
    }

    @Test
    fun `write error with a diverging readback fails without burning`() = runBlocking<Unit> {
        val (c, ecu, _) = veWriteSetup()
        ecu.onFrame = { cmd, _ -> if (cmd == 'M') listOf(byteArrayOf(0x00, 0x01), byteArrayOf(0x85.toByte()), ByteArray(4)) else null }
        ecu.mem(2).fill(0x55)
        assertFailsWith<PageWriteVerificationException> { c.writeVeTable(VeTable.createDefault(), 1) }
        assertTrue(ecu.burns.isEmpty())
    }

    @Test
    fun `cancellation during a table write is not retried`() = runBlocking<Unit> {
        val (c, ecu, _) = veWriteSetup()
        ecu.sendErrors += null // 1º envio: leitura da imagem anterior (rollback); o erro vem na gravação
        ecu.sendErrors += CancellationException("cancelado")
        assertFailsWith<CancellationException> { c.writeVeTable(VeTable.createDefault(), 1) }
    }

    @Test
    fun `listener failures never break the write`() = runBlocking<Unit> {
        val (c, ecu, _) = veWriteSetup()
        c.pageWriteAnomalyListener = { throw IllegalStateException("telemetria fora do ar") }
        ecu.onFrame = { cmd, _ -> if (cmd == 'M') listOf(byteArrayOf(0x00, 0x01), byteArrayOf(0x82.toByte()), ByteArray(4)) else null }
        ecu.mem(2).fill(0x55)
        assertFailsWith<PageWriteVerificationException> { c.writeVeTable(VeTable.createDefault(), 1) }
    }

    // ---- validação com .ini / F407 ----------------------------------------------------------------------------------

    private val badVe = VeTable.createDefault().let { it.copy(values = List(16) { List(16) { -40000 } }) }
    private val badIgn = IgnitionTable.createDefault().let { it.copy(values = List(16) { List(16) { -40000 } }) }

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
    """.trimIndent()

    @Test
    fun `ini layouts validate tables before writing`() = runBlocking<Unit> {
        val (mega, megaEcu) = connected(ClientFakeEcu.Kind.MEGASPEED)
        assertTrue(mega.applyIniDefinition(IniParser.parse("m.ini", megaSpeedIni)))
        assertFailsWith<ValidationException> { mega.writeVeTable(badVe, 1) }
        assertFailsWith<ValidationException> { mega.writeIgnitionTable(badIgn.copy(rpmBins = List(12) { 500 * (it + 1) }, loadBins = List(12) { 10 * (it + 1) }, values = List(12) { List(12) { -40000 } }), 1) }
        assertTrue(megaEcu.writes.isEmpty())
        val (rusefi, rusefiEcu) = connected(ClientFakeEcu.Kind.RUSEFI)
        assertTrue(rusefi.applyIniDefinition(IniParser.parse("r.ini", rusefiIni)))
        assertFailsWith<ValidationException> { rusefi.writeVeTable(badVe, 1) }
        assertFailsWith<ValidationException> { rusefi.writeIgnitionTable(badIgn, 1) }
        assertTrue(rusefiEcu.writes.isEmpty())
    }

    @Test
    fun `rusefi f407 discovery uses its own layouts`() = runBlocking<Unit> {
        val (c, ecu) = connected(ClientFakeEcu.Kind.RUSEFI_F407)
        assertNotNull(c.readVeTable(1))
        assertNotNull(c.readIgnitionTable(1))
        assertNotNull(c.readAfrTable())
        assertNotNull(c.readTriggerSettings())
        assertNotNull(c.readRusefiInputOutputSnapshot())
        c.writeVeTable(VeTable.createDefault(), 1)
        c.writeIgnitionTable(IgnitionTable.createDefault(), 1)
        c.writeAfrTable(AfrTable.createDefault())
        assertTrue(ecu.writes.isNotEmpty())
        assertFailsWith<ValidationException> { c.writeVeTable(badVe, 1) }
        assertFailsWith<ValidationException> { c.writeIgnitionTable(badIgn, 1) }
    }

    @Test
    fun `rusefi trigger and io snapshot can be parsed through the ini`() = runBlocking<Unit> {
        val (c, _) = connected(ClientFakeEcu.Kind.RUSEFI)
        assertTrue(c.applyIniDefinition(IniParser.parse("r.ini", rusefiIni)))
        assertNotNull(c.readTriggerSettings())
        assertNotNull(c.readRusefiInputOutputSnapshot())
    }

    // ---- .ini: campos de seleção de mapas -----------------------------------------------------------------------------

    @Test
    fun `map selection tolerates scalar bits without range and inverted ranges`() = runBlocking<Unit> {
        val (c, ecu) = connected(ClientFakeEcu.Kind.SPEEDUINO_2025)
        val ini = """
            [MegaTune]
            signature = "speeduino 202501"

            [Constants]
            nPages = 15
            pageSize = 128, 288, 288, 128, 288
            orphanMode = scalar, U08, 5, "x", 1.0, 0.0

            page = 2
            veTable = array, U08, 0, [16x16], "%", 1.0, 0.0, 0.0, 255.0, 0
            rpmBins = array, U08, 256, [16], "RPM", 100.0, 0.0, 100.0, 25500.0, 0
            fuelLoadBins = array, U08, 272, [16], "kPa", 2.0, 0.0, 0.0, 511.0, 0

            page = 3
            advTable1 = array, U08, 0, [16x16], "deg", 1.0, -40.0, -40.0, 70.0, 0
            rpmBins2 = array, U08, 256, [16], "RPM", 100.0, 0.0, 100.0, 25500.0, 0
            mapBins1 = array, U08, 272, [16], "kPa", 2.0, 0.0, 0.0, 511.0, 0

            page = 5
            afrTable = array, U08, 0, [16x16], "AFR", 0.1, 0.0, 7.0, 25.5, 1
            rpmBinsAFR = array, U08, 256, [16], "RPM", 100.0, 0.0, 100.0, 25500.0, 0
            loadBinsAFR = array, U08, 272, [16], "kPa", 2.0, 0.0, 0.0, 511.0, 0

            page = 8
            veTable3 = array, U08, 0, [16x16], "%", 1.0, 0.0, 0.0, 255.0, 0
            advTable3 = array, U08, 288, [16x16], "deg", 1.0, -40.0, -40.0, 70.0, 0
            fuel3Mode = scalar, U16, 604, "x", 1.0, 0.0
            fuel4Mode = bits, U08, 606, "x"
            spark3Mode = bits, U08, 607, [2:0], "Off", "On"
            spark4Mode = bits, U08, 608, [0:1], "Off", "On"

            [OutputChannels]
            ochBlockSize = 130
            rpm = scalar, U16, 14, "rpm", 1.0, 0.0
        """.trimIndent()
        assertTrue(c.applyIniDefinition(IniParser.parse("s.ini", ini)))
        ecu.mem(8)[604] = 1
        ecu.mem(8)[606] = 1
        ecu.mem(8)[607] = 1
        ecu.mem(8)[608] = 1
        val support = c.readMapSelectionSupport()
        assertTrue(3 in support.veMapIndices, support.toString())
        assertTrue(4 !in support.ignitionMapIndices, support.toString())
    }

    @Test
    fun `engine constants failure while resolving the load type falls back to defaults`() = runBlocking<Unit> {
        val (c, ecu) = connected(ClientFakeEcu.Kind.SPEEDUINO_2025)
        ecu.onFrame = { cmd, p -> if (cmd == 'p' && (p[1].toInt() and 0xFF) == 1) errorFrame else null }
        assertNotNull(c.readVeTable(1))
    }

    // ---- burn de tabelas MS3 ------------------------------------------------------------------------------------------

    @Test
    fun `ms3 table burn errors that mean not burnable are ignored`() = runBlocking<Unit> {
        for (message in listOf("RANGE_ERR", "Valor fora do range", "Incomplete modern response", "expected 1 bytes, received 0 bytes")) {
            val (c, ecu) = connected(ClientFakeEcu.Kind.MS3)
            ecu.onFrame = { cmd, _ -> if (cmd == 'b') throw Exception(message) else null }
            c.writeRawPage(0x05, ByteArray(16))
        }
        val (c, ecu) = connected(ClientFakeEcu.Kind.MS3)
        ecu.onFrame = { cmd, _ -> if (cmd == 'b') listOf(byteArrayOf(0x00, 0x00), ByteArray(0), ByteArray(4)) else null }
        c.writeRawPage(0x05, ByteArray(16)) // "sem resposta"
    }

    // ---- live data: re-alinhamento, stream e pause --------------------------------------------------------------------

    @Test
    fun `misaligned output channels are shifted by one byte when that scores better`() = runBlocking<Unit> {
        val (c, ecu) = connected(ClientFakeEcu.Kind.SPEEDUINO_2025)
        // MAP = 256 (múltiplo de 256) e data[0] == 0: sinal clássico de desalinhamento de 1 byte
        ecu.liveBytes = ByteArray(130).also { it[4] = 0; it[5] = 1 }
        assertNotNull(c.readLiveData())
        // desalinhado e continua ruim depois do shift: mantém o parse original
        ecu.liveBytes = ByteArray(130) { if (it == 0) 0 else 0xFF.toByte() }
        assertNotNull(c.readLiveData())
    }

    @Test
    fun `live data on a disconnected client fails cleanly`() = runBlocking<Unit> {
        val c = client(ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025))
        assertFailsWith<Exception> { c.readLiveData() }
    }

    @Test
    fun `stream logs progress every fifty packets and reports a lost link`() = runBlocking<Unit> {
        val received = mutableListOf<SpeeduinoLiveData>()
        val errors = mutableListOf<String>()
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025)
        val c = client(ecu, errors = errors, received = received)
        c.connect()
        c.startLiveDataStream(intervalMs = 1)
        waitFor { synchronized(received) { received.size >= 55 } }
        ecu.onFrame = { cmd, _ -> if (cmd == 'r') { ecu.connected = false; errorFrame } else null }
        waitFor { synchronized(errors) { errors.isNotEmpty() } }
        waitFor { !c.isStreaming() }
    }

    @Test
    fun `stream forces a disconnect on transport level failures`() = runBlocking<Unit> {
        val errors = mutableListOf<String>()
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025)
        val c = client(ecu, errors = errors)
        c.connect()
        ecu.onFrame = { cmd, _ -> if (cmd == 'r') throw Exception("Connection reset by peer") else null }
        c.startLiveDataStream(intervalMs = 5)
        waitFor { synchronized(errors) { errors.isNotEmpty() } }
        waitFor { !c.isStreaming() }
        waitFor { c.getFirmwareInfoCached() == null } // disconnect() limpou a sessão
    }

    @Test
    fun `pausing a stuck stream times out and cancels the job`() = runBlocking<Unit> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025)
        val c = client(ecu)
        c.connect()
        ecu.onFrame = { cmd, _ -> if (cmd == 'r') { Thread.sleep(600); null } else null }
        c.startLiveDataStream(intervalMs = 5)
        delay(150)
        c.pauseLiveDataStream(timeoutMs = 100)
        assertFalse(c.isStreaming())
    }

    /**
     * Amostras inválidas só são tratadas depois da janela de aquecimento de 10 s do stream e o
     * relatório periódico só sai com o relógio monotônico além de 30 s: este teste espera esses
     * tempos reais (cerca de 22 s) para cobrir o diagnóstico e a recuperação do stream. A segunda
     * fase deixa o stream "travado" no callback de dados, para que leituras diretas encontrem uma
     * recuperação já agendada e pendente.
     */
    @Test
    fun `faulty samples after warmup restart the stream and are traced`() = runBlocking<Unit> {
        ConnectionTrace.enabled = true
        val blockCallback = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            while (MonotonicClock.nowMillis() < 31_000) delay(250)
            val ecu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025)
            ecu.liveBytes = ByteArray(130) { 0xFF.toByte() }
            val c = SpeeduinoClient(
                ecu,
                onDataReceived = { while (blockCallback.get()) Thread.sleep(20) },
                onConnectionStateChanged = {},
                onError = {},
            )
            c.connect()
            c.startLiveDataStream(intervalMs = 5)
            delay(10_800) // fim do aquecimento: 3 amostras ruins seguidas agendam a recuperação e reiniciam o stream
            waitFor(10_000) { c.isStreaming() }
            blockCallback.set(true) // stream preso no callback, ainda "ativo"
            delay(10_500) // nova janela de aquecimento
            repeat(60) { c.readLiveData() } // relatório de diagnóstico (25ª e 50ª) e recuperação já pendente
            blockCallback.set(false)
            c.stopLiveDataStream()
            repeat(5) { c.readLiveData() } // sem stream ativo a recuperação nem é agendada
        } finally {
            blockCallback.set(false)
            ConnectionTrace.enabled = false
        }
    }
}
