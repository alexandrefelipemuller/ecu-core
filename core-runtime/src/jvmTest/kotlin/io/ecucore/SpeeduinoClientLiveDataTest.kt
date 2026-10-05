package io.ecucore

import io.ecucore.model.EcuFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** readLiveData em todas as famílias/eras e o stream contínuo. */
class SpeeduinoClientLiveDataTest {

    private val errorFrame = listOf(byteArrayOf(0x00, 0x01), byteArrayOf(0x84.toByte()), ByteArray(4))

    private fun client(
        ecu: ClientFakeEcu,
        received: MutableList<SpeeduinoLiveData> = mutableListOf(),
        errors: MutableList<String> = mutableListOf(),
    ) = SpeeduinoClient(ecu, onDataReceived = { synchronized(received) { received += it } }, onConnectionStateChanged = {}, onError = { synchronized(errors) { errors += it } })

    private suspend fun connect(
        kind: ClientFakeEcu.Kind,
        prefersLegacy: Boolean = true,
        modernProtocol: Boolean = false,
        received: MutableList<SpeeduinoLiveData> = mutableListOf(),
        errors: MutableList<String> = mutableListOf(),
    ): Pair<SpeeduinoClient, ClientFakeEcu> {
        val ecu = ClientFakeEcu(kind, prefersLegacy = prefersLegacy, modernProtocol = modernProtocol)
        return client(ecu, received, errors).also { it.connect() } to ecu
    }

    private suspend fun waitFor(timeoutMs: Long = 5000, condition: () -> Boolean) {
        withTimeout(timeoutMs) { while (!condition()) delay(10) }
    }

    // ---- readLiveData -----------------------------------------------------------------------------

    @Test
    fun `modern envelope era reads live data over r`() = runBlocking<Unit> {
        val (c, ecu) = connect(ClientFakeEcu.Kind.SPEEDUINO_2025)
        assertNotNull(c.readLiveData())
        assertTrue(ecu.commands.contains('r'))
        assertFalse(ecu.commands.takeLast(3).contains('A'))
    }

    @Test
    fun `modern envelope era never falls back to legacy`() = runBlocking<Unit> {
        val (c, ecu) = connect(ClientFakeEcu.Kind.SPEEDUINO_2025)
        ecu.onFrame = { cmd, _ -> if (cmd == 'r') errorFrame else null }
        assertFailsWith<Exception> { c.readLiveData() }
        assertFalse(ecu.commands.takeLast(4).contains('A'))
        Unit
    }

    @Test
    fun `lost connection during live data read is reported`() = runBlocking<Unit> {
        val (c, ecu) = connect(ClientFakeEcu.Kind.SPEEDUINO_2025)
        ecu.onFrame = { cmd, _ -> if (cmd == 'r') { ecu.connected = false; errorFrame } else null }
        assertTrue(assertFailsWith<Exception> { c.readLiveData() }.message!!.contains("Não conectado"))
    }

    @Test
    fun `read aborted by stop request is not retried`() = runBlocking<Unit> {
        val (c, ecu) = connect(ClientFakeEcu.Kind.SPEEDUINO_2025)
        var calls = 0
        ecu.onFrame = { cmd, _ -> if (cmd == 'r') { calls++; errorFrame } else null }
        c.stopLiveDataStream()
        assertFailsWith<Exception> { c.readLiveData() }
        assertEquals(1, calls)
    }

    @Test
    fun `modern era without envelope prefers legacy first on legacy-first transports`() = runBlocking<Unit> {
        val (c, ecu) = connect(ClientFakeEcu.Kind.SPEEDUINO_2020)
        val size = c.getTableDefinitions()!!.ochBlockSize
        ecu.onLegacy = { cmd, _ -> if (cmd == 'A') ByteArray(size) else null }
        assertNotNull(c.readLiveData()) // quadro do tamanho do OCH -> parser modern
        ecu.onLegacy = null
        assertNotNull(c.readLiveData()) // tamanho diferente -> oc_mismatch + parser legacy
    }

    @Test
    fun `legacy-first failure falls back to modern when transport allows`() = runBlocking<Unit> {
        val (c, ecu) = connect(ClientFakeEcu.Kind.SPEEDUINO_2020, modernProtocol = true)
        ecu.onLegacy = { cmd, _ -> if (cmd == 'A') throw RuntimeException("boom") else null }
        assertNotNull(c.readLiveData())
        assertTrue(ecu.commands.contains('r'))
    }

