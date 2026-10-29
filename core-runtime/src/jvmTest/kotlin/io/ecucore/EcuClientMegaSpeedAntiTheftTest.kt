package io.ecucore

import io.ecucore.definition.IniParser
import io.ecucore.model.MegaSpeedAntiTheft
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Antifurto: `floodClear` (tabela 4, offset 588) negativo trava a partida. */
class EcuClientMegaSpeedAntiTheftTest {

    private val d = '$'

    private fun ini(floodClearLine: String = """floodClear = scalar, S16, 588, "%", 0.10000, 0.00000, 0.00, 100.00, 1""") = """
        [MegaTune]
        signature = "MS2Extra MegaSpeed "

        [Constants]
        endianness = big
        nPages = 7
        pageSize = 1024,1024,1024,1024,1024,1024,1024
        pageIdentifier = "${d}tsCanId\x04","${d}tsCanId\x05","${d}tsCanId\x0a","${d}tsCanId\x08","${d}tsCanId\x09","${d}tsCanId\x0b","${d}tsCanId\x0c"
        ochBlockSize = 219

        page = 1
        $floodClearLine
    """.trimIndent()

    private suspend fun connect(rpm: Int = 0, iniText: String = ini()): Pair<EcuClient, ClientFakeEcu> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.MEGASPEED)
        // rpm no offset 6 (U16 BE) do bloco de saída de 219 bytes.
        ecu.liveBytes = ByteArray(219).also {
            it[6] = (rpm shr 8).toByte()
            it[7] = rpm.toByte()
        }
        val client = EcuClient(ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        client.connect()
        client.applyIniDefinition(IniParser.parse("megaspeed.ini", iniText))
        return client to ecu
    }

    private fun ClientFakeEcu.floodClear(raw: Int) = load(0x04, 588, MegaSpeedAntiTheft.encode(raw))

    @Test
    fun `travar grava o valor negativo na tabela 4 e queima`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        ecu.floodClear(700) // 70,0 %

        assertEquals(700, c.lockMegaSpeedAntiTheft())

        val w = ecu.writes.single()
        assertEquals(0x04, w.id)
        assertEquals(588, w.offset)
        assertEquals(listOf(0xFD, 0x43), w.data.map { it.toInt() and 0xFF }) // -701
        assertEquals(listOf(0x04), ecu.burns)
        assertEquals(-701, c.readMegaSpeedAntiTheftRaw())
    }

    @Test
    fun `destravar sem original salvo usa o guardado na trava`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        ecu.floodClear(-701)

        c.unlockMegaSpeedAntiTheft()

        assertEquals(700, c.readMegaSpeedAntiTheftRaw())
        assertEquals(listOf(0x04), ecu.burns)
    }

    @Test
    fun `destravar com original do celular usa esse valor`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        ecu.floodClear(-701)
        c.unlockMegaSpeedAntiTheft(originalRaw = 650)
        assertEquals(650, c.readMegaSpeedAntiTheftRaw())
    }

    @Test
    fun `destravar ecu ja destravada nao grava`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        ecu.floodClear(700)
        c.unlockMegaSpeedAntiTheft()
        assertTrue(ecu.writes.isEmpty())
        assertTrue(ecu.burns.isEmpty())
    }

    @Test
    fun `motor girando ou ecu ja travada nao trava`() = runBlocking<Unit> {
        val (running, ecuRunning) = connect(rpm = 850)
        ecuRunning.floodClear(700)
        assertFailsWith<IllegalStateException> { running.lockMegaSpeedAntiTheft() }
        assertTrue(ecuRunning.writes.isEmpty())

        val (c, ecu) = connect()
        ecu.floodClear(-701)
        assertFailsWith<IllegalStateException> { c.lockMegaSpeedAntiTheft() }
        assertTrue(ecu.writes.isEmpty())
    }

    @Test
    fun `ini com floodClear em outro lugar recusa`() = runBlocking<Unit> {
        val (c, ecu) = connect(iniText = ini("""floodClear = scalar, S16, 590, "%", 0.10000, 0.00000, 0.00, 100.00, 1"""))
        assertFailsWith<UnsupportedOperationException> { c.lockMegaSpeedAntiTheft() }
        assertTrue(ecu.writes.isEmpty())
    }

    @Test
    fun `writeGuard recebe a operacao do antifurto`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        ecu.floodClear(700)
        val ops = mutableListOf<String>()
        c.writeGuard = { op -> ops += op; op != MegaSpeedAntiTheft.OP_LOCK }
        assertFailsWith<WriteNotPermittedException> { c.lockMegaSpeedAntiTheft() }
        assertEquals(listOf(MegaSpeedAntiTheft.OP_LOCK), ops)
        assertTrue(ecu.writes.isEmpty())
    }

    @Test
    fun `fora de MegaSpeed recusa`() = runBlocking<Unit> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.MS2)
        val client = EcuClient(ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        client.connect()
        assertFailsWith<UnsupportedOperationException> { client.readMegaSpeedAntiTheftRaw() }
        assertFailsWith<UnsupportedOperationException> { client.lockMegaSpeedAntiTheft() }
    }
}
