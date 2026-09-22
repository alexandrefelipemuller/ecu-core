package io.ecucore.protocol

import io.ecucore.connection.ISpeeduinoConnection
import io.ecucore.model.FirmwareEra
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regressão de campo 2026-09 (Speeduino 202310, Bluetooth e USB): com o dashboard rodando, o live
 * data vai em envelope ('r' 0x30) e o firmware trava os comandos legacy até ser desligado
 * (speeduino/comms.cpp, serialReceive: `BIT_CLEAR(status4, BIT_STATUS4_ALLOW_LEGACY_COMMS)` após
 * o 1º frame com CRC válido). O download de páginas disparado depois mandava 'p' cru -> a ECU lia
 * `70 00` como tamanho de envelope e só devolvia timeout (`00 01 80 <CRC32>`, 7 bytes):
 * "Timeout: expected 256 bytes, received 7" em todas as páginas. Com 202501 não acontecia porque
 * ali o config read já é moderno.
 *
 * [FakeSpeeduino202310] reproduz essa máquina de estados do firmware.
 */
class SpeeduinoProtocolLegacyLockoutTest {

    @Test
    fun `before any enveloped command page read stays legacy on a legacy-first transport`() = runBlocking {
        val ecu = FakeSpeeduino202310()
        val protocol = modern2023Session(ecu)

        val data = protocol.readPage(pageNum = 15, offset = 0, length = 16)

        assertContentEquals(FakeSpeeduino202310.pageBytes(15, 0, 16), data)
        assertEquals(listOf("raw p"), ecu.log, "download inicial continua legacy (comportamento validado em bench)")
        assertFalse(protocol.isLegacyCommsLockedOut())
    }

    @Test
    fun `after enveloped live data page reads writes and burn go enveloped`() = runBlocking {
        val ecu = FakeSpeeduino202310()
        val protocol = modern2023Session(ecu)

        protocol.readLiveDataModern(125)
        assertTrue(ecu.legacyLockedOut, "pré-condição: firmware travou legacy ao processar o envelope")
        assertTrue(protocol.isLegacyCommsLockedOut())

        val page = protocol.readPage(pageNum = 15, offset = 0, length = 16)
        protocol.writePage(pageNum = 15, offset = 0, data = ByteArray(4) { 0x11 })
        protocol.burnConfig()

        assertContentEquals(FakeSpeeduino202310.pageBytes(15, 0, 16), page)
        assertEquals(
            listOf("envelope r", "envelope p", "envelope M", "envelope B"),
            ecu.log,
            "nenhum comando cru depois do lockout",
        )
    }

    @Test
    fun `envelope rejected with CRC error does not count as legacy lockout`() = runBlocking {
        val ecu = FakeSpeeduino202310(rejectEnvelopesWithCrcError = true)
        val protocol = modern2023Session(ecu)

        runCatching { protocol.readLiveDataModern(125) }
        val data = protocol.readPage(pageNum = 15, offset = 0, length = 16)

        assertFalse(protocol.isLegacyCommsLockedOut())
        assertContentEquals(FakeSpeeduino202310.pageBytes(15, 0, 16), data)
        assertEquals(listOf("envelope r", "raw p"), ecu.log)
    }

    @Test
    fun `resetLegacyCommsLockout returns page reads to legacy for the next session`() = runBlocking {
        val ecu = FakeSpeeduino202310()
        val protocol = modern2023Session(ecu)
        protocol.readLiveDataModern(125)

        protocol.resetLegacyCommsLockout()
        ecu.powerCycle()
        protocol.readPage(pageNum = 15, offset = 0, length = 16)

        assertEquals(listOf("envelope r", "raw p"), ecu.log)
    }

    private suspend fun modern2023Session(ecu: FakeSpeeduino202310): SpeeduinoProtocol {
        ecu.connect()
        return SpeeduinoProtocol(ecu).apply {
            // O que o SpeeduinoClient configura após handshake de um 202310.
            setSessionModernEnvelope(true)
            setSessionFirmwareEra(FirmwareEra.MODERN_2023)
        }
    }

