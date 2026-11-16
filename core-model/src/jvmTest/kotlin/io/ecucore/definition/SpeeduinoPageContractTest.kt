package io.ecucore.definition

import io.ecucore.model.ClosedLoopCorrectionMapper
import io.ecucore.model.EngineProtectionMapper
import io.ecucore.model.IdleControlLayout
import io.ecucore.model.IdleControlSettings
import io.ecucore.model.SecondarySerialConfig
import io.ecucore.model.TriggerSettings
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Contrato entre as telas do Speeduino com página/offset fixos no código e o .ini (fonte da verdade).
 *
 * Origem: a marcha lenta usava as páginas 4 e 7 quando o .ini diz 6, e nenhum teste conferia isso
 * (a ECU falsa aceitava qualquer página). Cada linha abaixo é (campo do .ini, página e offset que o código usa);
 * se o .ini divergir, o teste falha dizendo qual campo e em qual firmware.
 */
class SpeeduinoPageContractTest {

    private data class Expect(
        val field: String,
        val page: Int,
        val offset: Int,
        val bit: IntRange? = null,
        /** Nome que o mesmo campo tem em firmwares antigos (mesma página/offset). */
        val alias: String? = null,
    )

    /**
     * Campos que o 202008 NÃO tem: o código só os usa a partir do 202402 (o .ini marca esses offsets como `unused`
     * ou deixa o bit livre). O teste exige que continuem ausentes, para avisar se o .ini passar a defini-los.
     */
    private val absentIn202008 = setOf("iacPWMrun", "egoMAPMax", "egoMAPMin")

    private fun load(id: String): IniDefinition {
        val text = checkNotNull(javaClass.getResourceAsStream("/ini/$id.ini")) { "ini $id ausente" }
            .bufferedReader(Charsets.ISO_8859_1).readText()
        return IniParser.parse("$id.ini", text)
    }

    private val idle = listOf(
        Expect("idleFreq", IdleControlSettings.PAGE_NUMBER, 47),
        Expect("iacPWMrun", IdleControlSettings.PAGE_NUMBER, 60, 1..1),
        Expect("iacCLValues", IdleControlSettings.PAGE_NUMBER, 64),
        Expect("iacBins", IdleControlSettings.PAGE_NUMBER, 94),
        Expect("iacAlgorithm", IdleControlSettings.PAGE_NUMBER, 116, 0..2),
        Expect("iacStepTime", IdleControlSettings.PAGE_NUMBER, 116, 3..5),
        Expect("iacChannels", IdleControlSettings.PAGE_NUMBER, 116, 6..6),
        Expect("iacPWMdir", IdleControlSettings.PAGE_NUMBER, 116, 7..7),
        Expect("iacFastTemp", IdleControlSettings.PAGE_NUMBER, 117),
        Expect("iacStepHome", IdleControlSettings.PAGE_NUMBER, 118),
        Expect("iacStepHyster", IdleControlSettings.PAGE_NUMBER, 119),
    )

    private val trigger = listOf(
        Expect("TrigAng", TriggerSettings.PAGE_NUMBER, 0),
        Expect("FixAng", TriggerSettings.PAGE_NUMBER, 2),
        Expect("CrankAng", TriggerSettings.PAGE_NUMBER, 3),
        Expect("TrigAngMul", TriggerSettings.PAGE_NUMBER, 4),
        Expect("TrigPattern", TriggerSettings.PAGE_NUMBER, 5, 3..7),
        Expect("TrigEdge", TriggerSettings.PAGE_NUMBER, 5, 0..0),
        Expect("trigPatternSec", TriggerSettings.PAGE_NUMBER, 8, 0..6), // 202008 declara [0:7]
        Expect("SkipCycles", TriggerSettings.PAGE_NUMBER, 11),
        Expect("numTeeth", TriggerSettings.PAGE_NUMBER, 15),
        Expect("missingTeeth", TriggerSettings.PAGE_NUMBER, 16),
    )

    private val closedLoop = listOf(
        Expect("egoAlgorithm", ClosedLoopCorrectionMapper.PAGE_NUMBER, 0, 0..1),
        Expect("egoType", ClosedLoopCorrectionMapper.PAGE_NUMBER, 0, 2..3),
        Expect("egoKP", ClosedLoopCorrectionMapper.PAGE_NUMBER, 1),
        Expect("egoKI", ClosedLoopCorrectionMapper.PAGE_NUMBER, 2),
        Expect("egoKD", ClosedLoopCorrectionMapper.PAGE_NUMBER, 3),
        Expect("egoTemp", ClosedLoopCorrectionMapper.PAGE_NUMBER, 4),
        Expect("egoCount", ClosedLoopCorrectionMapper.PAGE_NUMBER, 5),
        Expect("egoLimit", ClosedLoopCorrectionMapper.PAGE_NUMBER, 7),
        Expect("ego_min_afr", ClosedLoopCorrectionMapper.PAGE_NUMBER, 8, alias = "ego_min"),
        Expect("ego_max_afr", ClosedLoopCorrectionMapper.PAGE_NUMBER, 9, alias = "ego_max"),
        Expect("ego_sdelay", ClosedLoopCorrectionMapper.PAGE_NUMBER, 10),
        Expect("egoRPM", ClosedLoopCorrectionMapper.PAGE_NUMBER, 11),
        Expect("egoTPSMax", ClosedLoopCorrectionMapper.PAGE_NUMBER, 12),
        Expect("egoMAPMax", ClosedLoopCorrectionMapper.MAP_WINDOW_PAGE_NUMBER, 112),
        Expect("egoMAPMin", ClosedLoopCorrectionMapper.MAP_WINDOW_PAGE_NUMBER, 113),
    )

