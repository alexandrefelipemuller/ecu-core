package io.ecucore

import io.ecucore.connection.ISpeeduinoConnection
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
class SpeeduinoClientVerifiedTableWriteTest {

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

    private fun veTable(value: Int) = VeTable(
        rpmBins = (1..16).map { it * 500 },
        loadBins = (1..16).map { it * 10 },
        values = List(16) { List(16) { value } },
    )

    private fun newClient(connection: ISpeeduinoConnection) = SpeeduinoClient(
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
    ) : ISpeeduinoConnection {
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
                'p'.code.toByte() -> pending += ModernEnvelopeFake.framedResponse(read(p[1].toInt() and 0xFF, u16(2), u16(4)))
                'M'.code.toByte() -> {
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
                        if (corruptEveryWrite) page(pageNum)[corruptOffsetOnReject] = 212.toByte()
                        pending += rc(0x00)
                    }
                }
                'B'.code.toByte() -> {
                    burns++
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
