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

    val scalars: MutableList<MegaSpeedWritableField> = mutableListOf()

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
}

/** Validação do Warm-Up Enrichment da MS2: `wueBins` (%) x `tempTable` (°F). */
object Ms2WueValidator {
    const val POINTS = 10

    /** Retorna a lista de erros (vazia = ok). [tempsF] em °F. */
    fun validate(wuePercent: List<Double>, tempsF: List<Double>): List<String> {
        val errors = mutableListOf<String>()
        if (wuePercent.size != POINTS) errors += "A curva de WUE deve ter $POINTS pontos"
        if (tempsF.size != POINTS) errors += "O eixo de temperatura deve ter $POINTS pontos"
        if (wuePercent.any { !it.isFinite() || it < 0.0 || it > 255.0 }) errors += "WUE deve estar entre 0% e 255%"
        if (wuePercent.size == POINTS && kotlin.math.round(wuePercent.last()).toLong() != 100L) {
            errors += "O último ponto do WUE deve ser 100%"
        }
        if (tempsF.any { !it.isFinite() }) errors += "Temperatura inválida"
        else if (tempsF.zipWithNext().any { (a, b) -> b <= a }) errors += "O eixo de temperatura deve ser estritamente crescente"
        return errors
    }
}
