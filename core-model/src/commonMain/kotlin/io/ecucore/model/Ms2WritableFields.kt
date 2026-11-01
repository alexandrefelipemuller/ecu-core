package io.ecucore.model

/** Curva 1D do .ini (array de U08/S08/U16/S16 big-endian) que o app pode gravar. Limites na unidade do .ini (ex.: tempTable em °F). */
data class IniWritableCurve(
    val name: String,
    val label: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val decimals: Int,
    /** Variante do .ini a usar quando o nome é definido duas vezes (`#if CELSIUS`): sufixo da unidade, ex. "F". */
    val unitSuffix: String? = null,
)

/**
 * Campos/curvas graváveis da família MS2 (MicroSquirt, MS2/Extra). Escalares ficam em [scalars]
 * (mesmo formato dos da MegaSpeed; novas telas, ex. marcha lenta, adicionam aqui) e curvas em [curves].
 */
object Ms2WritableFields {
    const val WUE_BINS = "wueBins"
    const val TEMP_TABLE = "tempTable"

    // Marcha lenta (página 0x04 do .ini, salvo indicação)
    const val IDLE_CTL = "IdleCtl"
    const val IDLE_CTL_ALG = "IdleCtl_alg"
    const val IDLE_CTL_HOME = "IdleCtl_home"
    const val IAC_TSTEP = "IACtstep"
    const val IAC_MINSTEP = "IACminstep"
    const val IAC_START = "IACStart"
    const val IAC_CRANK_XT = "IACcrankxt"
    const val IDLE_HYST = "IdleHyst"
    const val IAC_FULL_OPEN = "iacfullopen"
    /** Página 0x05 do .ini. */
    const val PWMIDLE_OPEN_STEPS = "pwmidle_open_steps"
    const val PWMIDLE_CLOSED_STEPS = "pwmidle_closed_steps"
    const val IAC_STEP_TABLE = "iacstepTable"
    const val PWMIDLE_TARGET_RPMS = "pwmidle_target_rpms"
    const val PWMIDLE_CLT_TEMPS = "pwmidle_clt_temps"

    /** Escalares graváveis da MS2. Lista imutável: novas telas (ex.: marcha lenta) adicionam entradas aqui. */
    val scalars: List<MegaSpeedWritableField> = listOf(
        MegaSpeedWritableField(IDLE_CTL, "Tipo de atuador de lenta (0=Nenhum, 1=On/Off, 2=PWM, 3=Passo)", "", 0.0, 3.0, 0),
        MegaSpeedWritableField(IDLE_CTL_ALG, "Algoritmo (0=Malha aberta, 1=Malha fechada)", "", 0.0, 1.0, 0),
        MegaSpeedWritableField(IDLE_CTL_HOME, "Direção de retorno (0=Fechado, 1=Aberto)", "", 0.0, 1.0, 0),
        MegaSpeedWritableField(IAC_TSTEP, "Tempo entre passos", "ms", 1.0, 255.0, 0),
        MegaSpeedWritableField(IAC_MINSTEP, "Mínimo de passos para mover", "passos", 1.0, 255.0, 0),
        MegaSpeedWritableField(IAC_START, "Passos de retorno (homing)", "passos", 0.0, 4000.0, 0),
        MegaSpeedWritableField(IAC_CRANK_XT, "Transição partida para funcionamento", "s", 0.0, 600.0, 0),
        // Sempre a variante °C do .ini (primeira definição, escala 0,05555): histerese é um delta.
        MegaSpeedWritableField(IDLE_HYST, "Histerese de temperatura", "°C", 0.0, 20.0, 1),
        MegaSpeedWritableField(IAC_FULL_OPEN, "Passos bem abertos", "passos", 0.0, 4000.0, 0),
        MegaSpeedWritableField(PWMIDLE_OPEN_STEPS, "Passos do atuador aberto (malha fechada)", "passos", 0.0, 255.0, 0),
        MegaSpeedWritableField(PWMIDLE_CLOSED_STEPS, "Passos do atuador fechado (malha fechada)", "passos", 0.0, 255.0, 0),
    )

    val curves: List<IniWritableCurve> = listOf(
        IniWritableCurve(WUE_BINS, "Enriquecimento de aquecimento", "%", 0.0, 255.0, 0),
        // Sempre a variante °F do .ini (bruto = °F x 10); a UI converte com [IniTemperature].
        IniWritableCurve(TEMP_TABLE, "Eixo de temperatura (CLT)", "°F", -40.0, 392.0, 1, unitSuffix = "F"),
        IniWritableCurve(IAC_STEP_TABLE, "Passos de lenta no aquecimento", "passos", 0.0, 500.0, 0),
        IniWritableCurve(PWMIDLE_TARGET_RPMS, "RPM alvo da lenta", "RPM", 500.0, 2000.0, 0),
        IniWritableCurve(PWMIDLE_CLT_TEMPS, "Eixo de temperatura da lenta (CLT)", "°F", -40.0, 392.0, 1, unitSuffix = "F"),
    )

