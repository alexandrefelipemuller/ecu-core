package com.speeduino.manager.protocol

import com.speeduino.manager.connection.ISpeeduinoConnection
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Quem decide o enquadramento é a era do firmware, não o transporte.
 *
 * Enquanto a era é desconhecida (handshake em andamento) valem os flags do transporte. Depois do
 * handshake, `setSessionModernEnvelope` manda: num firmware 202201+ o live data é modern mesmo
 * por Bluetooth, e num firmware sem envelope o frame modern não pode ser emitido.
 */
class SpeeduinoProtocolModernLiveDataGuardTest {

    @Test
    fun `modern live data is blocked on legacy-first transport while era is unknown`() = runBlocking {
        val connection = FakeTransport(prefersLegacy = true, supportsModern = false, modernFallback = true)
        connection.connect()
        val protocol = SpeeduinoProtocol(connection)

        assertFailsWith<Exception> { protocol.readLiveDataModern(127) }

        assertTrue(
            connection.sentPackets.isEmpty(),
            "nenhum byte pode chegar na ECU: enviados ${connection.sentPackets.size} pacotes"
        )
    }

    @Test
    fun `confirmed modern envelope wins over a legacy-first transport`() = runBlocking {
        val payload = ByteArray(4) { (it + 1).toByte() }
        val connection = FakeTransport(
            // Exatamente os flags do SpeeduinoBluetoothConnection.
            prefersLegacy = true,
            supportsModern = false,
            modernFallback = true,
            responseChunks = arrayOf(
                byteArrayOf(0x00, 0x05),
                byteArrayOf(0x00) + payload,
                byteArrayOf(0x00, 0x00, 0x00, 0x00),
            )
        )
        connection.connect()
        val protocol = SpeeduinoProtocol(connection)
        protocol.setSessionModernEnvelope(true)

        val data = protocol.readLiveDataModern(4)

        assertContentEquals(payload, data)
        val frame = connection.sentPackets.single()
        assertEquals(0x72, frame[2].toInt() and 0xFF, "payload deve começar com 'r'")
    }

    @Test
    fun `confirmed legacy firmware blocks the modern frame even on a modern transport`() = runBlocking {
        val connection = FakeTransport(prefersLegacy = false, supportsModern = true, modernFallback = true)
        connection.connect()
        val protocol = SpeeduinoProtocol(connection)
        protocol.setSessionModernEnvelope(false)

        assertFailsWith<Exception> { protocol.readLiveDataModern(35) }

        assertTrue(connection.sentPackets.isEmpty())
    }

    @Test
    fun `modern live data is allowed when transport speaks modern natively`() = runBlocking {
        val payload = ByteArray(4) { (it + 1).toByte() }
        val connection = FakeTransport(
            prefersLegacy = false,
            supportsModern = true,
            modernFallback = false,
            responseChunks = arrayOf(
                byteArrayOf(0x00, 0x05),
                byteArrayOf(0x00) + payload,
                byteArrayOf(0x00, 0x00, 0x00, 0x00),
            )
        )
        connection.connect()
        val protocol = SpeeduinoProtocol(connection)

        val data = protocol.readLiveDataModern(4)

        assertContentEquals(payload, data)
        assertTrue(connection.sentPackets.isNotEmpty())
    }

    private class FakeTransport(
        private val prefersLegacy: Boolean,
        private val supportsModern: Boolean,
        private val modernFallback: Boolean,
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

        override fun supportsModernProtocol(): Boolean = supportsModern

        override fun supportsModernProtocolFallback(): Boolean = modernFallback

        override fun prefersLegacyProtocol(): Boolean = prefersLegacy

        override fun setOnConnectionStateChanged(callback: (Boolean) -> Unit) = Unit

        override fun setOnError(callback: (String) -> Unit) = Unit
    }
}
