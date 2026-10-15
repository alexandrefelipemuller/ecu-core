package io.ecucore.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MegaSpeedOutputTestTest {

    // Linhas reais de [ControllerCommands] do MegaSpeedMS2-X.ini (CostaMegaSpeed), extraídas por script.
    private val iniCommands = """
cmdStopTestmode = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x00"
cmdEnterTestMode = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x01"
cmdtestspkon = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x02"
cmdtestinjon = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x03"
cmdfpon = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x05"
cmdfpoff = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x06"
cmdtestinjspkoff = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x07"
cmdtestiacoff = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x08"
cmdtestiachome = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x09"
cmdtestiacon = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x0a"
cmdtest10off  = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x80"
cmdtest10pulsed = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x82"
cmdtest10on = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x83"
cmdtest11off  = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x84"
cmdtest11pulsed = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x86"
cmdtest11on = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x87"
cmdtest12off  = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x88"
cmdtest12pulsed = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x8a"
cmdtest12on = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x8b"
cmdtest13off  = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x8c"
cmdtest13pulsed = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x8e"
cmdtest13on = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x8f"
cmdtest14off  = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x90"
cmdtest14pulsed = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x92"
cmdtest14on = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x93"
cmdtest15off  = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x94"
cmdtest15pulsed = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x96"
cmdtest15on = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x97"
cmdtest16off  = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x98"
cmdtest16pulsed = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x9a"
cmdtest16on = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x9b"
cmdtest17off  = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x9c"
cmdtest17pulsed = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x9e"
cmdtest17on = "w\${'$'}tsCanId\x07\x02\x08\x00\x03\x30\x39\x9f"
    """.trimIndent()

    /** nome do comando no .ini -> (3 últimos bytes do frame) */
    private val parsed: Map<String, List<Int>> = iniCommands.lines().associate { line ->
        val name = line.substringBefore("=").trim()
        val bytes = Regex("""\\x([0-9A-Fa-f]{2})""").findAll(line.substringAfter("=")).map { it.groupValues[1].toInt(16) }.toList()
        name to bytes
    }

    private fun iniCode(name: String): Int = parsed.getValue(name).last()

    private val live = MegaSpeedTestStatus(rpm = 0, testModeActive = true)
    private val validParams = MegaSpeedTestParams(
        coilMode = 1, injectorMode = 1, coilSelect = 0, injectorSelect = 0,
        dwellRaw = 20, intervalRaw = 800, pulseWidthRaw = 1500, pulseCount = 10,
    )

    private fun allowed(c: MegaSpeedTestCommand, s: MegaSpeedTestStatus? = live, p: MegaSpeedTestParams? = validParams) =
        MegaSpeedOutputTestGuard.evaluate(c, s, p) == MegaSpeedTestDecision.Allowed

    // ---- catálogo x .ini ------------------------------------------------------------------------

    @Test
    fun `todos os frames do ini seguem tabela 7 offset 0x0208 chave 0x3039`() {
        assertEquals(34, parsed.size) // 10 fixos + 8 canais x 3
        parsed.forEach { (name, bytes) ->
            // w, canId, tabela, offset(2), tamanho(2), chave(2), comando
            assertEquals(listOf(0x07, 0x02, 0x08, 0x00, 0x03, 0x30, 0x39), bytes.dropLast(1), name)
        }
    }

    @Test
    fun `codigos do catalogo batem com o ini`() {
        assertEquals(iniCode("cmdEnterTestMode"), MegaSpeedTestCommand.ENTER_TEST_MODE.code)
        assertEquals(iniCode("cmdStopTestmode"), MegaSpeedTestCommand.STOP_TEST_MODE.code)
        assertEquals(iniCode("cmdfpon"), MegaSpeedTestCommand.FUEL_PUMP_ON.code)
        assertEquals(iniCode("cmdfpoff"), MegaSpeedTestCommand.FUEL_PUMP_OFF.code)
        assertEquals(iniCode("cmdtestspkon"), MegaSpeedTestCommand.SPARK_PULSE.code)
        assertEquals(iniCode("cmdtestinjon"), MegaSpeedTestCommand.INJECTOR_PULSE.code)
        assertEquals(iniCode("cmdtestinjspkoff"), MegaSpeedTestCommand.INJECTOR_SPARK_OFF.code)
        assertEquals(iniCode("cmdtestiacoff"), MegaSpeedTestCommand.IDLE_OFF.code)
        assertEquals(iniCode("cmdtestiachome"), MegaSpeedTestCommand.IDLE_HOME.code)
        assertEquals(iniCode("cmdtestiacon"), MegaSpeedTestCommand.IDLE_ON.code)
    }

    @Test
    fun `portas extras batem com cmdtestNNoff e cmdtestNNon do ini`() {
        MegaSpeedAuxPort.entries.forEach { port ->
            assertEquals(iniCode("cmdtest${port.channel}off"), port.offCode, port.name)
            assertEquals(iniCode("cmdtest${port.channel}on"), port.onCode, port.name)
            assertEquals(port.offCode, MegaSpeedTestCommand.auxOff(port).code)
            assertEquals(port.onCode, MegaSpeedTestCommand.auxOn(port).code)
        }
    }

    @Test
    fun `dados do comando sao chave mais codigo`() {
        assertEquals(listOf(0x30, 0x39, 0x05), MegaSpeedTestCommand.FUEL_PUMP_ON.data().map { it.toInt() and 0xFF })
    }

    @Test
    fun `STOP_ALL desliga tudo e sai do modo de teste por ultimo`() {
        val all = MegaSpeedTestCommand.STOP_ALL
        assertTrue(all.all { it.isOff })
        assertEquals(MegaSpeedTestCommand.STOP_TEST_MODE, all.last())
        assertTrue(MegaSpeedTestCommand.FUEL_PUMP_OFF in all)
        assertTrue(MegaSpeedTestCommand.INJECTOR_SPARK_OFF in all)
        assertTrue(MegaSpeedTestCommand.IDLE_OFF in all)
        MegaSpeedAuxPort.entries.forEach { assertTrue(MegaSpeedTestCommand.auxOff(it) in all, it.name) }
    }

    @Test
    fun `todo comando que liga algo tem watchdog`() {
        val ons = listOf(
            MegaSpeedTestCommand.FUEL_PUMP_ON, MegaSpeedTestCommand.SPARK_PULSE, MegaSpeedTestCommand.INJECTOR_PULSE,
            MegaSpeedTestCommand.IDLE_HOME, MegaSpeedTestCommand.IDLE_ON,
        ) + MegaSpeedAuxPort.entries.map { MegaSpeedTestCommand.auxOn(it) }
        ons.forEach { assertTrue(it.autoOffMs in 1..30_000, it.label) }
        MegaSpeedTestCommand.STOP_ALL.forEach { assertEquals(0L, it.autoOffMs) }
    }

    // ---- guard ------------------------------------------------------------------------------------

    @Test
    fun `desligar e sempre permitido mesmo sem status`() {
        MegaSpeedTestCommand.STOP_ALL.forEach {
            assertTrue(allowed(it, s = null, p = null), it.label)
            assertTrue(allowed(it, s = MegaSpeedTestStatus(3000, false), p = null), it.label)
        }
    }

    @Test
    fun `sem status nao aciona nada`() {
        assertFalse(allowed(MegaSpeedTestCommand.ENTER_TEST_MODE, s = null))
        assertFalse(allowed(MegaSpeedTestCommand.FUEL_PUMP_ON, s = null))
        assertFalse(allowed(MegaSpeedTestCommand.IDLE_ON, s = null))
    }

    @Test
    fun `modo de teste so entra com rpm zero e fora do modo`() {
        assertTrue(allowed(MegaSpeedTestCommand.ENTER_TEST_MODE, MegaSpeedTestStatus(0, false)))
        assertFalse(allowed(MegaSpeedTestCommand.ENTER_TEST_MODE, MegaSpeedTestStatus(800, false)))
        assertFalse(allowed(MegaSpeedTestCommand.ENTER_TEST_MODE, MegaSpeedTestStatus(0, true)))
    }

    @Test
    fun `acionamentos exigem modo de teste ativo e rpm zero`() {
        val outputs = listOf(MegaSpeedTestCommand.FUEL_PUMP_ON) + MegaSpeedAuxPort.entries.map { MegaSpeedTestCommand.auxOn(it) }
        outputs.forEach {
            assertTrue(allowed(it), it.label)
            assertFalse(allowed(it, MegaSpeedTestStatus(0, false)), it.label)
            assertFalse(allowed(it, MegaSpeedTestStatus(1200, true)), it.label)
        }
    }

    @Test
    fun `atuador de lenta funciona com motor ligado como no ini`() {
        assertTrue(allowed(MegaSpeedTestCommand.IDLE_ON, MegaSpeedTestStatus(900, false)))
        assertTrue(allowed(MegaSpeedTestCommand.IDLE_HOME, MegaSpeedTestStatus(0, false)))
    }

    @Test
    fun `bobina e injetor exigem parametros validos`() {
        assertTrue(allowed(MegaSpeedTestCommand.SPARK_PULSE))
        assertTrue(allowed(MegaSpeedTestCommand.INJECTOR_PULSE))
        assertFalse(allowed(MegaSpeedTestCommand.SPARK_PULSE, p = null))
        assertFalse(allowed(MegaSpeedTestCommand.SPARK_PULSE, p = validParams.copy(coilMode = 0)))
        assertFalse(allowed(MegaSpeedTestCommand.SPARK_PULSE, p = validParams.copy(dwellRaw = 0)))
        assertFalse(allowed(MegaSpeedTestCommand.SPARK_PULSE, p = validParams.copy(dwellRaw = 41)))   // 4,1 ms
        assertFalse(allowed(MegaSpeedTestCommand.INJECTOR_PULSE, p = validParams.copy(injectorMode = 0)))
        assertFalse(allowed(MegaSpeedTestCommand.INJECTOR_PULSE, p = validParams.copy(pulseCount = 0)))
        assertFalse(allowed(MegaSpeedTestCommand.INJECTOR_PULSE, p = validParams.copy(pulseCount = 1001)))
        assertFalse(allowed(MegaSpeedTestCommand.INJECTOR_PULSE, p = validParams.copy(pulseWidthRaw = 15016)))
        assertFalse(allowed(MegaSpeedTestCommand.INJECTOR_PULSE, p = validParams.copy(intervalRaw = 10)))
    }

    // ---- parâmetros -------------------------------------------------------------------------------

    @Test
    fun `parametros fazem round trip e preservam bits alheios`() {
        val page = ByteArray(1024)
        page[MegaSpeedTestParams.OFFSET_OP] = 0b1001_1100.toByte()   // bits que não são nossos
        page[MegaSpeedTestParams.OFFSET_SELECT] = 0b1000_1100.toByte()
        val written = validParams.copy(injectorMode = 3, coilSelect = 4, injectorSelect = 2).applyTo(page)

        val back = MegaSpeedTestParams.fromPage(written)
        assertEquals(validParams.copy(injectorMode = 3, coilSelect = 4, injectorSelect = 2), back)
        assertEquals(0b1001_1100, written[MegaSpeedTestParams.OFFSET_OP].toInt() and 0b1001_1100)
        assertEquals(0b1000_1100, written[MegaSpeedTestParams.OFFSET_SELECT].toInt() and 0b1000_1100)
        // nada fora de 2..10 mudou
        for (i in written.indices) if (i !in 2..10) assertEquals(page[i], written[i], "offset $i")
    }

    // ---- status -----------------------------------------------------------------------------------

    @Test
    fun `status3 bit 3 indica modo de teste`() {
        val d = ByteArray(219)
        assertEquals(false, MegaSpeedTestStatus.fromOutputChannels(0, d)!!.testModeActive)
        d[80] = 0x08
        assertEquals(true, MegaSpeedTestStatus.fromOutputChannels(0, d)!!.testModeActive)
        d[80] = 0x02 // outro bit (dataReady)
        assertEquals(false, MegaSpeedTestStatus.fromOutputChannels(0, d)!!.testModeActive)
        assertEquals(null, MegaSpeedTestStatus.fromOutputChannels(0, ByteArray(80)))
        assertEquals(null, MegaSpeedTestStatus.fromOutputChannels(0, null))
    }
}