    fun byName(name: String): MegaSpeedWritableField? = scalars.firstOrNull { it.name == name }
    fun curveByName(name: String): IniWritableCurve? = curves.firstOrNull { it.name == name }
}

/** Conversão °F (unidade lida/gravada da `tempTable` do .ini) para °C da UI. */
object IniTemperature {
    fun fahrenheitToCelsius(f: Double): Double = (f - 32.0) * 5.0 / 9.0
    fun celsiusToFahrenheit(c: Double): Double = c * 9.0 / 5.0 + 32.0
    fun fahrenheitToCelsius(values: List<Double>): List<Double> = values.map(::fahrenheitToCelsius)
    fun celsiusToFahrenheit(values: List<Double>): List<Double> = values.map(::celsiusToFahrenheit)

    /**
     * Converte a curva editada em °C de volta para °F preservando EXATAMENTE o bruto lido nos pontos
     * não editados: um ponto só é convertido se [editedC] difere do original exibido com [decimals] casas.
     */
    fun mergeEditedCelsius(originalF: List<Double>, editedC: List<Double>, decimals: Int = 1): List<Double> {
        require(originalF.size == editedC.size) { "Tamanhos diferentes" }
        val p = Math10.pow10(decimals)
        return originalF.indices.map { i ->
            val shown = kotlin.math.round(fahrenheitToCelsius(originalF[i]) * p) / p
            if (kotlin.math.abs(editedC[i] - shown) < 1e-6) originalF[i]
            else kotlin.math.round(celsiusToFahrenheit(editedC[i]) * 10.0) / 10.0
        }
    }

    private object Math10 {
        fun pow10(n: Int): Double { var r = 1.0; repeat(n) { r *= 10.0 }; return r }
    }
}

/** Erro de validação do WUE; [index] é o índice (0-based) do ponto quando aplicável. */
data class Ms2WueError(val code: Code, val index: Int? = null) {
    enum class Code { WUE_POINT_COUNT, TEMP_POINT_COUNT, WUE_OUT_OF_RANGE, WUE_LAST_NOT_100, TEMP_INVALID, TEMP_NOT_INCREASING }
}

/** Validação do Warm-Up Enrichment da MS2: `wueBins` (%) x `tempTable` (°F). */
object Ms2WueValidator {
    const val POINTS = 10

    /** Retorna os erros (vazio = ok). [tempsF] em °F. */
    fun validate(wuePercent: List<Double>, tempsF: List<Double>): List<Ms2WueError> {
        val errors = mutableListOf<Ms2WueError>()
        if (wuePercent.size != POINTS) errors += Ms2WueError(Ms2WueError.Code.WUE_POINT_COUNT)
        if (tempsF.size != POINTS) errors += Ms2WueError(Ms2WueError.Code.TEMP_POINT_COUNT)
        wuePercent.forEachIndexed { i, v ->
            if (!v.isFinite() || v < 0.0 || v > 255.0) errors += Ms2WueError(Ms2WueError.Code.WUE_OUT_OF_RANGE, i)
        }
        if (wuePercent.size == POINTS && kotlin.math.round(wuePercent.last()).toLong() != 100L) {
            errors += Ms2WueError(Ms2WueError.Code.WUE_LAST_NOT_100, POINTS - 1)
        }
        tempsF.forEachIndexed { i, v -> if (!v.isFinite()) errors += Ms2WueError(Ms2WueError.Code.TEMP_INVALID, i) }
        if (tempsF.all { it.isFinite() }) {
            tempsF.zipWithNext().forEachIndexed { i, (a, b) ->
                if (b <= a) errors += Ms2WueError(Ms2WueError.Code.TEMP_NOT_INCREASING, i + 1)
            }
        }
        return errors
    }
}

/** Erro de validação da marcha lenta com motor de passo; [index] é 0-based quando aplicável. */
data class Ms2IdleError(val code: Code, val index: Int? = null) {
    enum class Code {
        IAC_STEP_POINT_COUNT, TARGET_RPM_POINT_COUNT, TARGET_TEMP_POINT_COUNT, VALUE_INVALID,
        IAC_START_NOT_ABOVE_FULL_OPEN, IAC_STEP_ABOVE_LIMIT, OPEN_STEPS_ABOVE_LIMIT, CLOSED_STEPS_ABOVE_LIMIT,
        CLOSED_NOT_BELOW_OPEN, TARGET_RPM_OUT_OF_RANGE, IAC_TEMP_NOT_INCREASING, TARGET_TEMP_NOT_INCREASING,
    }
}

