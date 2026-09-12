package io.ecucore.protocol

import io.ecucore.connection.ISpeeduinoConnection
import io.ecucore.model.FirmwareEra
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Achado de campo via Bluetooth em 2026-09-09: firmware 202501+ (MODERN_2025) removeu o comando
 * legacy 'p' (leitura de página) por completo. Em transportes legacy-first (Bluetooth/USB), a
 * leitura de página cai em legacy por padrão mesmo com a era do firmware já confirmada como
 * moderna no handshake - a ECU não reconhece o 'p' cru e a página trava em timeout.
 *
 * Esses testes travam o comportamento corrigido: com [FirmwareEra.MODERN_2025] confirmado, a
 * leitura de página deve usar o frame moderno (CRC32) mesmo num transporte legacy-first, sem
 * quebrar o caso anterior (eras 202201-202412), onde o legacy 'p' ainda existe no firmware e o
 * bench de 2026-08-08 mostrou que forçar moderno ali trava a ECU.
 */
class SpeeduinoProtocolModern2025ConfigReadTest {

    @Test
    fun `MODERN_2025 confirmed era reads page via modern frame on a legacy-first transport`() = runBlocking {
        val pageData = ByteArray(16) { (it + 1).toByte() }
        val connection = FakeTransport(
            prefersLegacy = true,
            supportsModern = false,
            modernFallback = true,
            responseChunks = arrayOf(
                byteArrayOf(0x00, 0x11),
                byteArrayOf(0x00) + pageData,
                byteArrayOf(0x00, 0x00, 0x00, 0x00),
            )
        )
        connection.connect()
        val protocol = SpeeduinoProtocol(connection)
        protocol.setSessionModernEnvelope(true)
        protocol.setSessionFirmwareEra(FirmwareEra.MODERN_2025)

        val data = protocol.readPage(pageNum = 1, offset = 0, length = 16)

        assertContentEquals(pageData, data)
        val frame = connection.sentPackets.single()
        assertEquals(0x70, frame[2].toInt() and 0xFF, "payload deve começar com 'p' (frame moderno com envelope CRC)")
    }

    @Test
    fun `MODERN_2022 confirmed era still reads page via legacy on a legacy-first transport`() = runBlocking {
        val pageData = ByteArray(16) { (it + 1).toByte() }
        val connection = FakeTransport(
            prefersLegacy = true,
            supportsModern = false,
            modernFallback = true,
            responseChunks = arrayOf(pageData),
        )
        connection.connect()
        val protocol = SpeeduinoProtocol(connection)
        protocol.setSessionModernEnvelope(true)
        protocol.setSessionFirmwareEra(FirmwareEra.MODERN_2022)

        val data = protocol.readPage(pageNum = 1, offset = 0, length = 16)

        assertContentEquals(pageData, data)
        val frame = connection.sentPackets.single()
        assertEquals(0x70, frame[0].toInt() and 0xFF, "payload deve começar com 'p' cru (legacy, sem envelope)")
        assertEquals(7, frame.size, "legacy 'p' cru: cmd + padding + pageNum + offset(2) + length(2)")
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
