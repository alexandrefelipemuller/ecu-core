package io.ecucore.definition

import io.ecucore.model.BatteryCorrectionLayout
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

private typealias Expect = FieldExpectation

/**
 * Contrato entre as telas do Speeduino com página/offset fixos no código e o .ini (fonte da verdade).
 *
 * Origem: a marcha lenta usava as páginas 4 e 7 quando o .ini diz 6, e nenhum teste conferia isso
 * (a ECU falsa aceitava qualquer página). Cada linha abaixo é (campo do .ini, página e offset que o código usa);
 * se o .ini divergir, o teste falha dizendo qual campo e em qual firmware.
 */
class SpeeduinoPageContractTest {

    private val idle = SpeeduinoLayoutExpectations.IDLE
    private val trigger = SpeeduinoLayoutExpectations.TRIGGER
    private val closedLoop = SpeeduinoLayoutExpectations.CLOSED_LOOP
    private val secondarySerial = SpeeduinoLayoutExpectations.SECONDARY_SERIAL
    private val engineProtection = SpeeduinoLayoutExpectations.ENGINE_PROTECTION
    private val engineConstants = SpeeduinoLayoutExpectations.ENGINE_CONSTANTS
    private val sensorCalibration = SpeeduinoLayoutExpectations.SENSOR_CALIBRATION
    private val dwell = SpeeduinoLayoutExpectations.DWELL

    private fun load(id: String): IniDefinition {
        val text = checkNotNull(javaClass.getResourceAsStream("/ini/$id.ini")) { "ini $id ausente" }
            .bufferedReader(Charsets.ISO_8859_1).readText()
        return IniParser.parse("$id.ini", text)
    }





    /** Campos que só existem do 202402 em diante (o 202008 não os tem). */

    private fun version(firmware: String) = firmware.substringAfterLast('_').toInt()

