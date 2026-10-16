package io.ecucore

import io.ecucore.definition.IniParser
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Gravação de campos da MegaSpeed no formato capturado do TunerStudio:
 * `w 00 <tabela> <off> <len> <dados>` só com os bytes alterados e `b 00 <tabela>` depois.
 */
class EcuClientMegaSpeedFieldWriteTest {

    private val d = '$'

    // Linhas reais de [Constants] do MegaSpeedMS2-X.ini (CostaMegaSpeed).
    private val ini = """
        [MegaTune]
        signature = "MS2Extra MegaSpeed "

        [Constants]
        endianness = big
        nPages = 7
        pageSize = 1024,1024,1024,1024,1024,1024,1024
        pageIdentifier = "${d}tsCanId\x04","${d}tsCanId\x05","${d}tsCanId\x0a","${d}tsCanId\x08","${d}tsCanId\x09","${d}tsCanId\x0b","${d}tsCanId\x0c"
        ochBlockSize = 219

        page = 1
        map0 = scalar, S16, 506, "Kpa", 0.10000, 0.00000, -100.0, 3276.7, 1
        RevLimNormal2 = scalar, S16, 656, "RPM", 1.00000, 0.00000, 0.00, 15000, 0
        IdleCtl = bits, U08, 547, [0:1], "Nenhuma", "Atuador On/Off", "Atuador PWM", "Motor de Passo"
    """.trimIndent()

    private suspend fun connect(): Pair<EcuClient, ClientFakeEcu> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.MEGASPEED)
        val client = EcuClient(ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        client.connect()
        client.applyIniDefinition(IniParser.parse("megaspeed.ini", ini))
        return client to ecu
    }

    private fun ClientFakeEcu.fieldWrites() = writes.filter { it.id == 0x04 }

    @Test
    fun `limite de giro grava so o byte que mudou e queima a tabela 4`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        ecu.load(0x04, 656, byteArrayOf(0x19, 0x00)) // 6400 RPM atual

        c.writeMegaSpeedConfigField("RevLimNormal2", 6500.0) // 0x1964

        val w = ecu.fieldWrites().single()
        assertEquals(657, w.offset)
        assertEquals(listOf(0x64), w.data.map { it.toInt() and 0xFF })
        assertEquals(listOf(0x04), ecu.burns)
        assertEquals(6500.0, c.readMegaSpeedFieldValues(listOf("RevLimNormal2"))["RevLimNormal2"])
    }

    @Test
    fun `dois bytes alterados vao no mesmo frame`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        ecu.load(0x04, 656, byteArrayOf(0x13, 0x88.toByte())) // 5000
        c.writeMegaSpeedConfigField("RevLimNormal2", 6500.0)
        val w = ecu.fieldWrites().single()
        assertEquals(656, w.offset)
        assertEquals(listOf(0x19, 0x64), w.data.map { it.toInt() and 0xFF })
    }

    @Test
    fun `escala do ini e aplicada e valor igual nao grava nem queima`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        ecu.load(0x04, 506, byteArrayOf(0x00, 0x34)) // 5.2 kPa
        c.writeMegaSpeedConfigField("map0", 5.2)
        assertTrue(ecu.fieldWrites().isEmpty())
        assertTrue(ecu.burns.isEmpty())

        c.writeMegaSpeedConfigField("map0", 6.0) // raw 60 = 0x3C
        assertEquals(listOf(0x3C), ecu.fieldWrites().single().data.map { it.toInt() and 0xFF })
        assertEquals(507, ecu.fieldWrites().single().offset)
    }

    @Test
    fun `valor fora do limite e campo nao permitido nada gravam`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        assertFailsWith<IllegalArgumentException> { c.writeMegaSpeedConfigField("RevLimNormal2", 200.0) }
        assertFailsWith<IllegalArgumentException> { c.writeMegaSpeedConfigField("RevLimNormal2", 99999.0) }
        assertFailsWith<IllegalArgumentException> { c.writeMegaSpeedConfigField("IdleCtl", 2.0) }
        assertTrue(ecu.writes.isEmpty())
        assertTrue(ecu.burns.isEmpty())
    }

    @Test
    fun `fora de MegaSpeed recusa`() = runBlocking<Unit> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.MS2)
        val client = EcuClient(ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        client.connect()
        assertFailsWith<UnsupportedOperationException> { client.writeMegaSpeedConfigField("RevLimNormal2", 6500.0) }
        assertTrue(ecu.writes.isEmpty())
    }
}
