package io.ecucore.definition

import io.ecucore.model.ClosedLoopCorrectionMapper
import io.ecucore.model.EngineProtectionMapper
import io.ecucore.model.IdleControlSettings
import io.ecucore.model.SecondarySerialConfig
import io.ecucore.model.TriggerSettings

/** Um campo do .ini que o código usa com página/offset (e bits) fixos. */
data class FieldExpectation(
    val field: String,
    val page: Int,
    val offset: Int,
    val bit: IntRange? = null,
    /** Nome que o mesmo campo tem em firmwares antigos (mesma página/offset). */
    val alias: String? = null,
    /** Para arrays: quantidade de elementos (confere com `[N]` do .ini). */
    val length: Int? = null,
    /** Primeira versão do firmware cujo .ini tem o campo (usado pelo teste de contrato sobre os .ini embutidos). */
    val since: Int = 202008,
    /** Bit alto alternativo aceito (`trigPatternSec` é [0:7] no 202008/202012 e [0:6] depois; o bit 7 nunca é gravado). */
    val bitHighAlso: Int? = null,
) {
    fun matches(f: IniFieldDefinition): Boolean =
        f.page == page && f.offset == offset &&
            (length == null || f.shape?.totalSize == length) &&
            (bit == null || (f.bitLow == bit.first && (f.bitHigh == bit.last || f.bitHigh == bitHighAlso)))

    fun candidates(definition: IniDefinition): List<IniFieldDefinition> =
        definition.fields.filter { it.name == field || (alias != null && it.name == alias) }
}

/** Divergência entre o que o código escreve e o que o .ini do firmware conectado define. */
class IniLayoutMismatchException(message: String) : IllegalStateException(message)

object IniLayoutCheck {
    /**
     * Para cada campo esperado que o [definition] define, a página/offset/bits têm de bater. Campo ausente no .ini é
     * ignorado (firmware que ainda não o tem; quem decide isso é o bloqueio por versão), mas campo presente em OUTRO
     * lugar é conflito: gravar pelo offset do código atingiria um byte que o firmware usa para outra coisa.
     */
    fun mismatches(definition: IniDefinition, expectations: List<FieldExpectation>): List<String> =
        expectations.mapNotNull { e ->
            val found = e.candidates(definition)
            if (found.isEmpty() || found.any(e::matches)) null
            else "${e.field}: o código usa p${e.page}@${e.offset}${e.bit?.let { "[${it.first}:${it.last}]" } ?: ""}, " +
                "o .ini diz " + found.joinToString { "p${it.page}@${it.offset}[${it.bitLow}:${it.bitHigh}]" }
        }

    fun require(label: String, definition: IniDefinition, expectations: List<FieldExpectation>) {
        val bad = mismatches(definition, expectations)
        if (bad.isNotEmpty()) {
            throw IniLayoutMismatchException(
                "$label: o .ini ${definition.sourceName} (${definition.signature}) define campos em outro lugar; " +
                    "gravação recusada para não atingir bytes de outro campo: " + bad.take(6).joinToString("; ")
            )
        }
    }
}

/**
 * Página/offset/bits que as telas do Speeduino usam, conferidos contra o .ini de cada versão pelo
 * `SpeeduinoPageContractTest` (CI) e contra o .ini ATIVO antes de cada gravação (em campo, via [IniLayoutCheck]).
 */
object SpeeduinoLayoutExpectations {
    val IDLE = listOf(
        FieldExpectation("idleFreq", IdleControlSettings.PAGE_NUMBER, 47),
        FieldExpectation("iacPWMrun", IdleControlSettings.PAGE_NUMBER, 60, 1..1, since = 202201),
        FieldExpectation("iacCLValues", IdleControlSettings.PAGE_NUMBER, 64),
        FieldExpectation("iacBins", IdleControlSettings.PAGE_NUMBER, 94),
        FieldExpectation("iacAlgorithm", IdleControlSettings.PAGE_NUMBER, 116, 0..2),
        FieldExpectation("iacStepTime", IdleControlSettings.PAGE_NUMBER, 116, 3..5),
        FieldExpectation("iacChannels", IdleControlSettings.PAGE_NUMBER, 116, 6..6),
        FieldExpectation("iacPWMdir", IdleControlSettings.PAGE_NUMBER, 116, 7..7),
        FieldExpectation("iacFastTemp", IdleControlSettings.PAGE_NUMBER, 117),
        FieldExpectation("iacStepHome", IdleControlSettings.PAGE_NUMBER, 118),
        FieldExpectation("iacStepHyster", IdleControlSettings.PAGE_NUMBER, 119),
    )

    val TRIGGER = listOf(
        FieldExpectation("TrigAng", TriggerSettings.PAGE_NUMBER, 0),
        FieldExpectation("FixAng", TriggerSettings.PAGE_NUMBER, 2),
        FieldExpectation("CrankAng", TriggerSettings.PAGE_NUMBER, 3),
        FieldExpectation("TrigAngMul", TriggerSettings.PAGE_NUMBER, 4),
        FieldExpectation("TrigPattern", TriggerSettings.PAGE_NUMBER, 5, 3..7),
        FieldExpectation("TrigEdge", TriggerSettings.PAGE_NUMBER, 5, 0..0),
        FieldExpectation("trigPatternSec", TriggerSettings.PAGE_NUMBER, 8, 0..6, bitHighAlso = 7), // 202008 declara [0:7]
        FieldExpectation("SkipCycles", TriggerSettings.PAGE_NUMBER, 11),
        FieldExpectation("numTeeth", TriggerSettings.PAGE_NUMBER, 15),
        FieldExpectation("missingTeeth", TriggerSettings.PAGE_NUMBER, 16),
    )

