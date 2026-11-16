package io.ecucore.definition

import io.ecucore.model.ClosedLoopCorrectionMapper
import io.ecucore.model.EngineProtectionMapper
import io.ecucore.model.IdleControlLayout
import io.ecucore.model.IdleControlSettings
import io.ecucore.model.SecondarySerialConfig
import io.ecucore.model.SpeeduinoAntiTheft
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
        /** Para arrays: quantidade de elementos (confere com `[N]` do .ini). */
        val length: Int? = null,
        /** Primeira versão do firmware cujo .ini tem o campo; antes dela o campo TEM de estar ausente. */
        val since: Int = 202008,
    )

    private fun load(id: String): IniDefinition {
        val text = checkNotNull(javaClass.getResourceAsStream("/ini/$id.ini")) { "ini $id ausente" }
            .bufferedReader(Charsets.ISO_8859_1).readText()
        return IniParser.parse("$id.ini", text)
    }

    private val idle = listOf(
        Expect("idleFreq", IdleControlSettings.PAGE_NUMBER, 47),
        Expect("iacPWMrun", IdleControlSettings.PAGE_NUMBER, 60, 1..1, since = 202201),
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
        Expect("egoMAPMax", ClosedLoopCorrectionMapper.MAP_WINDOW_PAGE_NUMBER, 112, since = 202402),
        Expect("egoMAPMin", ClosedLoopCorrectionMapper.MAP_WINDOW_PAGE_NUMBER, 113, since = 202402),
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
        Expect("hardRevLim", EngineProtectionMapper.RPM_PAGE_NUMBER, 22, since = 202207),
        Expect("engineProtectMaxRPM", EngineProtectionMapper.RPM_PAGE_NUMBER, 120),
        Expect("hardRevMode", EngineProtectionMapper.HARD_REV_MODE_PAGE_NUMBER, 166, 0..1, since = 202207),
        Expect("afrProtectEnabled", EngineProtectionMapper.HARD_REV_MODE_PAGE_NUMBER, 185, 0..1, since = 202207),
        Expect("oilPressureProtEnbl", EngineProtectionMapper.OIL_PAGE_NUMBER, 135, 2..2),
    )

    private fun version(firmware: String) = firmware.substringAfterLast('_').toInt()

    private fun check(firmware: String, expected: List<Expect>): List<String> {
        val ini = load(firmware)
        val problems = mutableListOf<String>()
        for (e in expected) {
            val candidates = ini.fields.filter { it.name == e.field || it.name == e.alias }
            if (version(firmware) < e.since) {
                // O campo ainda não existe nesta versão: o código só pode usá-lo se bloquear por versão.
                if (candidates.isNotEmpty()) problems += "$firmware: ${e.field} já existe no .ini; ajuste since=${e.since}"
                continue
            }
            if (candidates.isEmpty()) {
                problems += "$firmware: campo ${e.field} não existe no .ini (since=${e.since})"
                continue
            }
            // 202008/202012 declaram trigPatternSec [0:7]; o código usa [0:6] (o bit 7 nunca é gravado como 1).
            val ok = candidates.any {
                it.page == e.page && it.offset == e.offset &&
                    (e.length == null || it.shape?.totalSize == e.length) &&
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


    /** Página 1: `EngineConstants.fromPage1`/`applyToPage1` (sem constante de página, o código usa 1 direto). */
    private val engineConstants = listOf(
        Expect("battVCorMode", 1, 3, 2..2),
        Expect("reqFuel", 1, 24),
        Expect("divider", 1, 25),
        Expect("alternate", 1, 26, 0..0),
        Expect("ignAlgorithm", 1, 26, 4..6),
        Expect("injOpen", 1, 27),
        Expect("injAng", 1, 28, length = 4),
        Expect("mapSample", 1, 36, 0..1),
        Expect("twoStroke", 1, 36, 2..2),
        Expect("injType", 1, 36, 3..3),
        Expect("nCylinders", 1, 36, 4..7),
        Expect("algorithm", 1, 37, 0..2),
        Expect("fixAngEnable", 1, 37, 3..3),
        Expect("nInjectors", 1, 37, 4..7),
        Expect("perToothIgn", 1, 38, 6..6),
        Expect("dutyLim", 1, 40),
        Expect("stoich", 1, 50),
        Expect("oddfire2", 1, 51),
        Expect("oddfire3", 1, 53),
        Expect("oddfire4", 1, 55),
    )

    /** `EcuClient.readPressureCalibration/writePressureCalibration` e `writeTpsCalibration` (página 1). */
    private val sensorCalibration = listOf(
        Expect("tpsMin", 1, 44),
        Expect("tpsMax", 1, 45),
        Expect("mapMin", 1, 46),
        Expect("mapMax", 1, 47),
        Expect("baroMin", 1, 64),
        Expect("baroMax", 1, 65),
        Expect("EMAPMin", 1, 67),
        Expect("EMAPMax", 1, 68),
    )

    /** `DwellTable` (página 12; o código lê/grava a matriz 4x4 a partir do offset 160). */
    private val dwell = listOf(Expect("dwellTable", 12, 160, length = 16, since = 202201))

    /** `Page6Validator` limita cada um destes bytes da página 6; tem de ser o campo certo e não outro. */
    private val page6Validator = listOf(
        Expect("ego_min_afr", 6, 8, alias = "ego_min"),
        Expect("ego_max_afr", 6, 9, alias = "ego_max"),
        Expect("ego_sdelay", 6, 10),
        Expect("egoRPM", 6, 11),
        Expect("egoTPSMax", 6, 12),
        Expect("brvBins", 6, 15, length = 6),
        Expect("airDenBins", 6, 27, length = 9),
        Expect("boostFreq", 6, 45),
        Expect("vvtFreq", 6, 46),
        Expect("idleFreq", 6, 47),
        Expect("lnchSoftLim", 6, 49),
        Expect("lnchRetard", 6, 50),
        Expect("lnchHardLim", 6, 51),
        Expect("lnchFuelAdd", 6, 52),
        Expect("boostLimit", 6, 56),
        Expect("boostKP", 6, 57),
        Expect("boostKI", 6, 58),
        Expect("boostKD", 6, 59),
        Expect("flatSSoftWin", 6, 61),
        Expect("flatSRetard", 6, 62),
        Expect("flatSArm", 6, 63),
        Expect("iacOLPWMVal", 6, 84, length = 10),
        Expect("iacBins", 6, 94, length = 10),
        Expect("iacCrankDuty", 6, 108, length = 4),
        Expect("iacCrankBins", 6, 112, length = 4),
        Expect("iacStepHome", 6, 118),
        Expect("iacStepHyster", 6, 119),
    )

    /** Campos do antifurto (página/offset escritos à mão em `SpeeduinoAntiTheft`). */
    private val antiTheft = listOf(
        SpeeduinoAntiTheft.REV_LIMIT, SpeeduinoAntiTheft.PROTECT_MIN_RPM, SpeeduinoAntiTheft.PROTECT_TYPE,
        SpeeduinoAntiTheft.REV_MODE, SpeeduinoAntiTheft.CUT_TYPE, SpeeduinoAntiTheft.MARKER_FIELD,
        SpeeduinoAntiTheft.REV_LIMIT_ORIGINAL_FIELD, SpeeduinoAntiTheft.PROTECT_MIN_RPM_ORIGINAL_FIELD,
    ).map { f -> Expect(f.name, f.page, f.offset, f.bitLow?.let { it until (it + f.bitCount) }) }

    /** 202008..202501: todos têm o layout "moderno" (página 1 = constantes do motor). O 201609 é tratado à parte. */
    private val allFirmwares = listOf(
        "speeduino_202008", "speeduino_202012", "speeduino_202201", "speeduino_202202", "speeduino_202207",
        "speeduino_202305", "speeduino_202310", "speeduino_202402", "speeduino_202501",
    )
    private val modernFirmwares = allFirmwares

    @Test fun engineConstants() = assertContract("Constantes do motor (página 1)", allFirmwares.flatMap { check(it, engineConstants) })
    @Test fun sensorCalibration() = assertContract("Calibração de TPS/pressão (página 1)", allFirmwares.flatMap { check(it, sensorCalibration) })
    @Test fun dwellTable() = assertContract("Tabela de dwell (página 12)", allFirmwares.flatMap { check(it, dwell) })
    @Test fun page6Validator() = assertContract("Page6Validator", allFirmwares.flatMap { check(it, page6Validator) })
    /** O antifurto só vale do 202207 em diante (usa hardRevLim/hardRevMode); antes disso a tela não se aplica. */
    @Test fun antiTheft() = assertContract("Antifurto", allFirmwares.filter { version(it) >= 202207 }.flatMap { check(it, antiTheft) })

    /**
     * DIVERGÊNCIA CONHECIDA (ainda não corrigida): `EngineConstants` lê e grava `batteryVoltageBins` (offset 15) e
     * `injectorVoltageCorrectionRates` (offset 21) na PÁGINA 1, mas o .ini os põe na PÁGINA 6 (`brvBins`, `injBatRates`).
     * Na página 1 esses offsets são `pinLayout`, `tachoPin`, `tachoDuration`, `maeThresh`, `taeThresh`, `aeTime`,
     * `taeMinChange`... Este teste fixa o que o .ini diz; quando o código for corrigido, troque por `Expect` em `engineConstants`.
     */
    @Test fun knownDivergence_batteryCorrectionLivesOnPage6() {
        for (fw in allFirmwares) {
            val ini = load(fw)
            for (name in listOf("brvBins", "injBatRates")) {
                val pages = ini.fields.filter { it.name == name }.map { it.page }.toSet()
                kotlin.test.assertEquals(setOf(6), pages, "$fw: $name deveria estar só na página 6 do .ini")
            }
        }
    }

    /**
     * As "eras" do código decidem se a tela lê/grava um campo. Aqui a era tem de concordar com o que cada .ini
     * realmente define; senão o app grava em bytes sem uso ou em outro campo.
     */
    @Test fun eraGatesAgreeWithTheIni() {
        val problems = mutableListOf<String>()
        for (fw in allFirmwares) {
            val ini = load(fw)
            val era = io.ecucore.model.EcuTableDefinitions.detectFirmwareEra("speeduino ${version(fw)}")
            fun has(name: String) = ini.fields.any { it.name == name }
            fun gate(label: String, code: Boolean, name: String) {
                if (code != has(name)) problems += "$fw ($era): $label=$code mas o .ini ${if (has(name)) "define" else "não define"} $name"
            }
            gate("hasDwellTable", era.hasDwellTable(), "dwellTable")
            gate("hasPage9Protection", EngineProtectionMapper.hasPage9Protection(era), "hardRevMode")
            gate("hasPage9Protection(afr)", EngineProtectionMapper.hasPage9Protection(era), "afrProtectEnabled")
            gate("hasMapWindowPage", ClosedLoopCorrectionMapper.hasMapWindowPage(era), "egoMAPMax")
        }
        // DIVERGÊNCIA CONHECIDA (não corrigida): 202201 e 202202 caem na mesma era do 202207 (MODERN_2022), mas só o 202207
        // define hardRevMode/afrProtectEnabled (p9@166/185) e hardRevLim (p4@22); nas duas primeiras esses bytes são `unused`.
        // A tela de proteção mostra/grava valores sem significado nessas duas versões. Corrigir exige decidir pela presença
        // do campo no .ini ativo (ou por uma era mais fina). Quando for corrigido, este teste falha e `known` deve sair.
        val known = setOf(202201, 202202).flatMap { v ->
            listOf("speeduino_$v (MODERN_2022): hasPage9Protection=true mas o .ini não define hardRevMode",
                "speeduino_$v (MODERN_2022): hasPage9Protection(afr)=true mas o .ini não define afrProtectEnabled")
        }.toSet()
        val unexpected = problems.filterNot { it in known }
        val fixed = known.filterNot { it in problems }
        if (unexpected.isNotEmpty()) fail("Era do código diverge do .ini:\n" + unexpected.joinToString("\n"))
        if (fixed.isNotEmpty()) fail("Divergência conhecida foi corrigida; remova de `known`:\n" + fixed.joinToString("\n"))
    }

    /**
     * Premissa dos bloqueios do 201609 em `EcuClient.requireModernPageLayout`: nesse firmware a página 1 é a tabela VE e
     * as constantes do motor, calibrações e marcha lenta estão em outras páginas, com outro layout.
     */
    @Test fun firmware201609HasADifferentPageLayout() {
        val ini = load("speeduino_201609")
        fun page(name: String) = ini.fields.filter { it.name == name }.map { it.page }.toSet()
        kotlin.test.assertEquals(setOf(1), page("veTable"), "página 1 = VE no 201609")
        for (name in listOf("reqFuel", "divider", "injOpen", "nCylinders", "tpsMin", "tpsMax", "mapMin", "mapMax")) {
            kotlin.test.assertEquals(setOf(2), page(name), "$name no 201609 fica na página 2, não na 1")
        }
        kotlin.test.assertEquals(setOf(7), page("iacAlgorithm"), "marcha lenta do 201609 fica na página 7")
        for (name in listOf("baroMin", "EMAPMin", "numTeeth", "dwellTable", "enable_secondarySerial")) {
            kotlin.test.assertTrue(page(name).isEmpty(), "$name não existe no 201609")
        }
        kotlin.test.assertEquals(setOf(2), setOf(ini.fields.first { it.name == "algorithm" }.page), "algorithm no 201609 está na página 2")
    }

    @Test fun idleLayoutResolvedFromIni() {
        for (fw in allFirmwares) {
            val layout = checkNotNull(IdleControlLayout.fromIni(load(fw))) { "$fw: layout da marcha lenta não resolvido" }
            val expected = IdleControlLayout.DEFAULT.copy(pwmRun = if (version(fw) < 202201) null else 60)
            kotlin.test.assertEquals(expected, layout, fw)
        }
    }

    @Test fun idle() = assertContract("Marcha lenta", allFirmwares.flatMap { check(it, idle) })
    @Test fun trigger() = assertContract("Trigger", allFirmwares.flatMap { check(it, trigger) })
    @Test fun closedLoop() = assertContract("Malha fechada", allFirmwares.flatMap { check(it, closedLoop) })
    @Test fun secondarySerial() = assertContract("Serial secundária", allFirmwares.flatMap { check(it, secondarySerial) })
    @Test fun engineProtection() = assertContract("Proteção do motor", allFirmwares.flatMap { check(it, engineProtection) })
}
