package io.ecucore

import io.ecucore.connection.IEcuConnection
import io.ecucore.model.DwellTable
import io.ecucore.model.VeTable
import io.ecucore.tables.TableDomainFacade
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Regressão de campo 2026-09 (Speeduino via Bluetooth): "Erro ao gravar página 2: CRC_ERR (0x82)"
 * e logo depois células isoladas com valores absurdos (212 no meio de 40s) na VE.
 *
 * O firmware descarta o frame com CRC errado, mas o resto dele continua chegando depois do
 * flushRXbuffer(); com legacy liberado, bytes 'A'..'z' viram comandos 'W' e escrevem bytes
 * arbitrários na RAM. O fake reproduz isso: o 'M' rejeitado corrompe um byte da página.
 */
class EcuClientVerifiedTableWriteTest {

    @Test
    fun `0x82 com lixo na RAM e corrigido pelo read-back antes do burn`() = runBlocking {
        // 3º chunk rejeitado; o lixo cai no 1º chunk, que já tinha sido gravado e não é regravado
        // pelo retry - só o read-back enxerga.
        val ecu = FakeEcuMemory(rejectWriteNumber = 3, corruptOffsetOnReject = 37)
        val client = newClient(ecu)
        val anomalies = mutableListOf<PageWriteAnomaly>()
        client.pageWriteAnomalyListener = { anomalies += it }
        client.connect()
        val metadata = client.getTableDefinitions()!!.veTable
        ecu.load(metadata.page, metadata.offset, TableDomainFacade.prepareVeWrite(metadata, veTable(40)).data)

        val edited = veTable(40).let { it.copy(values = it.values.mapIndexed { r, row -> if (r == 2) row.map { 45 } else row }) }
        client.writeVeTable(edited, 1)

        val expected = TableDomainFacade.prepareVeWrite(metadata, edited).data
        assertContentEquals(expected, ecu.read(metadata.page, metadata.offset, expected.size))
        assertEquals(1, ecu.burns, "burn só depois da RAM conferida")

        // A corrupção foi corrigida, mas NÃO pode passar em silêncio: tem que chegar na telemetria.
        val anomaly = anomalies.single()
        assertEquals(true, anomaly.recovered)
        assertEquals(metadata.page, anomaly.pageId)
        assertEquals(0x82, anomaly.writeResponseCode)
        assertEquals(listOf(37 - metadata.offset), anomaly.mismatchedOffsets)
        assertEquals(listOf(212), anomaly.actual)
    }

    @Test
    fun `burn Speeduino queima a pagina escrita com b e nunca manda B sem payload`() = runBlocking {
        // Regressão: 'B' sem payload responde RANGE_ERR (0x84) no firmware; o app precisa queimar
        // cada página gravada com 'b' + número da página.
        val ecu = FakeEcuMemory(rejectWriteNumber = null, corruptOffsetOnReject = 37)
        val client = newClient(ecu)
        client.connect()
        val metadata = client.getTableDefinitions()!!.veTable

        client.writeVeTable(veTable(40), 1)

        assertEquals(0, ecu.bareBurnRejections, "nenhum 'B' sem payload deve ser enviado")
        assertEquals(listOf(metadata.page), ecu.burnedPages, "a página VE deve ser queimada com 'b'")
        assertEquals(1, ecu.burns)
    }

    @Test
    fun `0x82 mascarado pelo retry do chunk ainda chega na telemetria`() = runBlocking {
        // Lixo cai no próprio chunk rejeitado; o retry sobrescreve e o read-back sai limpo.
        val ecu = FakeEcuMemory(rejectWriteNumber = 1, corruptOffsetOnReject = 37)
        val client = newClient(ecu)
        val anomalies = mutableListOf<PageWriteAnomaly>()
        client.pageWriteAnomalyListener = { anomalies += it }
        client.connect()

        client.writeVeTable(veTable(40), 1)

        val anomaly = anomalies.single()
        assertTrue(anomaly.recovered)
        assertEquals(emptyList(), anomaly.mismatchedOffsets)
        assertEquals(0x82, anomaly.writeResponseCode)
        assertEquals(1, ecu.burns)
    }

