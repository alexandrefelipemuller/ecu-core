package com.speeduino.manager

import com.speeduino.manager.connection.ConnectionTrace
import com.speeduino.manager.connection.ConnectionTraceSink
import com.speeduino.manager.connection.ISpeeduinoConnection
import com.speeduino.manager.model.UnsupportedFirmwareException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith

class SpeeduinoClientFirmwareHandshakeTest {

    @Test
    fun connectFallsBackToLegacySWhenQIsGarbage() = runBlocking {
        val connection = FakeConnection(
            responses = mapOf(
                'Q'.code.toByte() to arrayDequeOf(
                    byteArrayOf(0xFF.toByte()),
                    byteArrayOf(0xFF.toByte()),
                ),
                'S'.code.toByte() to arrayDequeOf(
                    "@@A speeduino 202501\u0000A??".toByteArray(Charsets.US_ASCII),
                    "speeduino202501_BR1\u0000".toByteArray(Charsets.US_ASCII),
                    "ByRocha1\u0000".toByteArray(Charsets.US_ASCII),
                ),
                'p'.code.toByte() to arrayDequeOf(ByteArray(16)),
            )
        )

        val client = SpeeduinoClient(
            connection = connection,
            onDataReceived = {},
            onConnectionStateChanged = {},
            onError = {},
        )

        client.connect()

        val firmwareInfo = assertNotNull(client.getFirmwareInfoCached())
        assertEquals("speeduino 202501", firmwareInfo.signature)
        assertEquals("ByRocha1", firmwareInfo.productString)
        assertEquals(listOf('Q', 'S', 'Q', 'S', 'S', 'p'), connection.sentCommands)
    }

    @Test
    fun connectRetriesLegacyHandshakeAfterUnreadableCandidate() = runBlocking {
        val connection = FakeConnection(
            responses = mapOf(
                'Q'.code.toByte() to arrayDequeOf(
                    byteArrayOf(0xFF.toByte()),
                    "speeduino202501_BR1?".toByteArray(Charsets.US_ASCII),
                    "eeduino202501_BR1G".toByteArray(Charsets.US_ASCII),
                ),
                'S'.code.toByte() to arrayDequeOf(
                    byteArrayOf(0xFE.toByte()),
                    "ByRocha1\u0000".toByteArray(Charsets.US_ASCII),
                ),
                'p'.code.toByte() to arrayDequeOf(ByteArray(16)),
            )
        )

        val client = SpeeduinoClient(
            connection = connection,
            onDataReceived = {},
            onConnectionStateChanged = {},
            onError = {},
        )

        client.connect()

        val firmwareInfo = assertNotNull(client.getFirmwareInfoCached())
        assertEquals("speeduino 202501", firmwareInfo.signature)
        // The handshake makes 3 attempts (legacyFirmwareHandshakeAttempts=2, floored to 3)
        // and drains the input buffer at least twice per attempt via
        // drainInputBeforeFirmwareHandshake(). Additional defensive drains around each
        // ASCII candidate query are allowed, so assert the per-attempt-drain floor rather
        // than an exact count that drifts whenever a defensive drain is added.
        assertTrue(connection.clearInputBufferCalls >= 2 * 3)
    }

    @Test
    fun connectLogsRawLegacyHandshakeCandidatesToConnectionTrace() = runBlocking {
        val infos = mutableListOf<String>()
        val previousEnabled = ConnectionTrace.enabled
        val previousSink = ConnectionTrace.sink
        ConnectionTrace.enabled = true
        ConnectionTrace.sink = object : ConnectionTraceSink {
            override fun onTx(transport: String, data: ByteArray) = Unit
            override fun onRx(transport: String, data: ByteArray) = Unit
            override fun onError(transport: String, message: String, throwable: Throwable?) = Unit
            override fun onInfo(transport: String, message: String) {
                infos += "$transport|$message"
            }
        }

        try {
            val connection = FakeConnection(
                responses = mapOf(
                    'Q'.code.toByte() to arrayDequeOf(
                        byteArrayOf(0x00, 0x73, 0x70, 0x65, 0x65, 0x64, 0x75, 0x69, 0x6E, 0x6F, 0x20, 0x32, 0x30, 0x32, 0x35, 0x30, 0x31),
                    ),
                    'S'.code.toByte() to arrayDequeOf(
                        "ByRocha1\u0000".toByteArray(Charsets.US_ASCII),
                    ),
                    'p'.code.toByte() to arrayDequeOf(ByteArray(16)),
                )
            )

            val client = SpeeduinoClient(
                connection = connection,
                onDataReceived = {},
                onConnectionStateChanged = {},
                onError = {},
            )

            client.connect()

            assertTrue(infos.any { it.contains("legacy_handshake cmd=Q label=firmware info") && it.contains("raw=00 73 70 65 65 64 75 69 6E 6F") })
            assertTrue(infos.any { it.contains("firmware_handshake attempt=1/3") })
        } finally {
            ConnectionTrace.enabled = previousEnabled
            ConnectionTrace.sink = previousSink
        }
    }

