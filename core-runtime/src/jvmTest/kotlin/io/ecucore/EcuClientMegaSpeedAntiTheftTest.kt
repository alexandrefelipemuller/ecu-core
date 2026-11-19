package io.ecucore

import io.ecucore.definition.IniParser
import io.ecucore.model.EcuAntiTheft
import io.ecucore.model.MegaSpeedAntiTheft
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Antifurto: limitador de giro em 0 RPM com corte de combustível (tabela 4, offsets 636/656) trava a partida. */
class EcuClientMegaSpeedAntiTheftTest {

    private val d = '$'

    private fun ini(revLimitLine: String = """RevLimNormal2 = scalar, S16, 656, "RPM", 1, 0, 0, 15000, 0""") = """
        [MegaTune]
        signature = "MS2Extra MegaSpeed "

        [Constants]
        endianness = big
        nPages = 7
        pageSize = 1024,1024,1024,1024,1024,1024,1024
        pageIdentifier = "${d}tsCanId\x04","${d}tsCanId\x05","${d}tsCanId\x0a","${d}tsCanId\x08","${d}tsCanId\x09","${d}tsCanId\x0b","${d}tsCanId\x0c"
        ochBlockSize = 219

        page = 1
        RevLimCLTbased = bits, U08, 636, [3:3], "Desligado", "Ligado"
        RevLimOption_fuelprog = bits, U08, 636, [4:4], "Desligado", "Ligado"
        RevLimOption_fuelcut = bits, U08, 636, [5:5], "Desligado", "Ligado"
        $revLimitLine
        can_poll_id3 = scalar, U08, 720, "", 1, 0, 0, 14, 0
        hw_latency = scalar, U16, 730, "usec", 0.66667, 0, 0, 500, 0
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

    private fun ClientFakeEcu.revLimit(rpm: Int) = load(0x04, 656, MegaSpeedAntiTheft.encode(rpm))

    private fun ClientFakeEcu.revLimit() = MegaSpeedAntiTheft.decode(mem(0x04), 656)

    private fun ClientFakeEcu.options() = mem(0x04)[636].toInt() and 0xFF

    private fun ClientFakeEcu.marker() = mem(0x04)[721].toInt() and 0xFF

    private fun ClientFakeEcu.storedRevLimit() = MegaSpeedAntiTheft.decode(mem(0x04), 722) and 0xFFFF

    /** CLTbased (bit 3) ligado, fuelcut (bit 5) desligado, bits 0 e 2 do usuário. */
    private val userOptions = 0b0000_1101
    private val userRevLimit = 7200

    private fun ClientFakeEcu.userConfig() {
        revLimit(userRevLimit)
        mem(0x04)[636] = userOptions.toByte()
    }

    @Test
    fun `travar zera o limite, forca o corte de combustivel e guarda os originais`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        ecu.userConfig()

        val raw = c.lockAntiTheft()

        assertEquals(MegaSpeedAntiTheft.Original(userRevLimit, cltBased = 1, fuelProg = 0, fuelCut = 0).toRaw(), raw)
        assertEquals(0, ecu.revLimit())
        // CLTbased e fuelprog desligados, fuelcut ligado; bits 0 e 2 do usuário preservados.
        assertEquals(0b0010_0101, ecu.options())
        assertTrue(MegaSpeedAntiTheft.isLocked(ecu.marker()))
        assertEquals(userRevLimit, ecu.storedRevLimit())
        // O limite em 0 é a última gravação: antes dele a ECU ainda não está cortando.
        assertEquals(listOf(721, 636, 656), ecu.writes.map { it.offset })
        assertTrue(ecu.burns.isNotEmpty() && ecu.burns.all { it == 0x04 })
        assertTrue(c.readAntiTheftLocked())
    }

    @Test
    fun `destravar sem original salvo usa o guardado na trava`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        ecu.userConfig()
        c.lockAntiTheft()

        c.unlockAntiTheft()

        assertEquals(userRevLimit, ecu.revLimit())
        assertEquals(userOptions, ecu.options())
        assertEquals(0, ecu.marker())
        assertFalse(c.readAntiTheftLocked())
        // Assinatura apagada por último: interrompido antes dela, a ECU continua travada.
        assertEquals(721, ecu.writes.last().offset)
    }

    @Test
    fun `destravar com original do celular usa esse valor`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        ecu.userConfig()
        c.lockAntiTheft()

        c.unlockAntiTheft(originalRaw = MegaSpeedAntiTheft.Original(6500, cltBased = 0, fuelProg = 1, fuelCut = 1).toRaw())

        assertEquals(6500, ecu.revLimit())
        // CLTbased 0, fuelprog 1, fuelcut 1 (bits 3..5) + bits 0 e 2 do usuário.
        assertEquals(0b0011_0101, ecu.options())
    }

    @Test
    fun `destravar ecu ja destravada nao grava`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        ecu.userConfig()
        c.unlockAntiTheft()
        assertTrue(ecu.writes.isEmpty())
        assertTrue(ecu.burns.isEmpty())
    }

    @Test
    fun `trava gravada pela metade continua travada e destrava`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        ecu.userConfig()
        c.lockAntiTheft()
        // Falha depois da assinatura e antes do limite: simula voltando o limite ao original.
        ecu.revLimit(userRevLimit)

        assertTrue(c.readAntiTheftLocked())
        c.unlockAntiTheft()
        assertFalse(c.readAntiTheftLocked())
        assertEquals(userRevLimit, ecu.revLimit())
        assertEquals(userOptions, ecu.options())
    }

    @Test
    fun `motor girando ou ecu ja travada nao trava`() = runBlocking<Unit> {
        val (running, ecuRunning) = connect(rpm = 850)
        ecuRunning.userConfig()
        assertFailsWith<IllegalStateException> { running.lockAntiTheft() }
        assertTrue(ecuRunning.writes.isEmpty())

        val (c, ecu) = connect()
        ecu.userConfig()
        c.lockAntiTheft()
        ecu.writes.clear()
        assertFailsWith<IllegalStateException> { c.lockAntiTheft() }
        assertTrue(ecu.writes.isEmpty())
    }

    @Test
    fun `limite ja em zero nao trava`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        ecu.revLimit(0)
        assertFailsWith<IllegalArgumentException> { c.lockAntiTheft() }
        assertTrue(ecu.writes.isEmpty())
    }

    @Test
    fun `ini com limite em outro lugar recusa`() = runBlocking<Unit> {
        val (c, ecu) = connect(iniText = ini("""RevLimNormal2 = scalar, S16, 660, "RPM", 1, 0, 0, 15000, 0"""))
        assertFailsWith<UnsupportedOperationException> { c.lockAntiTheft() }
        assertFailsWith<UnsupportedOperationException> { c.readAntiTheftLocked() }
        assertTrue(ecu.writes.isEmpty())
    }

    @Test
    fun `ini sem os vizinhos do espaco livre recusa`() = runBlocking<Unit> {
        val (c, ecu) = connect(iniText = ini().replace("hw_latency = scalar, U16, 730", "hw_latency = scalar, U16, 725"))
        assertFailsWith<UnsupportedOperationException> { c.lockAntiTheft() }
        assertTrue(ecu.writes.isEmpty())
    }

    @Test
    fun `writeGuard recebe a operacao do antifurto`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        ecu.userConfig()
        val ops = mutableListOf<String>()
        c.writeGuard = { op -> ops += op; op != EcuAntiTheft.OP_LOCK }
        assertFailsWith<WriteNotPermittedException> { c.lockAntiTheft() }
        assertEquals(listOf(EcuAntiTheft.OP_LOCK), ops)
        assertTrue(ecu.writes.isEmpty())
    }

    @Test
    fun `fora de MegaSpeed recusa`() = runBlocking<Unit> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.MS2)
        val client = EcuClient(ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        client.connect()
        assertFailsWith<UnsupportedOperationException> { client.readAntiTheftLocked() }
        assertFailsWith<UnsupportedOperationException> { client.lockAntiTheft() }
    }
}
