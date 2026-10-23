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
        baromax = scalar, S16, 532, "kPa", 0.10000, 0.00000, -3276.8, 3276.7, 1
        RevLimNormal2 = scalar, S16, 656, "RPM", 1.00000, 0.00000, 0.00, 15000, 0
        RevLimNormal2_hyst= scalar,U16,    46,             "RPM",      1.00000,   0.00000,  100,  1000,       0
        RevLimOption_retard = bits,U08,    636,      [0:1], "Desligado", "Retardo progressivo", "Angulo fixo", "INVALID"
        RevLimOption_spkcut = bits,U08,    636,      [2:2], "Desligado", "Ligado"
        RevLimMaxRtd    = scalar,  U08,    637,             "grau",      0.10000,   0.00000,  0,      25.50,      1
        RevLimRtdAng    = scalar,  S16,    654,             "grau",      0.10000,   0.00000,-10,   25.5,      1
        IdleCtl = bits, U08, 547, [0:1], "Nenhuma", "Atuador On/Off", "Atuador PWM", "Motor de Passo"

        page = 7
        oil0 = scalar, S16, 629, "V", 0.10000, 0.00000, -300.0, 3276.7, 1
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
    fun `baromax grava os dois bytes como na captura do TunerStudio`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        c.writeMegaSpeedConfigField("baromax", 416.5) // raw 4165 = 0x1045
        val w = ecu.fieldWrites().single()
        assertEquals(532, w.offset)
        assertEquals(listOf(0x10, 0x45), w.data.map { it.toInt() and 0xFF })
        assertEquals(listOf(0x04), ecu.burns)
    }

    @Test
    fun `oil0 grava na tabela 0C so o byte que mudou e queima essa tabela`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        c.writeMegaSpeedConfigField("oil0", 4.9) // raw 49 = 0x31, igual à captura (offset 630)
        val w = ecu.writes.single { it.id == 0x0C }
        assertEquals(630, w.offset)
        assertEquals(listOf(0x31), w.data.map { it.toInt() and 0xFF })
        assertEquals(listOf(0x0C), ecu.burns)
        assertFailsWith<IllegalArgumentException> { c.writeMegaSpeedConfigField("oil0", 6.0) }
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

    @Test
    fun `campo de 8 bits grava um byte`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        c.writeMegaSpeedConfigField("RevLimMaxRtd", 5.0) // raw 50
        val w = ecu.fieldWrites().single()
        assertEquals(637, w.offset)
        assertEquals(listOf(50), w.data.map { it.toInt() and 0xFF })
        assertEquals(5.0, c.readMegaSpeedFieldValues(listOf("RevLimMaxRtd"))["RevLimMaxRtd"])
    }

    @Test
    fun `bits preservam os outros bits do byte`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        ecu.load(0x04, 636, byteArrayOf(0b0010_0001)) // retard=1, fuelcut ligado
        c.writeMegaSpeedConfigField("RevLimOption_spkcut", 1.0)
        val w = ecu.fieldWrites().single()
        assertEquals(636, w.offset)
        assertEquals(listOf(0b0010_0101), w.data.map { it.toInt() and 0xFF })
        c.writeMegaSpeedConfigField("RevLimOption_retard", 2.0)
        assertEquals(listOf(0b0010_0110), ecu.fieldWrites().last().data.map { it.toInt() and 0xFF })
        assertEquals(2.0, c.readMegaSpeedFieldValues(listOf("RevLimOption_retard"))["RevLimOption_retard"])
        assertFailsWith<IllegalArgumentException> { c.writeMegaSpeedConfigField("RevLimOption_spkcut", 2.0) }
    }

    @Test
    fun `retardo fixo negativo usa complemento de dois`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        c.writeMegaSpeedConfigField("RevLimRtdAng", -5.0) // raw -50 = 0xFFCE
        val w = ecu.fieldWrites().single()
        assertEquals(654, w.offset)
        assertEquals(listOf(0xFF, 0xCE), w.data.map { it.toInt() and 0xFF })
        assertEquals(-5.0, c.readMegaSpeedFieldValues(listOf("RevLimRtdAng"))["RevLimRtdAng"])
    }
}