    @Test
    fun `gravacao limpa nao emite anomalia`() = runBlocking {
        val ecu = FakeEcuMemory(rejectWriteNumber = null, corruptOffsetOnReject = 37)
        val client = newClient(ecu)
        val anomalies = mutableListOf<PageWriteAnomaly>()
        client.pageWriteAnomalyListener = { anomalies += it }
        client.connect()
        val metadata = client.getTableDefinitions()!!.veTable

        client.writeVeTable(veTable(40), 1)

        assertEquals(emptyList(), anomalies)
        assertEquals(1, ecu.burns)
    }

    @Test
    fun `divergencia persistente lanca excecao e nao faz burn`() = runBlocking {
        val ecu = FakeEcuMemory(rejectWriteNumber = null, corruptOffsetOnReject = 37, corruptEveryWrite = true)
        val client = newClient(ecu)
        client.connect()
        val metadata = client.getTableDefinitions()!!.veTable
        ecu.load(metadata.page, metadata.offset, TableDomainFacade.prepareVeWrite(metadata, veTable(40)).data)

        val anomalies = mutableListOf<PageWriteAnomaly>()
        client.pageWriteAnomalyListener = { anomalies += it }

        val error = assertFailsWith<PageWriteVerificationException> { client.writeVeTable(veTable(50), 1) }
        assertEquals(metadata.page, error.pageId)
        assertEquals(2, anomalies.size, "um evento por passe")
        assertTrue(anomalies.none { it.recovered })
        assertEquals(0, ecu.burns, "RAM divergente nunca pode ir pra EEPROM")
    }

    @Test
    fun `divergencia persistente restaura a RAM ao valor anterior e nao faz burn`() = runBlocking {
        // 5 chunks x 2 passes = 10 gravações corrompidas; o rollback (11ª em diante) sai limpo
        val ecu = FakeEcuMemory(rejectWriteNumber = null, corruptOffsetOnReject = 37, corruptFirstWrites = 10)
        val client = newClient(ecu)
        client.connect()
        val metadata = client.getTableDefinitions()!!.veTable
        val original = TableDomainFacade.prepareVeWrite(metadata, veTable(40)).data
        ecu.load(metadata.page, metadata.offset, original)

        val error = assertFailsWith<PageWriteVerificationException> { client.writeVeTable(veTable(50), 1) }

        assertTrue(error.rolledBack, "a RAM deveria ter voltado ao valor anterior")
        assertTrue(error.message!!.contains("previous value restored"))
        assertContentEquals(original, ecu.read(metadata.page, metadata.offset, original.size))
        assertEquals(0, ecu.burns)
    }

    @Test
    fun `rollback que tambem falha e informado e a RAM pode estar divergente`() = runBlocking {
        val ecu = FakeEcuMemory(rejectWriteNumber = null, corruptOffsetOnReject = 37, corruptEveryWrite = true)
        val client = newClient(ecu)
        client.connect()
        val metadata = client.getTableDefinitions()!!.veTable
        ecu.load(metadata.page, metadata.offset, TableDomainFacade.prepareVeWrite(metadata, veTable(40)).data)

        val error = assertFailsWith<PageWriteVerificationException> { client.writeVeTable(veTable(50), 1) }

        assertEquals(false, error.rolledBack)
        assertTrue(error.message!!.contains("RAM may differ"))
        assertEquals(0, ecu.burns)
    }

    @Test
    fun `dwell e conferido por read-back e so entao faz burn`() = runBlocking {
        val ecu = FakeEcuMemory(rejectWriteNumber = null, corruptOffsetOnReject = 37)
        val client = newClient(ecu)
        client.connect()
        val table = DwellTable.createDefault()

        client.writeDwellTable(table)

        assertContentEquals(table.toByteArray(), ecu.read(12, 0, table.toByteArray().size))
        assertEquals(1, ecu.burns)
    }

