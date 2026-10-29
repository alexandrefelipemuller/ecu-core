package io.ecucore

import io.ecucore.definition.IniParser
import io.ecucore.model.IniCurveCodec
import io.ecucore.model.IniTemperature
import io.ecucore.model.Ms2WueValidator
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Leitura/gravação de curvas 1D do .ini na MS2 (WUE) e helpers de temperatura/validação. */
class EcuClientMs2CurveTest {

    private val d = '$'

    // Trecho real do microsquirt.ini: tempTable definida duas vezes (#if CELSIUS), o parser não avalia #if.
    private val ini = """
        [MegaTune]
        signature = "MS2Extra comms342hU"

        [Constants]
        endianness = big
        nPages = 7
        pageSize = 1024,1024,1024,1024,1024,1024,1024
        pageIdentifier = "${d}tsCanId\x04","${d}tsCanId\x05","${d}tsCanId\x0a","${d}tsCanId\x08","${d}tsCanId\x09","${d}tsCanId\x0b","${d}tsCanId\x0c"
        ochBlockSize = 219

        page = 1
        wueBins         = array ,  U08,    336,    [   10], "%",        1.00000,   0.00000,  0.00,  255.00,      0
        tempTable       = array ,  S16,    470,    [   10], "°C",       0.05555,  -320.000,-40,   200,      1
        tempTable       = array ,  S16,    470,    [   10], "°F",       0.10000,   0.00000,-40,   392,      1
    """.trimIndent()

    private suspend fun connect(): Pair<EcuClient, ClientFakeEcu> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.MS2)
        val client = EcuClient(ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        client.connect()
        client.applyIniDefinition(IniParser.parse("microsquirt.ini", ini))
        return client to ecu
    }

    private val wue = listOf(180, 170, 160, 150, 140, 130, 120, 110, 105, 100)
    private val tempsF10 = listOf(-400, 0, 200, 400, 600, 800, 1000, 1200, 1400, 1600) // 0,1°F

    private fun ClientFakeEcu.seed() {
        load(0x04, 336, ByteArray(10) { wue[it].toByte() })
        val t = ByteArray(20)
        tempsF10.forEachIndexed { i, v -> t[i * 2] = (v shr 8).toByte(); t[i * 2 + 1] = v.toByte() }
        load(0x04, 470, t)
    }

    @Test
    fun `le wueBins e tempTable em F`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        ecu.seed()
        assertEquals(wue.map { it.toDouble() }, c.readIniCurve("wueBins"))
        val temps = c.readIniCurve("tempTable")
        assertEquals(-40.0, temps[0], 1e-9)
        assertEquals(160.0, temps[9], 1e-9)
        assertEquals(-40.0, IniTemperature.fahrenheitToCelsius(temps[0]), 1e-9)
    }

    @Test
    fun `grava so a faixa de bytes alterada e queima a pagina`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        ecu.seed()
        val novo = wue.map { it.toDouble() }.toMutableList().also { it[3] = 155.0; it[5] = 125.0 }
        c.writeIniCurve("wueBins", novo)
        val w = ecu.writes.single { it.id == 0x04 }
        assertEquals(339, w.offset)
        assertEquals(listOf(155, 140, 125), w.data.map { it.toInt() and 0xFF })
        assertEquals(listOf(0x04), ecu.burns)
        assertEquals(novo, c.readIniCurve("wueBins"))
    }

    @Test
    fun `tempTable grava em F x10 big-endian e nada a gravar se igual`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        ecu.seed()
        c.writeIniCurve("tempTable", c.readIniCurve("tempTable"))
        assertTrue(ecu.writes.isEmpty())
        val t = c.readIniCurve("tempTable").toMutableList().also { it[1] = 5.0 } // 5,0 °F = raw 50
        c.writeIniCurve("tempTable", t)
        val w = ecu.writes.single()
        assertEquals(473, w.offset) // byte alto (0x00) não mudou
        assertEquals(listOf(0x32), w.data.map { it.toInt() and 0xFF })
    }

    @Test
    fun `recusa curva fora da whitelist, tamanho errado e valor fora do limite`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        ecu.seed()
        assertFailsWith<IllegalArgumentException> { c.readIniCurve("outra") }
        assertFailsWith<IllegalArgumentException> { c.writeIniCurve("wueBins", listOf(100.0)) }
        assertFailsWith<IllegalArgumentException> { c.writeIniCurve("wueBins", List(10) { 300.0 }) }
        assertTrue(ecu.writes.isEmpty())
    }

    @Test
    fun `codec decodifica S16 negativo e U08`() {
        assertEquals(listOf(-40.0), IniCurveCodec.decode(byteArrayOf(0xFE.toByte(), 0x70), 2, true, 0.1, 0.0))
        assertEquals(listOf(255.0), IniCurveCodec.decode(byteArrayOf(0xFF.toByte()), 1, false, 1.0, 0.0))
        assertEquals(listOf(5.0), IniCurveCodec.decode(byteArrayOf(0x00, 0x32), 2, true, 0.1, 0.0))
    }

    @Test
    fun `conversao F C`() {
        assertEquals(100.0, IniTemperature.fahrenheitToCelsius(212.0), 1e-9)
        assertEquals(212.0, IniTemperature.celsiusToFahrenheit(100.0), 1e-9)
    }

    @Test
    fun `validacao do WUE`() {
        val ok = wue.map { it.toDouble() }
        val t = tempsF10.map { it / 10.0 }
        assertTrue(Ms2WueValidator.validate(ok, t).isEmpty())
        assertTrue(Ms2WueValidator.validate(ok.dropLast(1) + 99.0, t).isNotEmpty())
        assertTrue(Ms2WueValidator.validate(ok.dropLast(1) + 256.0, t).isNotEmpty())
        assertTrue(Ms2WueValidator.validate(ok, t.toMutableList().also { it[4] = it[3] }).isNotEmpty())
        assertTrue(Ms2WueValidator.validate(ok.drop(1), t).isNotEmpty())
    }
}
