package io.ecucore.transport.exploration

import kotlin.math.abs

/**
 * Descobre fórmulas de sinais OEM sem ML: para cada identificador relido nas rodadas
 * [RoundSource.RESAMPLE], pareia cada leitura com a última referência lida antes dela
 * (modo 01, ou o espelho UDS F4xx quando a ECU não fala modo 01) e testa todo (offset × fórmula conhecida). Um candidato só vale se bater
 * com a referência em **todas** as amostras, dentro de uma tolerância apertada.
 *
 * Cada grandeza de referência recebe no máximo um sinal e cada (id, offset) é usado uma
 * vez só (com motor frio ECT ≈ IAT, o mesmo byte bateria com as duas).
 * O resultado ainda passa pela verificação final da [ExplorationSession].
 */
object SignalFitter {

    data class Formula(
        val byteLength: Int,
        val scale: Double,
        val offset: Double,
        val signed: Boolean = false,
    )

    private val TEMPERATURE = listOf(
        Formula(1, 1.0, -40.0),
        Formula(1, 0.75, -48.0),
        Formula(1, 1.0, -50.0),
        Formula(2, 0.1, -40.0),
        Formula(2, 0.1, -273.1),
        Formula(2, 0.1, 0.0, signed = true),
    )

    val FORMULAS: Map<ReferenceQuantity, List<Formula>> = mapOf(
        ReferenceQuantity.RPM to listOf(Formula(2, 0.25, 0.0), Formula(2, 1.0, 0.0), Formula(2, 0.5, 0.0), Formula(2, 0.125, 0.0)),
        ReferenceQuantity.COOLANT_TEMP to TEMPERATURE,
        ReferenceQuantity.INTAKE_TEMP to TEMPERATURE,
        ReferenceQuantity.VEHICLE_SPEED to listOf(Formula(1, 1.0, 0.0), Formula(2, 0.01, 0.0), Formula(2, 0.1, 0.0), Formula(2, 1.0 / 128, 0.0)),
        ReferenceQuantity.MODULE_VOLTAGE to listOf(
            Formula(1, 0.1, 0.0), Formula(1, 0.08, 0.0), Formula(1, 0.0625, 0.0),
            Formula(2, 0.001, 0.0), Formula(2, 0.01, 0.0), Formula(2, 1.0 / 256, 0.0),
        ),
    )

    private val UNITS = mapOf(
        ReferenceQuantity.RPM to "rpm",
        ReferenceQuantity.COOLANT_TEMP to "°C",
        ReferenceQuantity.INTAKE_TEMP to "°C",
        ReferenceQuantity.VEHICLE_SPEED to "km/h",
        ReferenceQuantity.MODULE_VOLTAGE to "V",
    )

    /** Tolerância do ajuste — mais apertada que a da verificação, para reduzir coincidência. */
    fun tolerance(quantity: ReferenceQuantity, reference: Double): Double = when (quantity) {
        ReferenceQuantity.RPM -> maxOf(abs(reference) * 0.02, 25.0)
        ReferenceQuantity.COOLANT_TEMP, ReferenceQuantity.INTAKE_TEMP -> 1.5
        ReferenceQuantity.VEHICLE_SPEED -> 2.0
        ReferenceQuantity.MODULE_VOLTAGE -> maxOf(abs(reference) * 0.03, 0.15)
    }

    const val MIN_SAMPLES = 2

    private data class Sample(val data: List<Int>, val references: Map<ReferenceQuantity, Double>)

    private data class Match(
        val request: ProbeRequest,
        val quantity: ReferenceQuantity,
        val byteOffset: Int,
        val formula: Formula,
        val samples: Int,
        val distinctRaw: Int,
        val maxError: Double,
    )