    @Test
    fun `dwell com divergencia persistente lanca excecao e nao faz burn`() = runBlocking {
        val ecu = FakeEcuMemory(rejectWriteNumber = null, corruptOffsetOnReject = 37, corruptEveryWrite = true)
        val client = newClient(ecu)
        client.connect()

        val error = assertFailsWith<PageWriteVerificationException> { client.writeDwellTable(DwellTable.createDefault()) }

        assertEquals(12, error.pageId)
        assertEquals(0, ecu.burns, "RAM divergente nunca pode ir pra EEPROM")
    }

    @Test
    fun `calibracao de TPS com divergencia persistente nao faz burn`() = runBlocking {
        val ecu = FakeEcuMemory(rejectWriteNumber = null, corruptOffsetOnReject = 37, corruptEveryWrite = true)
        val client = newClient(ecu)
        client.connect()

        assertFailsWith<PageWriteVerificationException> {
            client.writeTpsCalibration(io.ecucore.model.TpsCalibration(tpsMin = 10, tpsMax = 240), burn = true)
        }

        assertEquals(0, ecu.burns)
    }

    @Test
    fun `mudanca grande rejeitada pelo guard nao grava nem faz burn`() = runBlocking {
        val ecu = FakeEcuMemory(rejectWriteNumber = null, corruptOffsetOnReject = 37)
        val client = newClient(ecu)
        client.connect()
        val metadata = client.getTableDefinitions()!!.veTable
        val original = TableDomainFacade.prepareVeWrite(metadata, veTable(40)).data
        ecu.load(metadata.page, metadata.offset, original)
        var assessed: io.ecucore.model.TableChangeAssessment? = null
        client.tableChangeGuard = { assessed = it; false }

        val error = assertFailsWith<TableChangeRejectedException> { client.writeVeTable(veTable(90), 1) }

        assertTrue(error.assessment.bulkReplacement)
        assertEquals(error.assessment, assessed)
        assertContentEquals(original, ecu.read(metadata.page, metadata.offset, original.size))
        assertEquals(0, ecu.burns)
    }

    @Test
    fun `mudanca grande confirmada pelo guard grava normalmente`() = runBlocking {
        val ecu = FakeEcuMemory(rejectWriteNumber = null, corruptOffsetOnReject = 37)
        val client = newClient(ecu)
        client.connect()
        val metadata = client.getTableDefinitions()!!.veTable
        ecu.load(metadata.page, metadata.offset, TableDomainFacade.prepareVeWrite(metadata, veTable(40)).data)
        client.tableChangeGuard = { true }

        client.writeVeTable(veTable(90), 1)

        assertContentEquals(
            TableDomainFacade.prepareVeWrite(metadata, veTable(90)).data,
            ecu.read(metadata.page, metadata.offset, 288),
        )
        assertEquals(1, ecu.burns)
    }

    @Test
    fun `mudanca pequena nao consulta o guard`() = runBlocking {
        val ecu = FakeEcuMemory(rejectWriteNumber = null, corruptOffsetOnReject = 37)
        val client = newClient(ecu)
        client.connect()
        val metadata = client.getTableDefinitions()!!.veTable
        ecu.load(metadata.page, metadata.offset, TableDomainFacade.prepareVeWrite(metadata, veTable(40)).data)
        var asked = false
        client.tableChangeGuard = { asked = true; false }

        client.writeVeTable(veTable(44), 1)

        assertEquals(false, asked)
        assertEquals(1, ecu.burns)
    }


    // ---- P0: restauração/backup de páginas e integridade ao redor do burn -------------------------

    private fun restoreImage(fill: Int) = ByteArray(128) { fill.toByte() }

    @Test
    fun `restauracao limpa grava confere por read-back e so entao queima`() = runBlocking {
        val ecu = FakeEcuMemory(rejectWriteNumber = null, corruptOffsetOnReject = 37)
        val client = newClient(ecu)
        val events = mutableListOf<WriteIntegrityEvent>()
        client.writeIntegrityListener = { events += it }
        client.connect()
        val image = restoreImage(7)

        client.writeRawPage(4, image)

        assertContentEquals(image, ecu.read(4, 0, 128))
        assertEquals(1, ecu.burns)
        assertEquals(emptyList(), events)
    }

