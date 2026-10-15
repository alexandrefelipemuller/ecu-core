package io.ecucore

import io.ecucore.model.MegaSpeedLoggerKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Captura de loggers da MegaSpeed no client: arma, espera DONELOG, lê. */
class SpeeduinoClientMegaSpeedLoggerTest {

    private suspend fun connect(
        kind: ClientFakeEcu.Kind = ClientFakeEcu.Kind.MEGASPEED,
        doneLog: Boolean = true,
    ): Triple<SpeeduinoClient, ClientFakeEcu, MutableList<Int>> {
        val ecu = ClientFakeEcu(kind)
        val client = SpeeduinoClient(ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        client.connect()
        client.megaSpeedLogPollMs = 20
        ecu.liveBytes = ByteArray(219).also { it[80] = if (doneLog) 0x02 else 0x00 }
        // tabelas lidas via 'r' (payload: [canId, tabela, off(2), len(2)])
        val tablesRead = mutableListOf<Int>()
        ecu.onFrame = { cmd, payload ->
            if (cmd == 'r' && payload.size >= 2) tablesRead += payload[1].toInt() and 0xFF
            null
        }
        return Triple(client, ecu, tablesRead)
    }

    private fun fillTooth(ecu: ClientFakeEcu, count: Int, ticks: Int) {
        val buf = ByteArray(1024)
        repeat(count) { i ->
            val o = i * 3
            buf[o] = (0x10 or ((ticks shr 16) and 0x0F)).toByte()
            buf[o + 1] = (ticks shr 8).toByte()
            buf[o + 2] = ticks.toByte()
        }
        buf[0x3FF] = 1
        ecu.load(0xF0, 0, buf)
    }

    @Test
    fun `captura le a tabela do logger duas vezes e decodifica`() = runBlocking<Unit> {
        val (c, ecu, tables) = connect()
        fillTooth(ecu, count = 12, ticks = 833)

        val capture = c.captureMegaSpeedLog(MegaSpeedLoggerKind.TOOTH, timeoutMs = 2000)

        assertEquals(12, capture.records.size)
        assertEquals(833, capture.records.first().ticks)
        assertTrue(capture.records.all { it.synced })
        assertTrue(capture.formatKnown)
        // 1ª leitura arma; depois consulta de live data (tabela 7); 2ª leitura devolve o buffer.
        val loggerReads = tables.count { it == 0xF0 }
        assertEquals(2, loggerReads)
        // ordem: leitura que arma ('r'), consulta de status/live data ('A' na fake), leitura do buffer ('r')
        val seq = ecu.commands.joinToString("")
        assertTrue(Regex("r+A+r+$").containsMatchIn(seq.trimEnd()), "sequência de comandos: $seq")
    }

    @Test
    fun `usa a tabela certa de cada logger`() = runBlocking<Unit> {
        for ((kind, table) in listOf(
            MegaSpeedLoggerKind.TOOTH to 0xF0, MegaSpeedLoggerKind.TRIGGER to 0xF1,
            MegaSpeedLoggerKind.COMPOSITE to 0xF2, MegaSpeedLoggerKind.SYNC_COMPOSITE to 0xF3,
        )) {
            val (c, _, tables) = connect()
            c.captureMegaSpeedLog(kind, timeoutMs = 2000)
            assertEquals(2, tables.count { it == table }, kind.name)
        }
    }

    @Test
    fun `sem DONELOG estoura o prazo e nao le o buffer incompleto`() = runBlocking<Unit> {
        val (c, _, tables) = connect(doneLog = false)
        val e = assertFailsWith<IllegalStateException> {
            c.captureMegaSpeedLog(MegaSpeedLoggerKind.COMPOSITE, timeoutMs = 300)
        }
        assertTrue("não completou" in e.message.orEmpty())
        // só a leitura que arma: ler antes do DONELOG reiniciaria a captura
        assertEquals(1, tables.count { it == 0xF2 })
    }

    @Test
    fun `outras familias nao suportam o logger`() = runBlocking<Unit> {
        for (kind in listOf(ClientFakeEcu.Kind.MS2, ClientFakeEcu.Kind.MS3, ClientFakeEcu.Kind.SPEEDUINO_2025)) {
            val (c, _, tables) = connect(kind)
            assertFailsWith<UnsupportedOperationException>(kind.name) {
                c.captureMegaSpeedLog(MegaSpeedLoggerKind.TOOTH, timeoutMs = 100)
            }
            assertTrue(tables.none { it in 0xF0..0xF3 }, kind.name)
        }
    }
}