/** Valores a validar; `null` = parte não aplicável/não editada (ignorada). Temperaturas em °F. */
data class Ms2IdleInput(
    val iacStart: Double? = null,
    /** Só informe quando aplicável (retorno para aberto, `IdleCtl_home` = 1). */
    val iacFullOpen: Double? = null,
    val openSteps: Double? = null,
    val closedSteps: Double? = null,
    val iacStepTable: List<Double>? = null,
    val iacStepTempsF: List<Double>? = null,
    val targetRpms: List<Double>? = null,
    val targetTempsF: List<Double>? = null,
)

/** Validação da marcha lenta por motor de passo da MS2. */
object Ms2IdleValidator {
    const val IAC_STEP_POINTS = 10
    const val TARGET_POINTS = 8
    const val DEFAULT_MAX_STEPS = 500.0
    const val MIN_TARGET_RPM = 500.0
    const val MAX_TARGET_RPM = 2000.0

    fun validate(input: Ms2IdleInput): List<Ms2IdleError> {
        val e = mutableListOf<Ms2IdleError>()
        fun bad(v: Double?) = v != null && !v.isFinite()
        val limit = input.iacFullOpen?.takeIf { it.isFinite() } ?: DEFAULT_MAX_STEPS

        if (bad(input.iacStart) || bad(input.iacFullOpen) || bad(input.openSteps) || bad(input.closedSteps)) {
            e += Ms2IdleError(Ms2IdleError.Code.VALUE_INVALID)
        }
        val start = input.iacStart; val full = input.iacFullOpen
        if (start != null && full != null && start.isFinite() && full.isFinite() && start <= full) {
            e += Ms2IdleError(Ms2IdleError.Code.IAC_START_NOT_ABOVE_FULL_OPEN)
        }
        input.openSteps?.takeIf { it.isFinite() && it > limit }?.let { e += Ms2IdleError(Ms2IdleError.Code.OPEN_STEPS_ABOVE_LIMIT) }
        input.closedSteps?.takeIf { it.isFinite() && it > limit }?.let { e += Ms2IdleError(Ms2IdleError.Code.CLOSED_STEPS_ABOVE_LIMIT) }
        val o = input.openSteps; val c = input.closedSteps
        if (o != null && c != null && o.isFinite() && c.isFinite() && c >= o) {
            e += Ms2IdleError(Ms2IdleError.Code.CLOSED_NOT_BELOW_OPEN)
        }
        input.iacStepTable?.let { t ->
            if (t.size != IAC_STEP_POINTS) e += Ms2IdleError(Ms2IdleError.Code.IAC_STEP_POINT_COUNT)
            t.forEachIndexed { i, v ->
                if (!v.isFinite()) e += Ms2IdleError(Ms2IdleError.Code.VALUE_INVALID, i)
                else if (v < 0.0 || v > limit) e += Ms2IdleError(Ms2IdleError.Code.IAC_STEP_ABOVE_LIMIT, i)
            }
        }
        input.iacStepTempsF?.let { increasing(it, IAC_STEP_POINTS, Ms2IdleError.Code.IAC_STEP_POINT_COUNT, Ms2IdleError.Code.IAC_TEMP_NOT_INCREASING, e) }
        input.targetRpms?.let { t ->
            if (t.size != TARGET_POINTS) e += Ms2IdleError(Ms2IdleError.Code.TARGET_RPM_POINT_COUNT)
            t.forEachIndexed { i, v ->
                if (!v.isFinite() || v < MIN_TARGET_RPM || v > MAX_TARGET_RPM) e += Ms2IdleError(Ms2IdleError.Code.TARGET_RPM_OUT_OF_RANGE, i)
            }
        }
        input.targetTempsF?.let { increasing(it, TARGET_POINTS, Ms2IdleError.Code.TARGET_TEMP_POINT_COUNT, Ms2IdleError.Code.TARGET_TEMP_NOT_INCREASING, e) }
        return e
    }

    private fun increasing(t: List<Double>, n: Int, countCode: Ms2IdleError.Code, code: Ms2IdleError.Code, e: MutableList<Ms2IdleError>) {
        if (t.size != n) e += Ms2IdleError(countCode)
        t.forEachIndexed { i, v -> if (!v.isFinite()) e += Ms2IdleError(Ms2IdleError.Code.VALUE_INVALID, i) }
        if (t.all { it.isFinite() }) t.zipWithNext().forEachIndexed { i, (a, b) -> if (b <= a) e += Ms2IdleError(code, i + 1) }
    }
}