    @Test
    fun `restauracao com RAM corrompida pelo link lanca excecao e nao queima a EEPROM`() = runBlocking {
        // Antes desta correção writeRawPage gravava sem read-back e queimava o lixo na EEPROM.
        val ecu = FakeEcuMemory(rejectWriteNumber = null, corruptOffsetOnReject = 37, corruptEveryWrite = true)
        val client = newClient(ecu)
        client.connect()

        val error = assertFailsWith<PageWriteVerificationException> { client.writeRawPage(4, restoreImage(7)) }

        assertEquals(4, error.pageId)
        assertEquals(0, ecu.burns, "RAM divergente nunca pode ir pra EEPROM")
    }

    @Test
    fun `restauracao em lote sem burn tambem confere por read-back`() = runBlocking {
        val ecu = FakeEcuMemory(rejectWriteNumber = null, corruptOffsetOnReject = 37, corruptEveryWrite = true)
        val client = newClient(ecu)
        client.connect()

        assertFailsWith<PageWriteVerificationException> { client.writeRawPageWithoutBurn(4, restoreImage(7)) }
        assertFailsWith<PageWriteVerificationException> { client.writeRawPageChunkedWithoutBurn(4, restoreImage(7), 32, 0) }
        assertEquals(0, ecu.burns)
    }

    @Test
    fun `restauracao da AFR de 288 bytes cabe no buffer do AVR e confere`() = runBlocking {
        // Campo 2026-11 (v1.7.0, Bluetooth, 202501): a página 5 ia num único 'M' de ~300 bytes,
        // o AVR ignorava e o read-back dava 288/288 bytes divergentes nas 3 tentativas.
        val ecu = FakeEcuMemory(rejectWriteNumber = null, corruptOffsetOnReject = 0, maxFrameBytes = 264)
        ecu.load(5, 0, ByteArray(288) { 100 })
        val client = newClient(ecu)
        client.connect()
        val image = ByteArray(288) { 147.toByte() }

        client.writeRawPageWithoutBurn(5, image)

        assertContentEquals(image, ecu.read(5, 0, 288))
        assertTrue(ecu.maxWriteFrame <= 264, "frame de ${ecu.maxWriteFrame} bytes")
    }

    @Test
    fun `passe ignorado inteiro pela ECU repete com blocos menores`() = runBlocking {
        // ECU que só aceita frames de até 32 bytes de dados (32 + 6 + envelope 7 = 45).
        val ecu = FakeEcuMemory(rejectWriteNumber = null, corruptOffsetOnReject = 0, maxFrameBytes = 45)
        val client = newClient(ecu)
        client.connect()
        val image = ByteArray(128) { (it + 1).toByte() }

        client.writeRawPageWithoutBurn(4, image)

        assertContentEquals(image, ecu.read(4, 0, 128))
    }

    @Test
    fun `restauracao em chunks grava e confere`() = runBlocking {
        val ecu = FakeEcuMemory(rejectWriteNumber = null, corruptOffsetOnReject = 37)
        val client = newClient(ecu)
        client.connect()
        val image = ByteArray(200) { (it * 3).toByte() }

        client.writeRawPageChunkedWithoutBurn(4, image, 32, 0)

        assertContentEquals(image, ecu.read(4, 0, 200))
    }

    @Test
    fun `RAM que diverge depois do burn emite POST_BURN_MISMATCH critico mas nao lanca`() = runBlocking {
        val ecu = FakeEcuMemory(rejectWriteNumber = null, corruptOffsetOnReject = 37, corruptOnBurn = true)
        val client = newClient(ecu)
        val events = mutableListOf<WriteIntegrityEvent>()
        client.writeIntegrityListener = { events += it }
        client.connect()

        client.writeRawPage(4, restoreImage(7)) // burn aconteceu: não pode lançar

        assertEquals(1, ecu.burns)
        val event = events.single()
        assertEquals(WriteIntegrityKind.POST_BURN_MISMATCH, event.kind)
        assertTrue(event.kind.critical)
        assertEquals(4, event.pageId)
        assertTrue(event.expectedCrc != event.actualCrc)
    }