    fun fit(history: List<ExplorationRound>): CandidateProfile {
        val samples = collectSamples(history)
        val matches = samples.flatMap { (request, list) -> matchesFor(request, list) }
        val ranked = matches.sortedWith(
            compareByDescending<Match> { it.distinctRaw }
                .thenByDescending { it.samples }
                .thenBy { it.maxError / tolerance(it.quantity, 1.0) }
                .thenBy { it.formula.byteLength },
        )
        val usedQuantities = mutableSetOf<ReferenceQuantity>()
        val usedBytes = mutableSetOf<String>()
        val signals = mutableListOf<CandidateSignal>()
        for (m in ranked) {
            if (m.quantity in usedQuantities) continue
            val key = ExplorationPlanValidator.probeKey(m.request)
            val bytes = (m.byteOffset until m.byteOffset + m.formula.byteLength).map { "$key#$it" }
            if (bytes.any { it in usedBytes }) continue
            usedQuantities += m.quantity
            usedBytes += bytes
            signals += CandidateSignal(
                name = "oem_${m.quantity.name.lowercase()}",
                service = m.request.service,
                id = m.request.id,
                header = m.request.header,
                byteOffset = m.byteOffset,
                byteLength = m.formula.byteLength,
                signed = m.formula.signed,
                scale = m.formula.scale,
                offset = m.formula.offset,
                unit = UNITS[m.quantity],
                reference = m.quantity,
            )
        }
        val ambiguity = matches.groupBy { it.quantity }.mapValues { it.value.size }
        val notes = buildString {
            append("fitter: ids=${samples.size} matches=${matches.size}")
            if (ambiguity.isNotEmpty()) {
                append(" candidates=")
                append(ambiguity.entries.joinToString(",") { "${it.key.name.lowercase()}:${it.value}" })
            }
        }
        return CandidateProfile(signals = signals, notes = notes)
    }

    /** Agrupa leituras positivas por identificador, cada uma com as referências vigentes. */
    private fun collectSamples(history: List<ExplorationRound>): Map<ProbeRequest, List<Sample>> {
        val result = linkedMapOf<String, Pair<ProbeRequest, MutableList<Sample>>>()
        for (round in history.filter { it.source == RoundSource.RESAMPLE }) {
            val current = mutableMapOf<ReferenceQuantity, Double>()
            for (outcome in round.outcomes) {
                if (outcome.status != ProbeStatus.POSITIVE) continue
                val request = outcome.request
                val reference = ReferenceQuantity.forRequest(request)
                if (reference != null || request.service == ReadService.OBD_MODE_01 || ReferenceQuantity.isMirrorDid(request)) {
                    reference?.decodeMode01(outcome.data)?.let { current[reference] = it }
                    continue
                }
                if (outcome.data.isEmpty()) continue
                val key = ExplorationPlanValidator.probeKey(request)
                val entry = result.getOrPut(key) { request.copy(hypothesis = null) to mutableListOf() }
                entry.second += Sample(outcome.data, current.toMap())
            }
        }
        return result.values.associate { it.first to it.second.toList() }
    }

    private fun matchesFor(request: ProbeRequest, samples: List<Sample>): List<Match> {
        if (samples.size < MIN_SAMPLES) return emptyList()
        val minLength = samples.minOf { it.data.size }
        val out = mutableListOf<Match>()
        for ((quantity, formulas) in FORMULAS) {
            val refs = samples.map { it.references[quantity] }
            if (refs.any { it == null }) continue
            val references = refs.filterNotNull()
            val trivialZero = quantity == ReferenceQuantity.RPM || quantity == ReferenceQuantity.VEHICLE_SPEED
            if (trivialZero && references.all { it == 0.0 }) continue
            for (formula in formulas) {
                for (offset in 0..minLength - formula.byteLength) {
                    val signal = CandidateSignal(
                        name = "", service = request.service, id = request.id,
                        byteOffset = offset, byteLength = formula.byteLength,
                        signed = formula.signed, scale = formula.scale, offset = formula.offset,
                    )
                    var maxError = 0.0
                    var ok = true
                    val raws = mutableSetOf<List<Int>>()
                    for ((i, sample) in samples.withIndex()) {
                        val value = PlausibilityChecker.decode(signal, sample.data)
                        val error = if (value == null) Double.MAX_VALUE else abs(value - references[i])
                        if (error > tolerance(quantity, references[i])) { ok = false; break }
                        maxError = maxOf(maxError, error)
                        raws += sample.data.subList(offset, offset + formula.byteLength)
                    }
                    if (!ok) continue
                    // Bytes saturados (00/FF em todas as amostras) quase sempre são coincidência.
                    val saturated = raws.size == 1 && raws.first().all { it == 0x00 || it == 0xFF }
                    if (saturated) continue
                    out += Match(request, quantity, offset, formula, samples.size, raws.size, maxError)
                }
            }
        }
        return out
    }
}
