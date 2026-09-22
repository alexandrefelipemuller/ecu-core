package io.ecucore.protocol

import io.ecucore.connection.ISpeeduinoConnection
import io.ecucore.model.EcuFamily
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Achado de campo 2026-09-21 (MS2/Extra 3.4.3 "MS2Extra comms342h2" via Bluetooth): a leitura de
 * config da MS2 não tem alternativa fora do 'r' em envelope CRC32 - o 'p' cru é comando só do
 * Speeduino e a ECU o rejeita com 0x84. Em transportes legacy-first (BT/USB) o table read da MS2
 * precisa ir pro fio em envelope mesmo quando o transporte não habilita fallback moderno.
 */
class SpeeduinoProtocolMs2TableReadTest {

    @Test
    fun `MS2 table read goes out as enveloped r on a legacy-first transport without modern fallback`() = runBlocking {
        val tableData = ByteArray(16) { (it + 1).toByte() }
        val connection = FakeTransport(
            responseChunks = arrayOf(
                byteArrayOf(0x00, 0x11),
                byteArrayOf(0x00) + tableData,
                byteArrayOf(0x00, 0x00, 0x00, 0x00),
            )
        )
        connection.connect()
        val protocol = SpeeduinoProtocol(connection)
        protocol.setSessionEcuFamily(EcuFamily.MS2)

        val data = protocol.readTable(tableId = 0x04, offset = 0x0100, length = 16, family = EcuFamily.MS2)

        assertContentEquals(tableData, data)
        val frame = connection.sentPackets.single()
        assertContentEquals(
            byteArrayOf(0x00, 0x07, 'r'.code.toByte(), 0x00, 0x04, 0x01, 0x00, 0x00, 0x10),
            frame.copyOf(9),
            "envelope [len BE] + 'r' canId table offset(BE) length(BE)",
        )
        assertEquals(13, frame.size, "envelope inclui CRC32 de 4 bytes")
    }

    private class FakeTransport(
        private val responseChunks: Array<ByteArray> = emptyArray(),
    ) : ISpeeduinoConnection {
        private var connected = false
        private var responseIndex = 0

        val sentPackets = mutableListOf<ByteArray>()

        override suspend fun connect() {
            connected = true
        }

        override fun disconnect() {
            connected = false
        }

        override fun send(data: ByteArray) {
            sentPackets += data.copyOf()
        }

        override fun receive(size: Int): ByteArray =
            responseChunks.getOrNull(responseIndex++) ?: ByteArray(0)

        override fun isConnected(): Boolean = connected

        override fun getConnectionInfo(): String = "fake"

        override fun supportsModernProtocol(): Boolean = false

        override fun supportsModernProtocolFallback(): Boolean = false

        override fun supportsModernConfigReads(): Boolean = false

        override fun prefersLegacyProtocol(): Boolean = true

        override fun setOnConnectionStateChanged(callback: (Boolean) -> Unit) = Unit

        override fun setOnError(callback: (String) -> Unit) = Unit
    }
}
