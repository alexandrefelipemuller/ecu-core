package io.ecucore

import io.ecucore.connection.ISpeeduinoConnection
import io.ecucore.model.EcuFamily
import io.ecucore.model.Ms3GenericSensorConfig
import io.ecucore.model.Ms3GenericSensors
import io.ecucore.model.Ms3SensorTransform
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Leitura/gravação dos Generic Sensor Inputs da MS3 (pressão de óleo/combustível) pelo client:
 * leitura da tabela 0x05 inteira em 'r', gravação só do trecho dos sensores (662..773) em 'w' e
 * burn 'b' da tabela 0x05 - nunca o resto da página.
 */
class SpeeduinoClientMs3GenericSensorsTest {

    @Test
    fun `reads the 16 generic sensor slots from table 0x05`() = runBlocking {
        val ecu = FakeMs3()
        ecu.page[662 + 2] = 4 // sensor03 em Analog In 1 / EGO2
        ecu.page[678 + 2] = 9 // Linear 0.5-4.5V
        ecu.page[726 + 5] = 100 // max 10.0
        ecu.page[758 + 2] = 100
        val client = connectedClient(ecu)

        val snapshot = client.readMs3GenericSensors()

        assertEquals(Ms3GenericSensors.FirmwareGeneration.MS3_1_5_PLUS, snapshot.generation)
        assertEquals(Ms3GenericSensorConfig(3, 4, 9, 0.0, 10.0, 100), snapshot.sensors[2])
        assertEquals("EGO2 (ADC12)", snapshot.boardInputs.first { it.source == 4 }.label)
    }

    @Test
    fun `writing a slot sends only the generic sensor span and burns table 0x05`() = runBlocking {
        val ecu = FakeMs3()
        for (i in ecu.page.indices) ecu.page[i] = (i * 3).toByte()
        val original = ecu.page.copyOf()
        val client = connectedClient(ecu)
        val config = Ms3GenericSensorConfig(
            index = 3, source = 4, transform = Ms3SensorTransform.LINEAR_05_45V.raw,
            valueLow = 0.0, valueHigh = 10.0, lagFactor = 100,
        )

        client.writeMs3GenericSensor(config)

        val write = ecu.writes.single()
        assertEquals(0x05, write.table)
        assertEquals(662, write.offset)
        assertEquals(112, write.data.size)
        assertEquals(listOf(0x05), ecu.burns)
        val expected = Ms3GenericSensors.apply(original, config, Ms3GenericSensors.FirmwareGeneration.MS3_1_5_PLUS)
        assertContentEquals(expected, ecu.page, "página na ECU = read-modify-write do slot 3")
        assertContentEquals(original.copyOfRange(0, 662), ecu.page.copyOfRange(0, 662))
        assertContentEquals(original.copyOfRange(774, 1024), ecu.page.copyOfRange(774, 1024))
    }

    @Test
    fun `0523 firmware refuses the 0,5-4,5V transform before touching the ECU`() = runBlocking {
        val ecu = FakeMs3(signature = "MS3 Format 0523.15 ")
        val client = connectedClient(ecu)

        assertFailsWith<IllegalArgumentException> {
            client.writeMs3GenericSensor(Ms3GenericSensorConfig(1, 4, Ms3SensorTransform.LINEAR_05_45V.raw, 0.0, 10.0, 100))
        }
        assertTrue(ecu.writes.isEmpty())
        assertTrue(ecu.burns.isEmpty())
    }

    private suspend fun connectedClient(ecu: FakeMs3): SpeeduinoClient {
        val client = SpeeduinoClient(connection = ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        client.connect()
        assertEquals(EcuFamily.MS3, client.getFirmwareInfoCached()?.family)
        return client
    }

    private class Write(val table: Int, val offset: Int, val data: ByteArray)

    /** MS3 newserial: 'Q'/'S' legacy, 'r'/'w'/'b' em envelope sobre uma tabela 0x05 em memória. */
    private class FakeMs3(private val signature: String = "MS3 Format 0592.13 ") : ISpeeduinoConnection {
        val page = ByteArray(1024)
        val writes = mutableListOf<Write>()
        val burns = mutableListOf<Int>()
        private var connected = false
        private val pending = ArrayDeque<ByteArray>()

        override suspend fun connect() {
            connected = true
        }

        override fun disconnect() {
            connected = false
        }

        override fun send(data: ByteArray) {
            pending.clear()
            if (ModernEnvelopeFake.isModernFrame(data)) {
                val payload = data.copyOfRange(2, data.size - 4)
                val table = payload.getOrNull(2)?.toInt()?.and(0xFF) ?: -1
                when (payload[0].toInt().toChar()) {
                    'r' -> {
                        val offset = u16be(payload, 3)
                        val length = u16be(payload, 5)
                        val body = if (table == 0x05) page.copyOfRange(offset, offset + length) else ByteArray(length)
                        pending += ModernEnvelopeFake.framedResponse(body)
                    }
                    'w' -> {
                        val offset = u16be(payload, 3)
                        val length = u16be(payload, 5)
                        val body = payload.copyOfRange(7, 7 + length)
                        writes += Write(table, offset, body)
                        if (table == 0x05) body.copyInto(page, offset)
                        pending += ModernEnvelopeFake.framedResponse(ByteArray(0))
                    }
                    'b' -> {
                        burns += table
                        pending += ModernEnvelopeFake.framedResponse(ByteArray(0))
                    }
                    else -> pending += listOf(byteArrayOf(0x00, 0x01), byteArrayOf(0x83.toByte()), ByteArray(4))
                }
                return
            }
            when (data.firstOrNull()?.toInt()?.toChar()) {
                'Q' -> pending += (signature + "\u0000").toByteArray(Charsets.US_ASCII)
                'S' -> pending += "MS3 1.5.1 release 20170101 (c)JSM/KC\u0000".toByteArray(Charsets.US_ASCII)
                else -> Unit
            }
        }

        override fun receive(size: Int): ByteArray = pending.removeFirstOrNull() ?: ByteArray(0)

        override fun isConnected(): Boolean = connected

        override fun getConnectionInfo(): String = "bt:00:11:22:33:44:66"

        override fun supportsModernProtocol(): Boolean = false

        override fun supportsModernProtocolFallback(): Boolean = true

        override fun supportsModernConfigReads(): Boolean = false

        override fun prefersLegacyProtocol(): Boolean = true

        override fun legacyFirmwareHandshakeAttempts(): Int = 2

        override fun setOnConnectionStateChanged(callback: (Boolean) -> Unit) = Unit

        override fun setOnError(callback: (String) -> Unit) = Unit

        override fun clearInputBuffer() = Unit

        private fun u16be(bytes: ByteArray, index: Int): Int =
            ((bytes[index].toInt() and 0xFF) shl 8) or (bytes[index + 1].toInt() and 0xFF)
    }
}