    @Test
    fun `pagina ilegivel depois do burn emite POST_BURN_UNREADABLE nao critico`() = runBlocking {
        val ecu = FakeEcuMemory(rejectWriteNumber = null, corruptOffsetOnReject = 37, failReadsAfterBurn = true)
        val client = newClient(ecu)
        val events = mutableListOf<WriteIntegrityEvent>()
        client.writeIntegrityListener = { events += it }
        client.connect()

        client.writeRawPage(4, restoreImage(7))

        val event = events.single()
        assertEquals(WriteIntegrityKind.POST_BURN_UNREADABLE, event.kind)
        assertEquals(false, event.kind.critical)
    }

    @Test
    fun `burn limpo nao emite evento e registra as regioes queimadas`() = runBlocking {
        val ecu = FakeEcuMemory(rejectWriteNumber = null, corruptOffsetOnReject = 37)
        val client = newClient(ecu)
        val events = mutableListOf<WriteIntegrityEvent>()
        client.writeIntegrityListener = { events += it }
        client.connect()
        val image = restoreImage(9)

        client.writeRawPage(4, image)

        assertEquals(emptyList(), events)
        val burned = client.getLastBurnedRegions().single()
        assertEquals(4, burned.pageId)
        assertEquals(128, burned.length)
        assertEquals(io.ecucore.shared.Crc32Table.compute(image), burned.crc)
        assertTrue(burned.verified)
    }

    @Test
    fun `ledger so queima o confirmado e guarda o ultimo burn`() {
        val ledger = WriteLedger()
        ledger.recordVerified(2, 0, byteArrayOf(1, 2, 3), "a")
        ledger.recordUnverified(3, 0, byteArrayOf(4), "b")

        assertEquals(listOf(false), ledger.pending().filter { !it.verified }.map { it.verified })
        val burned = ledger.markBurned()

        assertEquals(2, burned.size)
        assertEquals(emptyList(), ledger.pending())
        assertEquals(burned, ledger.lastBurned())
        ledger.clear()
        assertEquals(emptyList(), ledger.lastBurned())
    }


    // ---- conferência do último burn (persistência) --------------------------------------------------

    private class MemoryBurnStore : BurnRecordStore {
        val data = mutableMapOf<String, List<BurnRecord>>()
        override fun load(identity: String) = data[identity].orEmpty()
        override fun save(identity: String, records: List<BurnRecord>) { data[identity] = records }
    }

    @Test
    fun `regiao diferente do ultimo burn emite PREVIOUS_BURN_DIVERGED antes de sobrescrever`() = runBlocking {
        val ecu = FakeEcuMemory(rejectWriteNumber = null, corruptOffsetOnReject = 37)
        val client = newClient(ecu)
        val store = MemoryBurnStore()
        val events = mutableListOf<WriteIntegrityEvent>()
        client.burnRecordStore = store
        client.writeIntegrityListener = { events += it }
        client.connect()
        val metadata = client.getTableDefinitions()!!.veTable
        ecu.load(metadata.page, metadata.offset, TableDomainFacade.prepareVeWrite(metadata, veTable(40)).data)

        client.writeVeTable(veTable(44), 1)
        assertEquals(1, store.data.values.single().size, "o burn deixa um registro por região")
        assertEquals(emptyList(), events)

        // "Power cycle": a EEPROM não guardou o valor e a ECU recarregou outro conteúdo.
        ecu.load(metadata.page, metadata.offset, TableDomainFacade.prepareVeWrite(metadata, veTable(70)).data)
        client.writeVeTable(veTable(48), 1)

        val event = events.single()
        assertEquals(WriteIntegrityKind.PREVIOUS_BURN_DIVERGED, event.kind)
        assertEquals(false, event.kind.critical)
        assertEquals(metadata.page, event.pageId)
        assertTrue(event.expectedCrc != event.actualCrc)
        assertTrue(event.detail!!.startsWith("elapsedMs="))
        assertEquals(1, ecu.burns - 1, "a gravação nova segue normalmente")
    }

