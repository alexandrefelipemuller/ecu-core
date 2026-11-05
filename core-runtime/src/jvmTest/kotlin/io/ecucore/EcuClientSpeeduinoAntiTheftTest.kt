package io.ecucore

import io.ecucore.definition.IniParser
import io.ecucore.model.SpeeduinoAntiTheft
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Antifurto no Speeduino: limitador de giro em 0 RPM com assinatura e originais na página 9. */
class EcuClientSpeeduinoAntiTheftTest {

    private val d = '$'

    // Linhas do speeduino_202310.ini, que não tem o unused10_179 (páginas 2, 3 e 5 só para o layout de tabelas ser aceito).
    private fun ini(
        markerLine: String = """unused10_110 = scalar, U08, 110, "", 1, 0, 0, 255, 0""",
        revModeLine: String = """hardRevMode = bits, U08, 166, [0:1], "Off", "Fixed", "Coolant based", "INVALID"""",
    ) = """
        [MegaTune]
        signature = "speeduino 202310"

        [Constants]
        nPages = 15
        pageIdentifier = "${d}tsCanId\x01", "${d}tsCanId\x02", "${d}tsCanId\x03", "${d}tsCanId\x04", "${d}tsCanId\x05", "${d}tsCanId\x06", "${d}tsCanId\x07", "${d}tsCanId\x08", "${d}tsCanId\x09"
        pageSize = 128, 288, 288, 128, 288, 128, 240, 384, 192

        page = 1
        hardCutType = bits, U08, 26, [3:3], "Full", "Rolling"

        page = 2
        veTable = array, U08, 0, [16x16], "%", 1.0, 0.0, 0.0, 255.0, 0
        rpmBins = array, U08, 256, [16], "RPM", 100.0, 0.0, 100.0, 25500.0, 0
        fuelLoadBins = array, U08, 272, [16], "kPa", 2.0, 0.0, 0.0, 511.0, 0

        page = 3
        advTable1 = array, U08, 0, [16x16], "deg", 1.0, -40.0, -40.0, 70.0, 0
        rpmBins2 = array, U08, 256, [16], "RPM", 100.0, 0.0, 100.0, 25500.0, 0
        mapBins1 = array, U08, 272, [16], "kPa", 2.0, 0.0, 0.0, 511.0, 0

        page = 4
        hardRevLim = scalar, U08, 22, "rpm", 100, 0.0, 100, 25500, 0
        engineProtectMaxRPM = scalar, U08, 120, "rpm", 100, 0.0, 100, 25500, 0

        page = 5
        afrTable = array, U08, 0, [16x16], "AFR", 0.1, 0.0, 7.0, 25.5, 1
        rpmBinsAFR = array, U08, 256, [16], "RPM", 100.0, 0.0, 100.0, 25500.0, 0
        loadBinsAFR = array, U08, 272, [16], "kPa", 2.0, 0.0, 0.0, 511.0, 0

        page = 6
        engineProtectType = bits, U08, 0, [6:7], "Off", "Spark Only", "Fuel Only", "Both"

        page = 9
        unused10_68 = scalar, U08, 68, "", 1, 0, 0, 255, 0
        $markerLine
        unused10_111 = scalar, U08, 111, "", 1, 0, 0, 255, 0
        $revModeLine
    """.trimIndent()