    @Test
    fun `legacy-first failure with unusable modern fallback reports both errors`() = runBlocking<Unit> {
        val (c, ecu) = connect(ClientFakeEcu.Kind.SPEEDUINO_2020)
        ecu.onLegacy = { cmd, _ -> if (cmd == 'A') throw RuntimeException("boom") else null }
        val message = assertFailsWith<Exception> { c.readLiveData() }.message!!
        assertTrue(message.contains("modern fallback também falhou"), message)
    }

    @Test
    fun `legacy-first recoverable errors and lost link are rethrown`() = runBlocking<Unit> {
        val (c, ecu) = connect(ClientFakeEcu.Kind.SPEEDUINO_2020, modernProtocol = true)
        ecu.onLegacy = { cmd, _ -> if (cmd == 'A') ByteArray(0) else null } // resposta incompleta: recuperável
        assertTrue(assertFailsWith<Exception> { c.readLiveData() }.message!!.contains("incompleta"))
        ecu.onLegacy = { cmd, _ -> if (cmd == 'A') { ecu.connected = false; throw RuntimeException("boom") } else null }
        assertTrue(assertFailsWith<Exception> { c.readLiveData() }.message!!.contains("Não conectado"))
    }

    @Test
    fun `legacy-first read aborted is rethrown`() = runBlocking<Unit> {
        val (c, ecu) = connect(ClientFakeEcu.Kind.SPEEDUINO_2020, modernProtocol = true)
        ecu.onLegacy = { cmd, _ -> if (cmd == 'A') throw RuntimeException("Read aborted") else null }
        assertTrue(assertFailsWith<Exception> { c.readLiveData() }.message!!.contains("Read aborted"))
    }

    @Test
    fun `modern-first transport falls back to legacy after modern failures`() = runBlocking<Unit> {
        val (c, ecu) = connect(ClientFakeEcu.Kind.SPEEDUINO_2020, prefersLegacy = false, modernProtocol = true)
        ecu.onFrame = { cmd, _ -> if (cmd == 'r') errorFrame else null }
        assertNotNull(c.readLiveData())
        assertTrue(ecu.commands.takeLast(3).contains('A'))
        // e quando o modern funciona, fica no modern
        ecu.onFrame = null
        assertNotNull(c.readLiveData())
    }

    @Test
    fun `modern-first lost link and aborted reads are rethrown`() = runBlocking<Unit> {
        val (c, ecu) = connect(ClientFakeEcu.Kind.SPEEDUINO_2020, prefersLegacy = false, modernProtocol = true)
        ecu.onFrame = { cmd, _ -> if (cmd == 'r') { ecu.connected = false; errorFrame } else null }
        assertTrue(assertFailsWith<Exception> { c.readLiveData() }.message!!.contains("Não conectado"))
        ecu.connected = true
        c.stopLiveDataStream()
        ecu.onFrame = { cmd, _ -> if (cmd == 'r') errorFrame else null }
        assertFailsWith<Exception> { c.readLiveData() }
        Unit
    }

    @Test
    fun `legacy era reads a plain A frame`() = runBlocking<Unit> {
        val (c, ecu) = connect(ClientFakeEcu.Kind.SPEEDUINO_LEGACY)
        assertNotNull(c.readLiveData())
        assertTrue(ecu.commands.contains('A'))
    }

    @Test
    fun `ms families parse their output channel blocks`() = runBlocking<Unit> {
        for ((kind, size) in listOf(ClientFakeEcu.Kind.MS3 to 509, ClientFakeEcu.Kind.MS2 to 212, ClientFakeEcu.Kind.MEGASPEED to 219)) {
            val (c, ecu) = connect(kind)
            ecu.liveBytes = ByteArray(size)
            assertNotNull(c.readLiveData(), kind.name)
        }
    }

    @Test
    fun `ms1 uses the 22 byte legacy frame`() = runBlocking<Unit> {
        val (c, ecu) = connect(ClientFakeEcu.Kind.MS1)
        ecu.liveBytes = ByteArray(22)
        assertNotNull(c.readLiveData())
    }