    @Test
    fun `regravacao sem mudanca externa nao emite aviso`() = runBlocking {
        val ecu = FakeEcuMemory(rejectWriteNumber = null, corruptOffsetOnReject = 37)
        val client = newClient(ecu)
        val events = mutableListOf<WriteIntegrityEvent>()
        client.burnRecordStore = MemoryBurnStore()
        client.writeIntegrityListener = { events += it }
        client.connect()

        client.writeVeTable(veTable(44), 1)
        client.writeVeTable(veTable(48), 1)
        client.writeVeTable(veTable(52), 1)

        assertEquals(emptyList(), events)
        assertEquals(3, ecu.burns)
    }

    @Test
    fun `burn novo substitui registros sobrepostos da mesma pagina e preserva os demais`() {
        val old = listOf(
            BurnRecord(4, 0, 128, 1, 10, "p4"),
            BurnRecord(2, 0, 288, 2, 10, "ve"),
        )
        val burned = listOf(WriteLedgerEntry(4, 11, 6, 99, true, "trigger"))

        val merged = BurnRecords.merge(old, burned, nowMs = 50)

        assertEquals(listOf(2, 4), merged.map { it.pageId }.sorted())
        assertEquals(99L, merged.first { it.pageId == 4 }.crc, "a página 4 inteira deixou de valer")
        assertEquals(2L, merged.first { it.pageId == 2 }.crc)
    }


    // ---- resumo de saúde da sessão ----------------------------------------------------------------

    @Test
    fun `retry de chunk e a ultima operacao de gravacao entram no resumo de saude`() = runBlocking {
        val ecu = FakeEcuMemory(rejectWriteNumber = 1, corruptOffsetOnReject = 37)
        val client = newClient(ecu)
        client.connect()

        client.writeVeTable(veTable(40), 1)

        val health = client.getConnectionHealth()
        assertTrue(health.writeChunkRetries >= 1, health.toString())
        assertEquals("writeVeTable", health.lastWriteOperation)

        // Depois do disconnect o resumo continua o mesmo (o app lê no callback de desconexão).
        client.disconnect()
        val frozen = client.getConnectionHealth()
        assertEquals(health.writeChunkRetries, frozen.writeChunkRetries)
        assertEquals("writeVeTable", frozen.lastWriteOperation)
    }

    private fun veTable(value: Int) = VeTable(
        rpmBins = (1..16).map { it * 500 },
        loadBins = (1..16).map { it * 10 },
        values = List(16) { List(16) { value } },
    )

    private fun newClient(connection: IEcuConnection) = EcuClient(
        connection = connection,
        onDataReceived = {},
        onConnectionStateChanged = {},
        onError = {},
    )

