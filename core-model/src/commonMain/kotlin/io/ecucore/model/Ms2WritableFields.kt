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

    /** Escalares graváveis da MS2. Lista imutável: novas telas (ex.: marcha lenta) adicionam entradas aqui. */
    val scalars: List<MegaSpeedWritableField> = listOf()

    val curves: List<IniWritableCurve> = listOf(
        IniWritableCurve(WUE_BINS, "Enriquecimento de aquecimento", "%", 0.0, 255.0, 0),
        // Sempre a variante °F do .ini (bruto = °F x 10); a UI converte com [IniTemperature].
        IniWritableCurve(TEMP_TABLE, "Eixo de temperatura (CLT)", "°F", -40.0, 392.0, 1, unitSuffix = "F"),
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