    /**
     * Transporte legacy-first (Bluetooth) ligado a um Speeduino 202310: aceita 'p'/'M'/'B' crus
     * enquanto legacy está liberado; o primeiro envelope processado trava legacy, e a partir daí
     * todo comando cru vira tamanho de envelope e só devolve o frame de timeout de 7 bytes.
     */
    private class FakeSpeeduino202310(
        private val rejectEnvelopesWithCrcError: Boolean = false,
    ) : ISpeeduinoConnection {
        private var connected = false
        private val pending = ArrayDeque<ByteArray>()

        var legacyLockedOut = false
            private set
        val log = mutableListOf<String>()

        fun powerCycle() {
            legacyLockedOut = false
        }

        override suspend fun connect() {
            connected = true
        }

        override fun disconnect() {
            connected = false
        }

        override fun send(data: ByteArray) {
            pending.clear()
            if (isEnvelope(data)) {
                val payload = data.copyOfRange(2, data.size - 4)
                val cmd = payload[0].toInt().toChar()
                log += "envelope $cmd"
                if (rejectEnvelopesWithCrcError) {
                    pending += framed(byteArrayOf(0x82.toByte()))
                    return
                }
                legacyLockedOut = true
                pending += when (cmd) {
                    'r' -> framed(byteArrayOf(0x00) + ByteArray(u16le(payload, 5)) { 0x22 })
                    'p' -> framed(byteArrayOf(0x00) + pageBytes(payload[2].toInt(), u16le(payload, 3), u16le(payload, 5)))
                    'M' -> framed(byteArrayOf(0x00))
                    'B' -> framed(byteArrayOf(0x04))
                    else -> framed(byteArrayOf(0x83.toByte()))
                }
                return
            }
            val cmd = data[0].toInt().toChar()
            log += "raw $cmd"
            if (legacyLockedOut) {
                // `70 00` lido como tamanho 28672: o frame nunca completa -> SERIAL_RC_TIMEOUT.
                pending += framed(byteArrayOf(0x80.toByte())).reduce { acc, bytes -> acc + bytes }
                return
            }
            if (cmd == 'p') {
                pending += pageBytes(data[2].toInt(), u16le(data, 3), u16le(data, 5))
            }
        }

        override fun receive(size: Int): ByteArray = pending.removeFirstOrNull() ?: ByteArray(0)

        override fun isConnected(): Boolean = connected

        override fun getConnectionInfo(): String = "bt:00:11:22:33:44:55"

        override fun supportsModernProtocol(): Boolean = false

        override fun supportsModernProtocolFallback(): Boolean = true

        override fun supportsModernConfigReads(): Boolean = false

        override fun prefersLegacyProtocol(): Boolean = true

        override fun setOnConnectionStateChanged(callback: (Boolean) -> Unit) = Unit

        override fun setOnError(callback: (String) -> Unit) = Unit

        override fun clearInputBuffer() = Unit

        private fun isEnvelope(data: ByteArray): Boolean {
            if (data.size < 7) return false
            val declared = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            return declared == data.size - 6
        }

        private fun u16le(bytes: ByteArray, index: Int): Int =
            (bytes[index].toInt() and 0xFF) or ((bytes[index + 1].toInt() and 0xFF) shl 8)

        /** Resposta em três leituras, na ordem de readModernResponse; CRC 0 = validação pulada. */
        private fun framed(body: ByteArray): List<ByteArray> =
            listOf(byteArrayOf(((body.size shr 8) and 0xFF).toByte(), (body.size and 0xFF).toByte()), body, ByteArray(4))

        companion object {
            fun pageBytes(page: Int, offset: Int, length: Int): ByteArray =
                ByteArray(length) { (page * 16 + offset + it).toByte() }
        }
    }
}