    private val secondarySerial = listOf(
        Expect("enable_secondarySerial", SecondarySerialConfig.PAGE_NUMBER, SecondarySerialConfig.OFFSET, 0..0),
    )

    /** Campos que só existem do 202402 em diante (o 202008 não os tem). */
    private val engineProtection = listOf(
        Expect("boostCutEnabled", EngineProtectionMapper.PAGE_NUMBER, 6, 7..7),
        Expect("engineProtectType", EngineProtectionMapper.PAGE_NUMBER, 0, 6..7),
        Expect("hardCutType", EngineProtectionMapper.CUT_TYPE_PAGE_NUMBER, 26, 3..3),
        Expect("SoftRevLim", EngineProtectionMapper.RPM_PAGE_NUMBER, 19),
        Expect("SoftLimRetard", EngineProtectionMapper.RPM_PAGE_NUMBER, 20),
        Expect("SoftLimMax", EngineProtectionMapper.RPM_PAGE_NUMBER, 21),
        Expect("hardRevLim", EngineProtectionMapper.RPM_PAGE_NUMBER, 22),
        Expect("engineProtectMaxRPM", EngineProtectionMapper.RPM_PAGE_NUMBER, 120),
        Expect("hardRevMode", EngineProtectionMapper.HARD_REV_MODE_PAGE_NUMBER, 166, 0..1),
        Expect("afrProtectEnabled", EngineProtectionMapper.HARD_REV_MODE_PAGE_NUMBER, 185, 0..1),
        Expect("oilPressureProtEnbl", EngineProtectionMapper.OIL_PAGE_NUMBER, 135, 2..2),
    )

    private fun check(firmware: String, expected: List<Expect>): List<String> {
        val ini = load(firmware)
        val problems = mutableListOf<String>()
        for (e in expected) {
            val candidates = ini.fields.filter { it.name == e.field || it.name == e.alias }
            val expectedAbsent = firmware == "speeduino_202008" && e.field in absentIn202008
            if (expectedAbsent) {
                if (candidates.isNotEmpty()) problems += "$firmware: ${e.field} agora existe no .ini; remova de absentIn202008"
                continue
            }
            if (candidates.isEmpty()) {
                problems += "$firmware: campo ${e.field} não existe no .ini"
                continue
            }
            // 202008 declara trigPatternSec [0:7]; aceita o bit baixo igual e o alto >= ao usado.
            val ok = candidates.any {
                it.page == e.page && it.offset == e.offset &&
                    (e.bit == null || (it.bitLow == e.bit.first && (it.bitHigh == e.bit.last ||
                        (e.field == "trigPatternSec" && it.bitHigh == 7))))
            }
            if (!ok) {
                val found = candidates.joinToString { "p${it.page}@${it.offset}[${it.bitLow}:${it.bitHigh}]" }
                problems += "$firmware: ${e.field} o código usa p${e.page}@${e.offset}${e.bit?.let { b -> "[${b.first}:${b.last}]" } ?: ""}, o .ini diz $found"
            }
        }
        return problems
    }

    private fun assertContract(label: String, problems: List<String>) {
        if (problems.isNotEmpty()) fail("$label diverge do .ini:\n" + problems.joinToString("\n"))
        assertTrue(true)
    }

    @Test fun idleLayoutResolvedFromIni() {
        for (fw in listOf("speeduino_202008", "speeduino_202402", "speeduino_202501")) {
            val layout = checkNotNull(IdleControlLayout.fromIni(load(fw))) { "$fw: layout da marcha lenta não resolvido" }
            val expected = IdleControlLayout.DEFAULT.copy(pwmRun = if (fw == "speeduino_202008") null else 60)
            kotlin.test.assertEquals(expected, layout, fw)
        }
    }

    @Test fun idle() = assertContract("Marcha lenta", listOf("speeduino_202008", "speeduino_202402", "speeduino_202501").flatMap { check(it, idle) })
    @Test fun trigger() = assertContract("Trigger", listOf("speeduino_202008", "speeduino_202402", "speeduino_202501").flatMap { check(it, trigger) })
    @Test fun closedLoop() = assertContract("Malha fechada", listOf("speeduino_202008", "speeduino_202402", "speeduino_202501").flatMap { check(it, closedLoop) })
    @Test fun secondarySerial() = assertContract("Serial secundária", listOf("speeduino_202402", "speeduino_202501").flatMap { check(it, secondarySerial) })
    @Test fun engineProtection() = assertContract("Proteção do motor", listOf("speeduino_202402", "speeduino_202501").flatMap { check(it, engineProtection) })
}