    private suspend fun connect(running: Boolean = false, iniText: String = ini()): Pair<EcuClient, ClientFakeEcu> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025)
        val client = EcuClient(ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        client.connect()
        client.applyIniDefinition(IniParser.parse("speeduino.ini", iniText))
        // Bloco ao vivo zerado = motor parado; tudo 0xFF = rotação diferente de zero.
        ecu.liveBytes = if (running) ByteArray(256) { 0xFF.toByte() } else null
        // Configuração do usuário: limite 7000 RPM pela temperatura, proteção desligada a partir de
        // 1000 RPM, corte rolling. Os outros bits dos mesmos bytes ligados para conferir que a trava
        // não mexe neles.
        ecu.load(4, 22, byteArrayOf(70))
        ecu.load(4, 120, byteArrayOf(10))
        ecu.load(1, 26, byteArrayOf(0b1111_1111.toByte())) // hardCutType Rolling
        ecu.load(6, 0, byteArrayOf(0b0011_1111)) // engineProtectType Off
        ecu.load(9, 166, byteArrayOf(0b1111_1110.toByte())) // hardRevMode Coolant based
        ecu.writes.clear()
        ecu.burns.clear()
        return client to ecu
    }

    private fun ClientFakeEcu.u8(page: Int, offset: Int) = mem(page)[offset].toInt() and 0xFF

    private fun ClientFakeEcu.assertUserConfig() {
        assertEquals(70, u8(4, 22))
        assertEquals(10, u8(4, 120))
        assertEquals(0b1111_1111, u8(1, 26))
        assertEquals(0b0011_1111, u8(6, 0))
        assertEquals(0b1111_1110, u8(9, 166))
    }

    private val userOriginal = SpeeduinoAntiTheft.Original(revLimit = 70, protectMinRpm = 10, protectType = 0, revMode = 2, cutType = 1)

    @Test
    fun `travar guarda os originais, poe a assinatura e depois zera o limitador`() = runBlocking<Unit> {
        val (c, ecu) = connect()

        val originalRaw = c.lockAntiTheft()

        assertEquals(userOriginal.toRaw(), originalRaw)
        // Rotação mínima original, assinatura + limite original, corte total, proteção, modo fixo,
        // proteção desde 0 RPM e o limite por último.
        assertEquals(
            listOf(9 to 68, 9 to 110, 1 to 26, 6 to 0, 9 to 166, 4 to 120, 4 to 22),
            ecu.writes.map { it.id to it.offset },
        )
        assertEquals(userOriginal.markerByte(), ecu.u8(9, 110))
        assertEquals(70, ecu.u8(9, 111))
        assertEquals(10, ecu.u8(9, 68))
        assertEquals(0, ecu.u8(4, 22))
        assertEquals(0, ecu.u8(4, 120))
        assertEquals(0b1111_0111, ecu.u8(1, 26)) // Full
        assertEquals(0b1111_1111, ecu.u8(6, 0)) // Both
        assertEquals(0b1111_1101, ecu.u8(9, 166)) // Fixed
        assertEquals(7, ecu.burns.size)
        assertTrue(c.readAntiTheftLocked())
    }

    @Test
    fun `destravar sem original salvo usa o da ECU e apaga a assinatura depois`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        c.lockAntiTheft()
        ecu.writes.clear()

        c.unlockAntiTheft()

        assertEquals(
            listOf(4 to 22, 4 to 120, 9 to 166, 6 to 0, 1 to 26, 9 to 110, 9 to 68),
            ecu.writes.map { it.id to it.offset },
        )
        ecu.assertUserConfig()
        assertEquals(0, ecu.u8(9, 110))
        assertEquals(0, ecu.u8(9, 111))
        assertEquals(0, ecu.u8(9, 68))
        assertFalse(c.readAntiTheftLocked())
    }

    @Test
    fun `destravar com o original do celular`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        val originalRaw = c.lockAntiTheft()
        // Originais da página 9 perdidos (ex.: alguém mexeu), assinatura mantida: o do celular manda.
        ecu.load(9, 110, byteArrayOf(SpeeduinoAntiTheft.MARKER.toByte(), 0))
        ecu.load(9, 68, byteArrayOf(0))

        c.unlockAntiTheft(originalRaw)

        ecu.assertUserConfig()
        assertFalse(c.readAntiTheftLocked())
    }

    @Test
    fun `assinatura com campos ainda nao gravados conta como travada e destrava`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        // Trava interrompida logo depois da assinatura: limite e modos ainda do usuário.
        ecu.load(9, 68, byteArrayOf(10))
        ecu.load(9, 110, byteArrayOf(userOriginal.markerByte().toByte(), 70))
        assertTrue(c.readAntiTheftLocked())

        c.unlockAntiTheft()

        ecu.assertUserConfig()
        // Só a assinatura e os originais mudam: campos já no valor original não são regravados.
        assertEquals(listOf(9 to 110, 9 to 68), ecu.writes.map { it.id to it.offset })
    }

    @Test
    fun `marca da trava antiga pelo desafogador nao conta como trava`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        ecu.load(9, 110, byteArrayOf(0xA7.toByte(), 180.toByte()))
        assertFalse(c.readAntiTheftLocked())
    }

    @Test
    fun `limitador em 0 sem a assinatura nao e trava do app`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        ecu.load(4, 22, byteArrayOf(0))
        assertFalse(c.readAntiTheftLocked())
        c.unlockAntiTheft()
        assertTrue(ecu.writes.isEmpty())
        // E não trava por cima: o original seria 0.
        assertFailsWith<IllegalArgumentException> { c.lockAntiTheft() }
        assertTrue(ecu.writes.isEmpty())
    }

    @Test
    fun `motor girando nao trava`() = runBlocking<Unit> {
        val (c, ecu) = connect(running = true)
        assertFailsWith<IllegalStateException> { c.lockAntiTheft() }
        assertTrue(ecu.writes.isEmpty())
    }

    @Test
    fun `ini sem os bytes livres ou com campo fora do lugar recusa`() = runBlocking<Unit> {
        for (iniText in listOf(
            ini(markerLine = ""),
            ini(revModeLine = """hardRevMode = bits, U08, 166, [0:2], "Off", "Fixed", "Coolant based", "INVALID""""),
        )) {
            val (c, ecu) = connect(iniText = iniText)
            assertFailsWith<UnsupportedOperationException> { c.readAntiTheftLocked() }
            assertFailsWith<UnsupportedOperationException> { c.lockAntiTheft() }
            assertTrue(ecu.writes.isEmpty())
        }
    }
}