    @Test
    fun connectRejectsUnreadableFirmwareNoiseWithoutInventingSignature() = runBlocking {
        val connection = FakeConnection(
            responses = mapOf(
                'Q'.code.toByte() to arrayDequeOf(
                    "h b% 2 h b h ! 0 h T Z h b% i h b h h T Z".toByteArray(Charsets.US_ASCII),
                    "h b% 2 h b h ! 0 h S Z h b% i h b h h R Z".toByteArray(Charsets.US_ASCII),
                ),
                'S'.code.toByte() to arrayDequeOf(
                    "h b% 3 h b h ! 0 h T Z h b% i h c h h T Z".toByteArray(Charsets.US_ASCII),
                    "h b% 2 h b h ! 0 h R Z h b% i h b h".toByteArray(Charsets.US_ASCII),
                ),
            )
        )

        val client = SpeeduinoClient(
            connection = connection,
            onDataReceived = {},
            onConnectionStateChanged = {},
            onError = {},
        )

        val error = assertFailsWith<UnsupportedFirmwareException> {
            client.connect()
        }

        assertTrue(error.message.orEmpty().contains("no readable legacy candidates"))
    }

    private class FakeConnection(
        responses: Map<Byte, ArrayDeque<ByteArray>>
    ) : ISpeeduinoConnection {
        private val queuedResponses = responses
            .mapValues { (_, queue) -> ArrayDeque(queue) }
            .toMutableMap()
        private var connected = false
        private var lastCommand: Byte? = null
        private val pendingChunks = ArrayDeque<ByteArray>()

        val sentCommands = mutableListOf<Char>()
        var clearInputBufferCalls = 0
            private set

        override suspend fun connect() {
            connected = true
        }

        override fun disconnect() {
            connected = false
        }

        override fun send(data: ByteArray) {
            val command = ModernEnvelopeFake.commandOf(data)
            lastCommand = command
            command?.let { sentCommands += it.toInt().toChar() }
            pendingChunks.clear()
            // 202501 usa envelope, então o cliente enquadra a leitura de página. O fake precisa
            // responder no mesmo formato, senão testa um protocolo que a ECU real não fala.
            if (command != null && ModernEnvelopeFake.isModernFrame(data)) {
                pendingChunks += ModernEnvelopeFake.framedResponse(nextResponseFor(command))
            }
        }

        override fun receive(size: Int): ByteArray {
            pendingChunks.removeFirstOrNull()?.let { return it }
            val command = lastCommand ?: error("receive() without previous send()")
            return nextResponseFor(command)
        }

        private fun nextResponseFor(command: Byte): ByteArray {
            val queue = queuedResponses[command] ?: ArrayDeque()
            val response = queue.removeFirstOrNull() ?: ByteArray(0)
            queuedResponses[command] = queue
            return response
        }

        override fun isConnected(): Boolean = connected

        override fun getConnectionInfo(): String = "fake"

        override fun supportsModernProtocol(): Boolean = false

        override fun supportsModernProtocolFallback(): Boolean = false

        override fun prefersLegacyProtocol(): Boolean = true

        override fun legacyFirmwareHandshakeAttempts(): Int = 2

        override fun setOnConnectionStateChanged(callback: (Boolean) -> Unit) = Unit

        override fun setOnError(callback: (String) -> Unit) = Unit

        override fun clearInputBuffer() {
            clearInputBufferCalls += 1
        }
    }

    private fun arrayDequeOf(vararg values: ByteArray): ArrayDeque<ByteArray> = ArrayDeque(values.toList())
}
