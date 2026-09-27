package io.ecucore

import io.ecucore.connection.ISpeeduinoConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Loop de live data diante de SERIAL_RC_CRC_ERR (0x82), reproduzindo o trace do Crashlytics
 * (Speeduino 202501 via Bluetooth): o mesmo frame 'r' é aceito e, entre respostas válidas, a
 * ECU devolve 0x82 porque recebeu bytes corrompidos/concatenados com restos de frame.
 */
class SpeeduinoClientLiveStreamLinkDesyncTest {

    @Test
    fun `transient CRC rejection recovers without surfacing an error`() = runBlocking {
        val connection = LiveDataFakeConnection(
            liveDataStatuses = listOf(OK, CRC_ERR, CRC_ERR, CRC_ERR),
            fallbackStatus = OK,
        )
        val harness = Harness(connection)
        harness.client.connect()

        harness.client.startLiveDataStream(10L)
        withTimeout(10_000L) {
            while (harness.samples.get() < 4) delay(20)
        }
        harness.client.stopLiveDataStream()

        assertTrue(harness.errors.isEmpty(), "erros inesperados: ${harness.errors}")
        assertTrue(connection.isConnected())
    }

    @Test
    fun `persistent CRC rejection after healthy stream reports link desync and reconnects`() = runBlocking {
        val connection = LiveDataFakeConnection(
            liveDataStatuses = listOf(OK),
            fallbackStatus = CRC_ERR,
        )
        val harness = Harness(connection)
        harness.client.connect()

        harness.client.startLiveDataStream(10L)
        withTimeout(15_000L) {
            while (harness.errors.isEmpty()) delay(20)
        }

        val error = harness.errors.single()
        assertTrue(SpeeduinoClient.LIVE_DATA_LINK_DESYNC_MARKER in error, error)
        assertTrue("response code = 0x82" in error, error)
        // Força disconnect para o app disparar a reconexão automática em vez de ficar
        // "conectado" sem live data.
        assertFalse(connection.isConnected())
    }

    @Test
    fun `CRC rejection before any valid frame keeps raw error for diagnosis`() = runBlocking {
        val connection = LiveDataFakeConnection(
            liveDataStatuses = emptyList(),
            fallbackStatus = CRC_ERR,
        )
        val harness = Harness(connection)
        harness.client.connect()

        harness.client.startLiveDataStream(10L)
        withTimeout(15_000L) {
            while (harness.errors.isEmpty()) delay(20)
        }

        val error = harness.errors.single()
        // Sem nenhum frame aceito, 0x82 pode ser defeito real de framing do app: não recebe o
        // marcador de link e continua chegando ao Crashlytics como defeito de protocolo.
        assertFalse(SpeeduinoClient.LIVE_DATA_LINK_DESYNC_MARKER in error, error)
        assertEquals("Erro no stream: Erro ao ler live data modern: response code = 0x82", error)
        assertTrue(connection.isConnected())
        harness.client.stopLiveDataStream()
    }

    private class Harness(connection: LiveDataFakeConnection) {
        val samples = AtomicInteger(0)
        val errors = CopyOnWriteArrayList<String>()
        val client = SpeeduinoClient(
            connection = connection,
            onDataReceived = { samples.incrementAndGet() },
            onConnectionStateChanged = {},
            onError = { errors += it },
        )
    }

    /**
     * Handshake igual ao de [SpeeduinoClientFirmwareHandshakeTest] (speeduino 202501) e respostas
     * scriptadas para o comando 'r': cada item é o status code devolvido pela ECU.
     */
    private class LiveDataFakeConnection(
        liveDataStatuses: List<Byte>,
        private val fallbackStatus: Byte,
    ) : ISpeeduinoConnection {
        private val handshakeResponses = mutableMapOf(
            'Q'.code.toByte() to ArrayDeque(listOf(byteArrayOf(0xFF.toByte()), byteArrayOf(0xFF.toByte()))),
            'S'.code.toByte() to ArrayDeque(
                listOf(
                    "@@A speeduino 202501\u0000A??".toByteArray(Charsets.US_ASCII),
                    "speeduino202501_BR1\u0000".toByteArray(Charsets.US_ASCII),
                    "ByRocha1\u0000".toByteArray(Charsets.US_ASCII),
                )
            ),
            'p'.code.toByte() to ArrayDeque(listOf(ByteArray(16))),
        )
        private val liveDataQueue = ArrayDeque(liveDataStatuses)
        private val pendingChunks = ArrayDeque<ByteArray>()
        private var lastCommand: Byte? = null

        @Volatile
        private var connected = false

        override suspend fun connect() {
            connected = true
        }

        override fun disconnect() {
            connected = false
        }

        @Synchronized
        override fun send(data: ByteArray) {
            val command = ModernEnvelopeFake.commandOf(data)
            lastCommand = command
            pendingChunks.clear()
            if (command == null || !ModernEnvelopeFake.isModernFrame(data)) return
            if (command == 'r'.code.toByte()) {
                val requested = (data[7].toInt() and 0xFF) or ((data[8].toInt() and 0xFF) shl 8)
                val status = liveDataQueue.removeFirstOrNull() ?: fallbackStatus
                pendingChunks += if (status == OK) {
                    ModernEnvelopeFake.framedResponse(ByteArray(requested))
                } else {
                    listOf(byteArrayOf(0x00, 0x01), byteArrayOf(status), ByteArray(4))
                }
            } else {
                pendingChunks += ModernEnvelopeFake.framedResponse(nextHandshakeResponse(command))
            }
        }

        @Synchronized
        override fun receive(size: Int): ByteArray {
            pendingChunks.removeFirstOrNull()?.let { return it }
            val command = lastCommand ?: error("receive() without previous send()")
            if (command == 'r'.code.toByte()) return ByteArray(0)
            return nextHandshakeResponse(command)
        }

        private fun nextHandshakeResponse(command: Byte): ByteArray =
            handshakeResponses[command]?.removeFirstOrNull() ?: ByteArray(0)

        override fun isConnected(): Boolean = connected
        override fun getConnectionInfo(): String = "fake"
        override fun supportsModernProtocol(): Boolean = false
        override fun supportsModernProtocolFallback(): Boolean = false
        override fun prefersLegacyProtocol(): Boolean = true
        override fun legacyFirmwareHandshakeAttempts(): Int = 2
        override fun setOnConnectionStateChanged(callback: (Boolean) -> Unit) = Unit
        override fun setOnError(callback: (String) -> Unit) = Unit
        override fun clearInputBuffer() = Unit
    }

    private companion object {
        const val OK: Byte = 0x00
        const val CRC_ERR: Byte = 0x82.toByte()
    }
}
