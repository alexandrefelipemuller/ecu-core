package io.ecucore

import io.ecucore.definition.IniParser
import io.ecucore.model.Ms2IdleError
import io.ecucore.model.Ms2IdleInput
import io.ecucore.model.Ms2IdleValidator
import io.ecucore.model.Ms2WritableFields
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Marcha lenta com motor de passo na MS2: whitelist, leitura/gravação de campos e curvas, validador. */
class EcuClientMs2IdleTest {
    private val d = '$'

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
        iacstepTable    = array ,  S16,    354,    [   10], "steps",    1.00000,   0.00000,  0.00,  500.00,      0
        IdleCtl         = bits  ,  U08,    547,      [0:1], "None", "On/Off valve", "PWM valve (2 or 3 wire)", "Stepper valve (4 or 6 wire)"
        IACtstep        = scalar,  U08,    548,             "ms",       1,   0.00000,  0.00,   255,      0
        IACStart        = scalar, S16,     552,             "",         1.00000, 0.00000, 0.00, 4000.00,    0
        IdleHyst        = scalar,  S16,    554,             "°C",       0.05555,   0.00000, 0,  20.0,       1
        IdleHyst        = scalar,  S16,    554,             "°F",       0.10000,   0.00000, 0,  36.0,       1
        iacfullopen     = scalar,  U16,    914,  "steps",    1.00000,   0.00000,  0.0,   4000.0,      0

