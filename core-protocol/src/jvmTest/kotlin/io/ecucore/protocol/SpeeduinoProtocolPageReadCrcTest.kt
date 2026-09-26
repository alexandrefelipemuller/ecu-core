package io.ecucore.protocol

import io.ecucore.connection.ISpeeduinoConnection
import io.ecucore.model.FirmwareEra
import io.ecucore.shared.Crc32Table
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Relato de campo (2026-09): Speeduino 2023 mostrou células isoladas absurdas na VE (212, 93 no
 * meio de um bolsão < 20) que sumiram depois de reinstalar o app. A leitura moderna de página
 * ignorava CRC mismatch e a página corrompida ia pro cache persistente. Leitura de página agora
 * rejeita CRC divergente e repete o comando.
 */
class SpeeduinoProtocolPageReadCrcTest {

    private val pageData = ByteArray(16) { (it + 1).toByte() }

    @Test
    fun `page read retries when CRC does not match and returns the clean page`() = runBlocking {
        val corrupted = pageData.copyOf().also { it[5] = 0xD4.toByte() }
        val connection = FakeTransport(
            frame(corrupted, crcOf = pageData) + frame(pageData, crcOf = pageData)
        )
        val protocol = newProtocol(connection)

        val data = protocol.readPage(pageNum = 2, offset = 0, length = 16)

        assertContentEquals(pageData, data)
        assertEquals(2, connection.sentPackets.size, "deve repetir o 'p' após o CRC divergente")
    }

    @Test
    fun `page read fails instead of returning bytes with a persistent CRC mismatch`() {
        val corrupted = pageData.copyOf().also { it[5] = 0xD4.toByte() }
        val connection = FakeTransport(
            frame(corrupted, crcOf = pageData) + frame(corrupted, crcOf = pageData) + frame(corrupted, crcOf = pageData)
        )
        val protocol = newProtocol(connection)

        assertFailsWith<Exception> {
            runBlocking { protocol.readPage(pageNum = 2, offset = 0, length = 16) }
        }
        assertEquals(3, connection.sentPackets.size)
    }

    private fun newProtocol(connection: FakeTransport): SpeeduinoProtocol = runBlocking {
        connection.connect()
        SpeeduinoProtocol(connection).apply {
            setSessionModernEnvelope(true)
            setSessionFirmwareEra(FirmwareEra.MODERN_2025)
        }
    }

    /** Frame moderno de resposta: length, `SERIAL_RC_OK` + dados, CRC calculado sobre [crcOf]. */
    private fun frame(data: ByteArray, crcOf: ByteArray): List<ByteArray> {
        val body = byteArrayOf(0x00) + data
        val crc = Crc32Table.compute(byteArrayOf(0x00) + crcOf)
        return listOf(
            byteArrayOf(((body.size shr 8) and 0xFF).toByte(), (body.size and 0xFF).toByte()),
            body,
            byteArrayOf(
                ((crc shr 24) and 0xFF).toByte(),
                ((crc shr 16) and 0xFF).toByte(),
                ((crc shr 8) and 0xFF).toByte(),
                (crc and 0xFF).toByte(),
            ),
        )
    }

    private class FakeTransport(private val responseChunks: List<ByteArray>) : ISpeeduinoConnection {
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

        override fun supportsModernProtocol(): Boolean = true

        override fun supportsModernProtocolFallback(): Boolean = true

        override fun prefersLegacyProtocol(): Boolean = false

        override fun setOnConnectionStateChanged(callback: (Boolean) -> Unit) = Unit

        override fun setOnError(callback: (String) -> Unit) = Unit
    }
}
