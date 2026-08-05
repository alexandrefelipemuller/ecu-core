package com.speeduino.manager.protocol

import com.speeduino.manager.connection.ISpeeduinoConnection
import com.speeduino.manager.model.EcuFamily
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class SpeeduinoProtocolRusEfiTest {

    @Test
    fun `rusEFI table read ignores legacy preferred session`() = runBlocking {
        val connection = FakeConnection(
            responseChunks = arrayOf(
                byteArrayOf(0x00, 0x05),
                byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44),
                byteArrayOf(0x00, 0x00, 0x00, 0x00),
            )
        )
        connection.connect()

        val protocol = SpeeduinoProtocol(connection)
        protocol.setSessionLegacyPreferred(true)

        val data = protocol.readTable(0x0000, 0, 4, EcuFamily.RUSEFI)

        assertContentEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44), data)
        assertTrue(connection.sentPackets.isNotEmpty())
        assertEquals('R', connection.sentPackets.first()[2].toInt().toChar())
    }

    private class FakeConnection(
        private val responseChunks: Array<ByteArray>,
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

        override fun receive(size: Int): ByteArray {
            return responseChunks.getOrNull(responseIndex++) ?: ByteArray(0)
        }

        override fun isConnected(): Boolean = connected

        override fun getConnectionInfo(): String = "fake"

        override fun supportsModernProtocol(): Boolean = true

        override fun supportsModernProtocolFallback(): Boolean = false

        override fun prefersLegacyProtocol(): Boolean = false

        override fun setOnConnectionStateChanged(callback: (Boolean) -> Unit) = Unit

        override fun setOnError(callback: (String) -> Unit) = Unit
    }
}