    private fun check(firmware: String, expected: List<Expect>): List<String> {
        val ini = load(firmware)
        val problems = mutableListOf<String>()
        for (e in expected) {
            val candidates = e.candidates(ini)
            if (version(firmware) < e.since) {
                // O campo ainda não existe nesta versão: o código só pode usá-lo se bloquear por versão.
                if (candidates.isNotEmpty()) problems += "$firmware: ${e.field} já existe no .ini; ajuste since=${e.since}"
                continue
            }
            if (candidates.isEmpty()) {
                problems += "$firmware: campo ${e.field} não existe no .ini (since=${e.since})"
                continue
            }
            val ok = candidates.any(e::matches)
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

    /** `EcuClient.readPressureCalibration/writePressureCalibration` e `writeTpsCalibration` (página 1). */

    /** `DwellTable` (página 12; o código lê/grava a matriz 4x4 a partir do offset 160). */

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

    /** `brvBins`/`injBatRates` (correção de bateria): página 6 do .ini, resolvida por [BatteryCorrectionLayout]. */
    @Test fun batteryCorrectionLayoutResolvedFromIni() {
        for (fw in allFirmwares) {
            val layout = checkNotNull(BatteryCorrectionLayout.fromIni(load(fw))) { "$fw: layout da correção de bateria não resolvido" }
            kotlin.test.assertEquals(BatteryCorrectionLayout.DEFAULT, layout, fw)
        }
        // O 201609 também define os campos (página 6, 64 bytes), mas a tela de constantes é bloqueada nele.
        kotlin.test.assertEquals(64, BatteryCorrectionLayout.fromIni(load("speeduino_201609"))?.pageLength)
    }

    /**
     * As "eras" do código decidem se a tela lê/grava um campo. Aqui a decisão tem de concordar com o que cada .ini
     * realmente define; senão o app grava em bytes sem uso ou em outro campo. A proteção do motor decide pela presença
     * do campo no .ini ativo (`hasPage9Protection(era, definition)`), porque a era é grossa demais (ver o teste abaixo).
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
            gate("hasPage9Protection(ini)", EngineProtectionMapper.hasPage9Protection(era, ini), "hardRevMode")
            gate("hasPage9Protection(ini,afr)", EngineProtectionMapper.hasPage9Protection(era, ini), "afrProtectEnabled")
            gate("hasMapWindowPage", ClosedLoopCorrectionMapper.hasMapWindowPage(era), "egoMAPMax")
        }
        if (problems.isNotEmpty()) fail("Decisão do código diverge do .ini:\n" + problems.joinToString("\n"))
    }

    /**
     * Limite da reserva por era (usada só sem .ini carregado): 202201 e 202202 caem na mesma era do 202207, mas só o 202207
     * define hardRevMode/afrProtectEnabled. Se este teste quebrar, a era ficou mais fina e a reserva pode ser simplificada.
     */
    @Test fun eraOnlyFallbackIsCoarseFor202201And202202() {
        for (fw in allFirmwares) {
            val ini = load(fw)
            val era = io.ecucore.model.EcuTableDefinitions.detectFirmwareEra("speeduino ${version(fw)}")
            val byEra = EngineProtectionMapper.hasPage9Protection(era)
            val byIni = EngineProtectionMapper.hasPage9Protection(era, ini)
            val expectedToDiffer = version(fw) in setOf(202201, 202202)
            kotlin.test.assertEquals(expectedToDiffer, byEra != byIni, "$fw: era=$byEra ini=$byIni")
        }
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

    /**
     * As tabelas VE/ignição/AFR do Speeduino usam `EcuTableDefinitions` (página/offset por era, escritos à mão) quando
     * nenhum .ini as substitui. Elas têm de bater com o .ini de TODAS as versões, incluindo o 201609 (layout próprio).
     */
    @Test fun tableDefinitionsMatchTheIniOfEveryVersion() {
        val problems = mutableListOf<String>()
        val versions = listOf("speeduino_201609") + allFirmwares
        for (fw in versions) {
            val ini = load(fw)
            val defs = io.ecucore.model.EcuTableDefinitions.getDefinitions("speeduino ${version(fw)}")
            fun field(vararg names: String) = ini.fields.firstOrNull { f -> f.name in names && f.page != null }
            fun check(label: String, meta: io.ecucore.model.TableMetadata, table: Array<String>, rpm: Array<String>, load: Array<String>) {
                val t = field(*table)
                val r = field(*rpm)
                val l = field(*load)
                if (t == null) { problems += "$fw: $label sem ${table.toList()} no .ini"; return }
                if (meta.page != t.page) problems += "$fw: $label página código=${meta.page} ini=${t.page}"
                val tableOffset = t.offset
                if (tableOffset != null && meta.offset + meta.valuesOffset != tableOffset)
                    problems += "$fw: $label valores código=${meta.offset + meta.valuesOffset} ini=$tableOffset"
                if (r != null && r.page == t.page && r.offset != null && meta.offset + meta.rpmBinsOffset != r.offset)
                    problems += "$fw: $label rpmBins código=${meta.offset + meta.rpmBinsOffset} ini=${r.offset}"
                if (l != null && l.page == t.page && l.offset != null && meta.offset + meta.loadBinsOffset != l.offset)
                    problems += "$fw: $label loadBins código=${meta.offset + meta.loadBinsOffset} ini=${l.offset}"
            }
            check("VE", defs.veTable, arrayOf("veTable"), arrayOf("rpmBins"), arrayOf("fuelLoadBins", "mapBins"))
            check("Ignição", defs.ignitionTable, arrayOf("advTable1"), arrayOf("rpmBins2"), arrayOf("mapBins1", "loadBins2"))
            check("AFR", defs.afrTable, arrayOf("afrTable"), arrayOf("rpmBinsAFR"), arrayOf("loadBinsAFR"))
        }
        if (problems.isNotEmpty()) fail("Definições de tabela divergem do .ini:\n" + problems.joinToString("\n"))
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
