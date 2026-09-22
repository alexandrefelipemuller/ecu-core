package io.ecucore

import io.ecucore.connection.ISpeeduinoConnection
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regressão de campo 2026-09-21 (MS2/Extra 3.4.3, "MS2Extra comms342h2", via Bluetooth): o
 * commit 2e2f568 rebaixou o config read de MS2/MegaSpeed/MS3 para LEGACY_PAGE em transportes
 * legacy-first, o que manda o 'p' cru do Speeduino - comando que não existe na MS2. A ECU
 * newserial leu `70 00` como tamanho de envelope e respondeu `00 01 84 <CRC32>` (out of range),
 * o download de páginas nunca terminou e o watchdog live_data_never_started disparou.
 *
 * Trava que a MS2 lê config com 'r' em envelope CRC32 em BT e USB, e nunca manda 'p'. A gravação
 * ('w' + burn 'b') tinha o mesmo defeito de gate: em USB sem fallback moderno ela falhava sem
 * mandar nada pro fio, então também fica travada aqui.
 */
class SpeeduinoClientMs2ConfigTest {

    @Test
    fun `MS2 over Bluetooth reads config pages via enveloped r and never sends raw p`() = runBlocking {
        assertMs2ConfigReadUsesEnvelope(FakeMs2Connection(modernFallback = true))
    }

    @Test
    fun `MS2 over USB without modern fallback reads config pages via enveloped r and never sends raw p`() = runBlocking {
        assertMs2ConfigReadUsesEnvelope(FakeMs2Connection(modernFallback = false))
    }

    @Test
    fun `MS2 over Bluetooth writes and burns config pages via enveloped w and b`() = runBlocking {
        assertMs2ConfigWriteUsesEnvelope(FakeMs2Connection(modernFallback = true))
    }

    @Test
    fun `MS2 over USB without modern fallback writes and burns config pages via enveloped w and b`() = runBlocking {
        assertMs2ConfigWriteUsesEnvelope(FakeMs2Connection(modernFallback = false))
    }

    private suspend fun assertMs2ConfigWriteUsesEnvelope(connection: FakeMs2Connection) {
        val client = SpeeduinoClient(
            connection = connection,
            onDataReceived = {},
            onConnectionStateChanged = {},
            onError = {},
        )
        client.connect()
        val pageData = ByteArray(64) { (0x40 + it).toByte() }

        client.writeRawPage(pageNum = 0x04, data = pageData)

        val write = connection.envelopedTableWrites.single()
        assertContentEquals(
            byteArrayOf('w'.code.toByte(), 0x00, 0x04, 0x00, 0x00, 0x00, 0x40),
            write.copyOf(7),
            "'w' canId table offset(BE) length(BE)",
        )
        assertContentEquals(pageData, write.copyOfRange(7, write.size))
        assertEquals(listOf(0x04), connection.envelopedBurns, "burn 'b' da tabela 0x04 em envelope")
        assertTrue(connection.rawWriteCommands.isEmpty(), "sem comando de gravação cru: ${connection.rawWriteCommands}")
    }

    private suspend fun assertMs2ConfigReadUsesEnvelope(connection: FakeMs2Connection) {
        val client = SpeeduinoClient(
            connection = connection,
            onDataReceived = {},
            onConnectionStateChanged = {},
            onError = {},
        )
        client.connect()
        assertEquals(io.ecucore.model.EcuFamily.MS2, client.getFirmwareInfoCached()?.family)

        val page = client.readFullPage(pageNum = 0x04, pageSize = 1024, blockSize = 256)

        assertEquals(1024, page.size)
        assertContentEquals(ByteArray(1024) { (it and 0xFF).toByte() }, page)
        assertTrue(connection.rawPageCommands == 0, "'p' cru não existe na MS2 e não pode ir pro fio")
        assertEquals(4, connection.envelopedTableReads, "página 0x04 de 1024 bytes = 4 blocos de 256 via 'r'")
    }

    /**
     * MS2/Extra newserial: responde 'Q'/'S' legacy e 'r' em envelope. Qualquer outro frame é
     * tratado como a ECU real faz com um 'p' cru: envelope de erro 0x84.
     */
    private class FakeMs2Connection(private val modernFallback: Boolean) : ISpeeduinoConnection {
        private var connected = false
        private val pendingChunks = ArrayDeque<ByteArray>()

        var rawPageCommands = 0
            private set
        var envelopedTableReads = 0
            private set
        val envelopedTableWrites = mutableListOf<ByteArray>()
        val envelopedBurns = mutableListOf<Int>()
        val rawWriteCommands = mutableListOf<Char>()

        override suspend fun connect() {
            connected = true
        }

        override fun disconnect() {
            connected = false
        }

        override fun send(data: ByteArray) {
            pendingChunks.clear()
            if (ModernEnvelopeFake.isModernFrame(data) && data[2] == 'r'.code.toByte()) {
                envelopedTableReads++
                val offset = ((data[5].toInt() and 0xFF) shl 8) or (data[6].toInt() and 0xFF)
                val length = ((data[7].toInt() and 0xFF) shl 8) or (data[8].toInt() and 0xFF)
                pendingChunks += ModernEnvelopeFake.framedResponse(ByteArray(length) { ((offset + it) and 0xFF).toByte() })
                return
            }
            if (ModernEnvelopeFake.isModernFrame(data) && data[2] == 'w'.code.toByte()) {
                envelopedTableWrites += data.copyOfRange(2, data.size - 4)
                pendingChunks += ModernEnvelopeFake.framedResponse(ByteArray(0))
                return
            }
            if (ModernEnvelopeFake.isModernFrame(data) && data[2] == 'b'.code.toByte()) {
                envelopedBurns += data[4].toInt() and 0xFF
                pendingChunks += ModernEnvelopeFake.framedResponse(ByteArray(0))
                return
            }
            data.firstOrNull()?.toInt()?.toChar()?.takeIf { it in "MWPBbw" }?.let { rawWriteCommands += it }
            when (data.firstOrNull()) {
                'Q'.code.toByte() -> pendingChunks += "MS2Extra comms342h2\u0000".toByteArray(Charsets.US_ASCII)
                'S'.code.toByte() -> pendingChunks +=
                    "MS2/Extra 3.4.3 release  20191126 15:29BST(c)KC/JSM/JB   MS2".toByteArray(Charsets.US_ASCII)
                'p'.code.toByte() -> {
                    rawPageCommands++
                    pendingChunks += listOf(byteArrayOf(0x00, 0x01), byteArrayOf(0x84.toByte()), ByteArray(4))
                }
                else -> pendingChunks += listOf(byteArrayOf(0x00, 0x01), byteArrayOf(0x84.toByte()), ByteArray(4))
            }
        }

        override fun receive(size: Int): ByteArray = pendingChunks.removeFirstOrNull() ?: ByteArray(0)

        override fun isConnected(): Boolean = connected

        override fun getConnectionInfo(): String = "bt:00:11:22:33:44:55"

        override fun supportsModernProtocol(): Boolean = false

        override fun supportsModernProtocolFallback(): Boolean = modernFallback

        override fun supportsModernConfigReads(): Boolean = false

        override fun prefersLegacyProtocol(): Boolean = true

        override fun legacyFirmwareHandshakeAttempts(): Int = 2

        override fun setOnConnectionStateChanged(callback: (Boolean) -> Unit) = Unit

        override fun setOnError(callback: (String) -> Unit) = Unit

        override fun clearInputBuffer() = Unit
    }
}
