package io.ecucore

import io.ecucore.model.MsTemperatureUnits
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** O client lê o bit `tempUnits` (página 1, offset 599) no connect e ao ler a página 0x04 inteira. */
class EcuClientMsTemperatureUnitsTest {

    @AfterTest
    fun reset() {
        MsTemperatureUnits.celsius = false
    }

    private suspend fun connect(kind: ClientFakeEcu.Kind, tempUnits: Int): Pair<EcuClient, ClientFakeEcu> {
        val ecu = ClientFakeEcu(kind)
        ecu.load(0x04, 599, byteArrayOf(tempUnits.toByte()))
        val client = EcuClient(ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        client.connect()
        return client to ecu
    }

    @Test
    fun `connect detecta tempUnits em C no MS2 e na MegaSpeed`() = runBlocking {
        for (kind in listOf(ClientFakeEcu.Kind.MS2, ClientFakeEcu.Kind.MEGASPEED)) {
            val (client, _) = connect(kind, tempUnits = 1)
            assertTrue(MsTemperatureUnits.celsius, kind.name)
            client.disconnect()
            assertFalse(MsTemperatureUnits.celsius, "${kind.name}: disconnect volta ao padrão")
        }
    }

    @Test
    fun `connect mantem F quando o bit esta zerado`() = runBlocking {
        connect(ClientFakeEcu.Kind.MS2, tempUnits = 0)
        assertFalse(MsTemperatureUnits.celsius)
    }

    @Test
    fun `leitura da pagina 0x04 atualiza a unidade`() = runBlocking {
        val (client, ecu) = connect(ClientFakeEcu.Kind.MS2, tempUnits = 0)
        ecu.load(0x04, 599, byteArrayOf(1))
        client.readFullPage(0x04, 1024, 256)
        assertTrue(MsTemperatureUnits.celsius)
        ecu.load(0x04, 599, byteArrayOf(0))
        client.readFullPage(0x04, 1024, 256)
        assertFalse(MsTemperatureUnits.celsius)
    }

    @Test
    fun `Speeduino nao le nem altera a unidade`() = runBlocking {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025)
        val client = EcuClient(ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        client.connect()
        assertFalse(MsTemperatureUnits.celsius)
        assertEquals(0, ecu.tableReadLengths.count { it == 1 })
    }
}
