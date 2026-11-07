package io.ecucore

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Achado de campo 2026-11 (MicroSquirt "MS2Extra comms342hU", USB, v1.6.7):
 * - no log CSV, um frame isolado com secl=0, rpm=41175, bateria=100 V, CLT=-3007 °C entre
 *   frames normais (o 'A' cru do live data MS não tem cabeçalho nem CRC);
 * - com o stream ativo, ler a WUE (tabela 4, offset 336) falhava com `unexpected response code=1`
 *   (0x01 = realtime data no newserial MS) e a marcha lenta (offset 547) com `code=null`.
 */
class EcuClientMsStreamDesyncTest {

    private fun ms2Frame(secl: Int, rpm: Int, cltRaw: Int, battRaw: Int, advRaw: Int): ByteArray {
        val b = ByteArray(212)
        fun put(off: Int, v: Int) {
            b[off] = ((v shr 8) and 0xFF).toByte()
            b[off + 1] = (v and 0xFF).toByte()
        }
        put(0, secl)
        put(6, rpm)
        put(8, advRaw)
        put(18, 700) // MAP 70 kPa
        put(20, 400) // MAT
        put(22, cltRaw)
        put(26, battRaw)
        return b
    }

    private val good = ms2Frame(secl = 293, rpm = 900, cltRaw = 1110, battRaw = 144, advRaw = 220)
    private val corrupt = ms2Frame(secl = 0, rpm = 41175, cltRaw = -30000, battRaw = 1000, advRaw = -29977)

    private suspend fun streamSamples(frames: (Int) -> ByteArray, count: Int): List<EcuLiveData> {
        val samples = CopyOnWriteArrayList<EcuLiveData>()
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.MS2)
        val client = EcuClient(ecu, onDataReceived = { samples += it }, onConnectionStateChanged = {}, onError = {})
        client.connect()
        val aCount = AtomicInteger(0)
        ecu.onLegacy = { cmd, _ -> if (cmd == 'A') frames(aCount.getAndIncrement()) else null }

        client.startLiveDataStream(10L)
        withTimeout(15_000L) {
            while (samples.size < count) delay(10)
        }
        client.stopLiveDataStream()
        return samples.toList()
    }

    @Test
    fun `frame MS isolado implausivel nao chega ao dashboard nem ao log`() = runBlocking {
        val samples = streamSamples(frames = { i -> if (i == 3) corrupt else good }, count = 6)

        assertTrue(samples.none { it.rpm > 20000 }, "frame corrompido vazou: ${samples.map { it.rpm }}")
        assertTrue(samples.all { it.secl == 293 })
    }

    @Test
    fun `estado implausivel persistente volta a ser mostrado apos dois descartes`() = runBlocking {
        // Ex.: chicote solto de verdade - esconder para sempre deixaria o problema invisível.
        val samples = streamSamples(frames = { i -> if (i < 2) good else corrupt }, count = 4)

        assertEquals(listOf(900, 900), samples.take(2).map { it.rpm })
        assertTrue(samples.drop(2).all { it.rpm == 41175 }, samples.map { it.rpm }.toString())
    }

    @Test
    fun `stream que ja comeca implausivel nao e filtrado`() = runBlocking {
        val samples = streamSamples(frames = { corrupt }, count = 2)

        assertTrue(samples.all { it.rpm == 41175 })
    }

    @Test
    fun `leitura de config MS2 com resposta de outro comando code=1 e recuperada`() = runBlocking {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.MS2)
        val client = EcuClient(ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        client.connect()
        val wue = intArrayOf(180, 170, 160, 150, 140, 130, 120, 110, 105, 100).map { it.toByte() }.toByteArray()
        ecu.load(0x04, 336, wue)
        var failures = 2
        ecu.onFrame = { cmd, _ ->
            if (cmd == 'r' && failures > 0) {
                failures--
                // envelope cujo 1º byte é 0x01 (realtime data), não 0x00 (OK)
                listOf(byteArrayOf(0x00, 0x03), byteArrayOf(0x01, 0x01, 0x07), ByteArray(4))
            } else null
        }

        val data = client.readConfigChunk(0x04, 336, 10)

        assertContentEquals(wue, data)
        assertEquals(0, failures)
    }

    @Test
    fun `resposta de live data abortada pela pausa nao contamina a primeira leitura de config`() = runBlocking {
        // Caminho real das telas WUE/marcha lenta: withLiveDataPaused -> pauseLiveDataStream ->
        // readIniCurve. A pausa aborta a leitura do 'A', mas a ECU termina de mandar a resposta.
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.MS2)
        val samples = AtomicInteger(0)
        val client = EcuClient(ecu, onDataReceived = { samples.incrementAndGet() }, onConnectionStateChanged = {}, onError = {})
        client.connect()
        ecu.liveBytes = good
        val wue = intArrayOf(180, 170, 160, 150, 140, 130, 120, 110, 105, 100).map { it.toByte() }.toByteArray()
        ecu.load(0x04, 336, wue)

        client.startLiveDataStream(10L)
        withTimeout(10_000L) { while (samples.get() < 3) delay(10) }
        // envelope que começa com 0x01 (realtime data), ainda em trânsito no flush da pausa
        ecu.lateBytesOnAbort = listOf(byteArrayOf(0x00, 0x01), byteArrayOf(0x01), ByteArray(4))
        client.pauseLiveDataStream(timeoutMs = 2000)
        val rBefore = ecu.commands.count { it == 'r' }

        val data = client.readConfigChunk(0x04, 336, 10)

        assertContentEquals(wue, data)
        assertEquals(1, ecu.commands.count { it == 'r' } - rBefore, "a 1ª tentativa já devia ler a resposta certa")
    }

    @Test
    fun `erro real da ECU 0x84 no MS2 continua sem retry`() = runBlocking {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.MS2)
        val client = EcuClient(ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        client.connect()
        var reads = 0
        ecu.onFrame = { cmd, _ ->
            if (cmd == 'r') {
                reads++
                listOf(byteArrayOf(0x00, 0x01), byteArrayOf(0x84.toByte()), ByteArray(4))
            } else null
        }

        val result = runCatching { client.readConfigChunk(0x04, 547, 1) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("code=84"), result.exceptionOrNull()!!.message)
        assertEquals(1, reads)
    }
}