    /**
     * ECU 202501 com memória de páginas: responde handshake Q/S e 'p'/'M'/'B' em envelope.
     */
    private class FakeEcuMemory(
        private val rejectWriteNumber: Int?,
        private val corruptOffsetOnReject: Int,
        private val corruptEveryWrite: Boolean = false,
        private val corruptFirstWrites: Int = 0,
        /** Reset/brownout durante o burn: depois dele a RAM não bate mais com o gravado. */
        private val corruptOnBurn: Boolean = false,
        /** Depois do burn a ECU não responde mais a leituras de página (rejeita com erro). */
        private val failReadsAfterBurn: Boolean = false,
        /** Buffer serial do AVR: frame 'M' maior que isso é ignorado inteiro (mas ainda responde OK). */
        private val maxFrameBytes: Int? = null,
    ) : IEcuConnection {
        private val pages = mutableMapOf<Int, ByteArray>()
        private val handshake = mutableMapOf(
            'Q'.code.toByte() to ArrayDeque(listOf(byteArrayOf(0xFF.toByte()), byteArrayOf(0xFF.toByte()))),
            'S'.code.toByte() to ArrayDeque(
                listOf(
                    "@@A speeduino 202501 A??".toByteArray(Charsets.US_ASCII),
                    "speeduino202501_BR1 ".toByteArray(Charsets.US_ASCII),
                    "ByRocha1 ".toByteArray(Charsets.US_ASCII),
                )
            ),
        )
        private val pending = ArrayDeque<ByteArray>()
        private var lastCommand: Byte? = null
        private var connected = false
        private var writes = 0
        var burns = 0
            private set
        /** Maior frame 'M' recebido (bytes, com envelope). */
        var maxWriteFrame = 0
            private set
        /** Páginas queimadas com 'b', na ordem em que chegaram. */
        val burnedPages = mutableListOf<Int>()
        /** Quantas vezes o app mandou 'B' sem payload (o firmware rejeita). */
        var bareBurnRejections = 0
            private set

        fun load(page: Int, offset: Int, data: ByteArray) = data.copyInto(page(page), offset)

        fun read(page: Int, offset: Int, length: Int): ByteArray = page(page).copyOfRange(offset, offset + length)

        private fun page(page: Int) = pages.getOrPut(page) { ByteArray(1024) }

        override fun send(data: ByteArray) {
            val command = ModernEnvelopeFake.commandOf(data)
            lastCommand = command
            pending.clear()
            if (command == null || !ModernEnvelopeFake.isModernFrame(data)) return
            val p = data.copyOfRange(3, data.size - 4) // sem length, cmd e CRC
            fun u16(i: Int) = (p[i].toInt() and 0xFF) or ((p[i + 1].toInt() and 0xFF) shl 8)
            when (command) {
                'p'.code.toByte() ->
                    if (failReadsAfterBurn && burns > 0) pending += rc(0x83)
                    else pending += ModernEnvelopeFake.framedResponse(read(p[1].toInt() and 0xFF, u16(2), u16(4)))
                'M'.code.toByte() -> {
                    maxWriteFrame = maxOf(maxWriteFrame, data.size)
                    if (maxFrameBytes != null && data.size > maxFrameBytes) {
                        pending += rc(0x00)
                        return
                    }
                    val pageNum = p[1].toInt() and 0xFF
                    val offset = u16(2)
                    val length = u16(4)
                    writes++
                    if (writes == rejectWriteNumber) {
                        // Frame rejeitado; o resto dele chega depois do flush e vira um 'W' legacy.
                        page(pageNum)[corruptOffsetOnReject] = 212.toByte()
                        pending += rc(0x82)
                    } else {
                        p.copyInto(page(pageNum), offset, 6, 6 + length)
                        if (corruptEveryWrite || writes <= corruptFirstWrites) page(pageNum)[corruptOffsetOnReject] = 212.toByte()
                        pending += rc(0x00)
                    }
                }
                'B'.code.toByte() -> {
                    // Firmware real: 'B' sem payload de página é RANGE_ERR (0x84).
                    bareBurnRejections++
                    pending += rc(0x84)
                }
                'b'.code.toByte() -> {
                    // 'b' + número da página: único burn aceito pelo firmware.
                    val pageNum = p[1].toInt() and 0xFF
                    burns++
                    burnedPages += pageNum
                    if (corruptOnBurn) pages.values.forEach { it[corruptOffsetOnReject] = 99 }
                    pending += rc(0x04)
                }
                else -> pending += ModernEnvelopeFake.framedResponse(nextHandshake(command))
            }
        }

        private fun rc(code: Int): List<ByteArray> = listOf(byteArrayOf(0x00, 0x01), byteArrayOf(code.toByte()), ByteArray(4))

        private fun nextHandshake(command: Byte): ByteArray = handshake[command]?.removeFirstOrNull() ?: ByteArray(0)

        override fun receive(size: Int): ByteArray {
            pending.removeFirstOrNull()?.let { return it }
            val command = lastCommand ?: error("receive() sem send()")
            return if (command == 'Q'.code.toByte() || command == 'S'.code.toByte()) nextHandshake(command) else ByteArray(0)
        }

        override suspend fun connect() {
            connected = true
        }

        override fun disconnect() {
            connected = false
        }

        override fun isConnected(): Boolean = connected
        override fun getConnectionInfo(): String = "tcp:10.0.0.5:5555"
        override fun supportsModernProtocol(): Boolean = false
        override fun supportsModernProtocolFallback(): Boolean = false
        override fun prefersLegacyProtocol(): Boolean = true
        override fun legacyFirmwareHandshakeAttempts(): Int = 2
        override fun setOnConnectionStateChanged(callback: (Boolean) -> Unit) = Unit
        override fun setOnError(callback: (String) -> Unit) = Unit
        override fun clearInputBuffer() = Unit
    }
}