    @Test
    fun `rusefi live data comes from O and failures clear the buffer`() = runBlocking<Unit> {
        val (c, ecu) = connect(ClientFakeEcu.Kind.RUSEFI)
        assertNotNull(c.readLiveData())
        assertTrue(ecu.commands.contains('O'))
        ecu.onFrame = { cmd, _ -> if (cmd == 'O') errorFrame else null }
        val before = ecu.clearCalls
        assertFailsWith<Exception> { c.readLiveData() }
        assertTrue(ecu.clearCalls > before)
        ecu.connected = false
        assertFailsWith<Exception> { c.readLiveData() }
        Unit
    }

    @Test
    fun `faulty samples are traced when tracing is on`() = runBlocking<Unit> {
        io.ecucore.connection.ConnectionTrace.enabled = true
        try {
            val (c, ecu) = connect(ClientFakeEcu.Kind.SPEEDUINO_2025)
            // payload absurdo: RPM/MAP/TPS/temperaturas fora de faixa
            ecu.liveBytes = ByteArray(130) { 0xFF.toByte() }
            repeat(3) { c.readLiveData() }
            ecu.liveBytes = ByteArray(130)
            c.readLiveData()
        } finally {
            io.ecucore.connection.ConnectionTrace.enabled = false
        }
    }

    // ---- stream -------------------------------------------------------------------------------------

    @Test
    fun `stream delivers samples and can be paused and stopped`() = runBlocking<Unit> {
        val received = mutableListOf<SpeeduinoLiveData>()
        val (c, _) = connect(ClientFakeEcu.Kind.SPEEDUINO_2025, received = received)
        c.startLiveDataStream(intervalMs = 10)
        assertTrue(c.isStreaming())
        c.startLiveDataStream(intervalMs = 10) // já ativo: ignorado
        waitFor { synchronized(received) { received.size >= 3 } }
        c.pauseLiveDataStream(timeoutMs = 2000)
        assertFalse(c.isStreaming())
        c.pauseLiveDataStream(timeoutMs = 2000) // sem stream: no-op
        c.startLiveDataStream(intervalMs = 10)
        waitFor { c.isStreaming() }
        c.stopLiveDataStream()
        assertFalse(c.isStreaming())
    }

    @Test
    fun `stream survives recoverable timeouts then reports persistent failure`() = runBlocking<Unit> {
        val errors = mutableListOf<String>()
        val (c, ecu) = connect(ClientFakeEcu.Kind.SPEEDUINO_2025, errors = errors)
        ecu.onFrame = { cmd, _ -> if (cmd == 'r') listOf(byteArrayOf(0x00, 0x01), byteArrayOf(0x80.toByte()), ByteArray(4)) else null }
        c.startLiveDataStream(intervalMs = 5)
        waitFor(20_000) { synchronized(errors) { errors.isNotEmpty() } }
        assertTrue(errors.first().startsWith("Erro no stream"))
        waitFor { !c.isStreaming() }
    }

    @Test
    fun `stream reports unrecoverable protocol errors`() = runBlocking<Unit> {
        val errors = mutableListOf<String>()
        val (c, ecu) = connect(ClientFakeEcu.Kind.SPEEDUINO_2025, errors = errors)
        ecu.onFrame = { cmd, _ -> if (cmd == 'r') errorFrame else null }
        c.startLiveDataStream(intervalMs = 5)
        waitFor { synchronized(errors) { errors.isNotEmpty() } }
        waitFor { !c.isStreaming() }
    }

    @Test
    fun `stream is not started for ecus without live data support`() = runBlocking<Unit> {
        val (c, _) = connect(ClientFakeEcu.Kind.SPEEDUINO_2025)
        c.disconnect()
        c.startLiveDataStream(intervalMs = 10) // desconectado: loop termina logo
        waitFor { !c.isStreaming() }
        assertEquals(EcuFamily.UNKNOWN, c.getEcuFamily())
    }

    @Test
    fun `reconnecting cancels a stream left from the previous connection`() = runBlocking<Unit> {
        val (c, ecu) = connect(ClientFakeEcu.Kind.SPEEDUINO_2025)
        c.startLiveDataStream(intervalMs = 10)
        ecu.connected = false
        c.connect()
        assertTrue(c.isConnected())
    }
}