        page = 2
        pwmidle_target_rpms = array, U16, 0,    [ 8 ], "rpms",      1, 0.00000, 0.00, 2000, 0;
        pwmidle_clt_temps = array,  S16, 16,   [8],  "°C",   0.05555,  -320.000,-40,   200,      1 ;
        pwmidle_clt_temps = array,  S16, 16,   [8],  "°F",       0.10000,  0.00000,-40,   392,      1
        pwmidle_open_steps = scalar, U08, 35, "steps", 1.00000, 0.00000, 0, 255, 0
    """.trimIndent()

    private suspend fun connect(): Pair<EcuClient, ClientFakeEcu> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.MS2)
        val client = EcuClient(ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        client.connect()
        client.applyIniDefinition(IniParser.parse("microsquirt.ini", ini))
        return client to ecu
    }

    @Test
    fun `whitelist tem escalares e curvas de lenta`() {
        listOf("IdleCtl", "IdleCtl_alg", "IdleCtl_home", "IACtstep", "IACminstep", "IACStart", "IACcrankxt",
            "IdleHyst", "iacfullopen", "pwmidle_open_steps", "pwmidle_closed_steps").forEach {
            assertNotNull(Ms2WritableFields.byName(it), it)
        }
        assertEquals("F", Ms2WritableFields.curveByName("pwmidle_clt_temps")?.unitSuffix)
        assertNotNull(Ms2WritableFields.curveByName("iacstepTable"))
        assertEquals(500.0, Ms2WritableFields.curveByName("pwmidle_target_rpms")?.min)
        assertEquals(Ms2WritableFields.scalars.size, Ms2WritableFields.scalars.map { it.name }.toSet().size)
    }

    @Test
    fun `le e grava escalares e bits de IdleCtl`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        ecu.load(0x04, 547, byteArrayOf(0x28)) // alg=1 (bit3), home=1 (bit5), ctl=0
        c.writeMegaSpeedConfigField("IdleCtl", 3.0)
        assertEquals(0x2B, ecu.writes.single().data[0].toInt())
        c.writeMegaSpeedConfigField("IACStart", 500.0)
        c.writeMegaSpeedConfigField("iacfullopen", 300.0)
        c.writeMegaSpeedConfigField("pwmidle_open_steps", 120.0)
        val v = c.readMegaSpeedFieldValues(listOf("IdleCtl", "IACStart", "iacfullopen", "pwmidle_open_steps"))
        assertEquals(mapOf("IdleCtl" to 3.0, "IACStart" to 500.0, "iacfullopen" to 300.0, "pwmidle_open_steps" to 120.0), v)
        assertTrue(ecu.writes.any { it.id == 0x05 })
        assertFailsWith<IllegalArgumentException> { c.writeMegaSpeedConfigField("IdleCtl", 4.0) }
        assertFailsWith<IllegalArgumentException> { c.writeMegaSpeedConfigField("IACStart", 4001.0) }
    }

    @Test
    fun `curvas da lenta na pagina 5 e iacstepTable`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        val rpms = List(8) { 1200.0 - it * 50 }
        c.writeIniCurve("pwmidle_target_rpms", rpms)
        assertEquals(rpms, c.readIniCurve("pwmidle_target_rpms"))
        assertEquals(0x05, ecu.writes.last().id)
        val temps = List(8) { -40.0 + it * 30 }
        c.writeIniCurve("pwmidle_clt_temps", temps)
        assertEquals(temps, c.readIniCurve("pwmidle_clt_temps"))
        val steps = List(10) { 200.0 - it * 20 }
        c.writeIniCurve("iacstepTable", steps)
        assertEquals(steps, c.readIniCurve("iacstepTable"))
        assertFailsWith<IllegalArgumentException> { c.writeIniCurve("pwmidle_target_rpms", List(8) { 100.0 }) }
        assertFailsWith<IllegalArgumentException> { c.writeIniCurve("iacstepTable", List(10) { 501.0 }) }
    }

    private val okInput = Ms2IdleInput(
        iacStart = 600.0, iacFullOpen = 500.0, openSteps = 100.0, closedSteps = 20.0,
        iacStepTable = List(10) { 400.0 - it * 40 }, iacStepTempsF = List(10) { it * 20.0 },
        targetRpms = List(8) { 1200.0 - it * 50 }, targetTempsF = List(8) { it * 20.0 },
    )

    @Test
    fun `validador aceita configuracao correta`() {
        assertTrue(Ms2IdleValidator.validate(okInput).isEmpty())
        assertTrue(Ms2IdleValidator.validate(Ms2IdleInput()).isEmpty())
    }

    @Test
    fun `validador detecta cada erro`() {
        fun codes(i: Ms2IdleInput) = Ms2IdleValidator.validate(i).map { it.code }
        assertEquals(listOf(Ms2IdleError.Code.IAC_START_NOT_ABOVE_FULL_OPEN), codes(okInput.copy(iacStart = 500.0)))
        assertEquals(listOf(Ms2IdleError.Code.CLOSED_NOT_BELOW_OPEN), codes(okInput.copy(closedSteps = 100.0)))
        assertEquals(listOf(Ms2IdleError.Code.OPEN_STEPS_ABOVE_LIMIT), codes(okInput.copy(openSteps = 501.0, closedSteps = 20.0)))
        assertEquals(
            listOf(Ms2IdleError(Ms2IdleError.Code.IAC_STEP_ABOVE_LIMIT, 0)),
            Ms2IdleValidator.validate(okInput.copy(iacStepTable = okInput.iacStepTable!!.toMutableList().also { it[0] = 501.0 })),
        )
        // sem iacfullopen aplicável o teto é 500
        assertTrue(codes(okInput.copy(iacFullOpen = null, iacStepTable = List(10) { 500.0 })).isEmpty())
        assertEquals(
            listOf(Ms2IdleError(Ms2IdleError.Code.TARGET_RPM_OUT_OF_RANGE, 2)),
            Ms2IdleValidator.validate(okInput.copy(targetRpms = okInput.targetRpms!!.toMutableList().also { it[2] = 499.0 })),
        )
        assertTrue(codes(okInput.copy(targetRpms = okInput.targetRpms!!.dropLast(1))).contains(Ms2IdleError.Code.TARGET_RPM_POINT_COUNT))
        assertEquals(
            listOf(Ms2IdleError(Ms2IdleError.Code.TARGET_TEMP_NOT_INCREASING, 3)),
            Ms2IdleValidator.validate(okInput.copy(targetTempsF = okInput.targetTempsF!!.toMutableList().also { it[3] = it[2] })),
        )
        assertEquals(
            listOf(Ms2IdleError(Ms2IdleError.Code.IAC_TEMP_NOT_INCREASING, 1)),
            Ms2IdleValidator.validate(okInput.copy(iacStepTempsF = okInput.iacStepTempsF!!.toMutableList().also { it[1] = -5.0 })),
        )
    }
}