    val CLOSED_LOOP = listOf(
        FieldExpectation("egoAlgorithm", ClosedLoopCorrectionMapper.PAGE_NUMBER, 0, 0..1),
        FieldExpectation("egoType", ClosedLoopCorrectionMapper.PAGE_NUMBER, 0, 2..3),
        FieldExpectation("egoKP", ClosedLoopCorrectionMapper.PAGE_NUMBER, 1),
        FieldExpectation("egoKI", ClosedLoopCorrectionMapper.PAGE_NUMBER, 2),
        FieldExpectation("egoKD", ClosedLoopCorrectionMapper.PAGE_NUMBER, 3),
        FieldExpectation("egoTemp", ClosedLoopCorrectionMapper.PAGE_NUMBER, 4),
        FieldExpectation("egoCount", ClosedLoopCorrectionMapper.PAGE_NUMBER, 5),
        FieldExpectation("egoLimit", ClosedLoopCorrectionMapper.PAGE_NUMBER, 7),
        FieldExpectation("ego_min_afr", ClosedLoopCorrectionMapper.PAGE_NUMBER, 8, alias = "ego_min"),
        FieldExpectation("ego_max_afr", ClosedLoopCorrectionMapper.PAGE_NUMBER, 9, alias = "ego_max"),
        FieldExpectation("ego_sdelay", ClosedLoopCorrectionMapper.PAGE_NUMBER, 10),
        FieldExpectation("egoRPM", ClosedLoopCorrectionMapper.PAGE_NUMBER, 11),
        FieldExpectation("egoTPSMax", ClosedLoopCorrectionMapper.PAGE_NUMBER, 12),
        FieldExpectation("egoMAPMax", ClosedLoopCorrectionMapper.MAP_WINDOW_PAGE_NUMBER, 112, since = 202402),
        FieldExpectation("egoMAPMin", ClosedLoopCorrectionMapper.MAP_WINDOW_PAGE_NUMBER, 113, since = 202402),
    )

    val SECONDARY_SERIAL = listOf(
        FieldExpectation("enable_secondarySerial", SecondarySerialConfig.PAGE_NUMBER, SecondarySerialConfig.OFFSET, 0..0),
    )

    val ENGINE_PROTECTION = listOf(
        FieldExpectation("boostCutEnabled", EngineProtectionMapper.PAGE_NUMBER, 6, 7..7),
        FieldExpectation("engineProtectType", EngineProtectionMapper.PAGE_NUMBER, 0, 6..7),
        FieldExpectation("hardCutType", EngineProtectionMapper.CUT_TYPE_PAGE_NUMBER, 26, 3..3),
        FieldExpectation("SoftRevLim", EngineProtectionMapper.RPM_PAGE_NUMBER, 19),
        FieldExpectation("SoftLimRetard", EngineProtectionMapper.RPM_PAGE_NUMBER, 20),
        FieldExpectation("SoftLimMax", EngineProtectionMapper.RPM_PAGE_NUMBER, 21),
        FieldExpectation("hardRevLim", EngineProtectionMapper.RPM_PAGE_NUMBER, 22, alias = "HardRevLim"),
        FieldExpectation("engineProtectMaxRPM", EngineProtectionMapper.RPM_PAGE_NUMBER, 120),
        FieldExpectation("hardRevMode", EngineProtectionMapper.HARD_REV_MODE_PAGE_NUMBER, 166, 0..1, since = 202207),
        FieldExpectation("afrProtectEnabled", EngineProtectionMapper.HARD_REV_MODE_PAGE_NUMBER, 185, 0..1, since = 202207),
        FieldExpectation("oilPressureProtEnbl", EngineProtectionMapper.OIL_PAGE_NUMBER, 135, 2..2),
    )

    val ENGINE_CONSTANTS = listOf(
        FieldExpectation("battVCorMode", 1, 3, 2..2),
        FieldExpectation("reqFuel", 1, 24),
        FieldExpectation("divider", 1, 25),
        FieldExpectation("alternate", 1, 26, 0..0),
        FieldExpectation("ignAlgorithm", 1, 26, 4..6),
        FieldExpectation("injOpen", 1, 27),
        FieldExpectation("injAng", 1, 28, length = 4),
        FieldExpectation("mapSample", 1, 36, 0..1),
        FieldExpectation("twoStroke", 1, 36, 2..2),
        FieldExpectation("injType", 1, 36, 3..3),
        FieldExpectation("nCylinders", 1, 36, 4..7),
        FieldExpectation("algorithm", 1, 37, 0..2),
        FieldExpectation("fixAngEnable", 1, 37, 3..3),
        FieldExpectation("nInjectors", 1, 37, 4..7),
        FieldExpectation("perToothIgn", 1, 38, 6..6),
        FieldExpectation("dutyLim", 1, 40),
        FieldExpectation("stoich", 1, 50),
        FieldExpectation("oddfire2", 1, 51),
        FieldExpectation("oddfire3", 1, 53),
        FieldExpectation("oddfire4", 1, 55),
    )

    val SENSOR_CALIBRATION = listOf(
        FieldExpectation("tpsMin", 1, 44),
        FieldExpectation("tpsMax", 1, 45),
        FieldExpectation("mapMin", 1, 46),
        FieldExpectation("mapMax", 1, 47),
        FieldExpectation("baroMin", 1, 64),
        FieldExpectation("baroMax", 1, 65),
        FieldExpectation("EMAPMin", 1, 67),
        FieldExpectation("EMAPMax", 1, 68),
    )

    val DWELL = listOf(FieldExpectation("dwellTable", 12, 160, length = 16, since = 202201))
}
