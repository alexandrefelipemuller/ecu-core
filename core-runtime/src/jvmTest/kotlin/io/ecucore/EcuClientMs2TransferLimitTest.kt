package io.ecucore

import io.ecucore.model.IgnitionTable
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regressão de campo 2026-11 (MicroSquirt, "MS2Extra comms342hU", USB): o .ini diz
 * `blockingFactor = 256 ; 256 is max read/write data size for MS2/Extra`, mas a ignição
 * (advanceTable1 S16 12x12 = 288 bytes, tabela 0x0A) era lida e gravada num único 'r'/'w' de 288
 * bytes. A ECU respondia 0x84 (out of range): o sync inicial falhava com
 * `page=10 offset=0 length=288 ... code=84` e a gravação da ignição nunca passava.
 */
class EcuClientMs2TransferLimitTest {

    private suspend fun connect(kind: ClientFakeEcu.Kind = ClientFakeEcu.Kind.MS2): Pair<EcuClient, ClientFakeEcu> {
        val ecu = ClientFakeEcu(kind)
        val client = EcuClient(ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        client.connect()
        return client to ecu
    }

    @Test
    fun `leitura de 288 bytes no MS2 vai em blocos de no maximo 256`() = runBlocking {
        val (client, ecu) = connect()
        val expected = ByteArray(288) { (it * 7).toByte() }
        ecu.load(0x0A, 0, expected)
        ecu.tableReadLengths.clear()

        val data = client.readConfigChunk(0x0A, 0, 288)

        assertContentEquals(expected, data)
        assertEquals(listOf(256, 32), ecu.tableReadLengths)
    }

    @Test
    fun `MS2 e MegaSpeed leem e gravam a ignicao 12x12 sem estourar o limite`() = runBlocking {
        for (kind in listOf(ClientFakeEcu.Kind.MS2, ClientFakeEcu.Kind.MEGASPEED)) {
            val (client, ecu) = connect(kind)
            val ign = IgnitionTable(
                rpmBins = (1..12).map { it * 500 },
                loadBins = (1..12).map { it * 10 },
                values = List(12) { List(12) { 132 } }, // 13.2° (caso do usuário)
                valueScale = 0.1,
            )

            client.writeIgnitionTable(ign, 1)
            val readBack = client.readIgnitionTable(1)

            assertTrue(ecu.writes.all { it.data.size <= 256 }, kind.name)
            assertTrue(ecu.tableReadLengths.all { it <= 256 }, kind.name)
            assertEquals(13.2, readBack.degreesAt(0, 0), 1e-9, kind.name)
            assertEquals(13.2, readBack.degreesAt(11, 11), 1e-9, kind.name)
            assertTrue(ecu.burns.isNotEmpty(), kind.name)
        }
    }

    @Test
    fun `envelope vazio na leitura do MS2 e recuperado com retry`() = runBlocking {
        val (client, ecu) = connect()
        val expected = ByteArray(256) { it.toByte() }
        ecu.load(0x09, 0, expected)
        var failures = 1
        ecu.onFrame = { cmd, _ ->
            if (cmd == 'r' && failures > 0) {
                failures--
                ModernEnvelopeFake.framedResponse(ByteArray(0))
            } else null
        }

        val data = client.readConfigChunk(0x09, 0, 256)

        assertContentEquals(expected, data)
    }

    @Test
    fun `tamanho absurdo de envelope no MS2 e recuperado com retry`() = runBlocking {
        val (client, ecu) = connect()
        val expected = ByteArray(256) { (255 - it).toByte() }
        ecu.load(0x09, 0, expected)
        var failures = 1
        ecu.onFrame = { cmd, _ ->
            if (cmd == 'r' && failures > 0) {
                failures--
                listOf(byteArrayOf(0x72, 0x01)) // 0x7201 = 29185 bytes
            } else null
        }

        val data = client.readConfigChunk(0x09, 0, 256)

        assertContentEquals(expected, data)
    }
}
