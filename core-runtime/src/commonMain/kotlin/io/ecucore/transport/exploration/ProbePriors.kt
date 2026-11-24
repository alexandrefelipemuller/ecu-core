package io.ecucore.transport.exploration

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Crença a priori de que um teste responde (positivo ou NRC "interessante"), como
 * Beta(alpha, beta), por chave hierárquica do mais específico ao mais genérico:
 *
 * 1. `b:<marca>|<serviço>|<header>|<classe do id>`
 * 2. `<serviço>|<header>|<classe do id>`
 * 3. `<serviço>|<classe do id>`
 * 4. `<serviço>`
 *
 * Classe do id: byte alto para ids de 4 dígitos ("F190" → "F1"), o próprio id para ids de
 * 2 dígitos, vazio para requisições fixas. Header nulo vira "*".
 *
 * Formato texto (um por linha, `#` comenta): `version <n>` e `<chave> <alpha> <beta>`.
 * É isto que o pipeline offline gera a partir dos registros e que o app pode baixar
 * para substituir o [BUILTIN] sem atualizar o APK.
 */
class ProbePriors(
    val version: Int,
    private val entries: Map<String, Beta>,
    private val default: Beta = Beta(1.0, 20.0),
) {
    data class Beta(val alpha: Double, val beta: Double) {
        val mean: Double get() = alpha / (alpha + beta)
        fun update(successes: Int, failures: Int) = Beta(alpha + successes, beta + failures)
    }

    fun lookup(probe: ProbeRequest, brand: String? = null): Beta =
        keysFor(probe, brand).firstNotNullOfOrNull { entries[it] } ?: default

    val size: Int get() = entries.size

    companion object {
        fun idClass(probe: ProbeRequest): String = when (probe.service.idHexLength) {
            0 -> ""
            4 -> probe.id.uppercase().take(2)
            else -> probe.id.uppercase()
        }

        /** Chave de evidência local (nível 2): mesma ECU, mesmo serviço, mesmo bloco. */
        fun blockKey(probe: ProbeRequest): String =
            "${probe.service.name}|${probe.header?.uppercase() ?: "*"}|${idClass(probe)}"

        fun keysFor(probe: ProbeRequest, brand: String?): List<String> {
            val service = probe.service.name
            val block = blockKey(probe)
            return listOfNotNull(
                brand?.takeIf { it.isNotBlank() }?.let { "b:${it.trim().lowercase()}|$block" },
                block,
                "$service|${idClass(probe)}",
                service,
            )
        }

        /** Lê o formato texto. Linhas inválidas são ignoradas (arquivo baixado não pode derrubar o app). */
        fun parse(text: String): ProbePriors {
            var version = 0
            val map = mutableMapOf<String, Beta>()
            text.lineSequence()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .forEach { line ->
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size == 2 && parts[0] == "version") {
                        version = parts[1].toIntOrNull() ?: version
                    } else if (parts.size == 3) {
                        val a = parts[1].toDoubleOrNull()
                        val b = parts[2].toDoubleOrNull()
                        if (a != null && b != null && a > 0 && b > 0 && a.isFinite() && b.isFinite()) {
                            map[parts[0]] = Beta(a, b)
                        }
                    }
                }
            return ProbePriors(version, map)
        }

        /**
         * Priors v0 só com conhecimento público de padrão (sem dado de frota):
         * - UDS 22 F1xx: identificação (ISO 14229) — muito comum.
         * - UDS 22 F4xx: espelho dos PIDs do modo 01 (ISO 27145/WWH-OBD) — comum em CAN recente.
         * - 19 02 FF: DTCs — quase toda ECU UDS responde (positivo ou NRC).
         * - Modo 01 00/20/40 e 09 02: suporte a PIDs e VIN.
         * - KWP 1A 80-9B: identificação KWP2000; KWP 21 01-3F: blocos de medição.
         */
        val BUILTIN: ProbePriors = parse(
            """
            version 0
            UDS_22|F1            30 10
            UDS_22|F4            8 12
            UDS_22|F8            2 18
            UDS_22               1 30
            UDS_19_DTC_BY_STATUS 10 10
            OBD_MODE_01|00       40 2
            OBD_MODE_01|20       30 4
            OBD_MODE_01|40       20 6
            OBD_MODE_01          4 6
            OBD_MODE_09|02       20 4
            OBD_MODE_09|04       12 6
            OBD_MODE_09          2 10
            KWP_1A|80            12 6
            KWP_1A|86            8 8
            KWP_1A|90            8 8
            KWP_1A|91            6 10
            KWP_1A|9B            6 10
            KWP_1A               1 20
            KWP_21|01            6 8
            KWP_21               2 14
            """.trimIndent()
        )
    }
}

/** Amostragem Beta (Thompson sampling) sem dependências: via duas Gamma (Marsaglia-Tsang). */
internal object BetaSampler {
    fun sample(alpha: Double, beta: Double, random: Random): Double {
        val x = gamma(alpha, random)
        val y = gamma(beta, random)
        return if (x + y <= 0.0) alpha / (alpha + beta) else x / (x + y)
    }

    private fun gamma(shape: Double, random: Random): Double {
        if (shape < 1.0) {
            // Boost: Gamma(a) = Gamma(a+1) * U^(1/a)
            return gamma(shape + 1.0, random) * random.nextDouble().coerceAtLeast(1e-12).pow(1.0 / shape)
        }
        val d = shape - 1.0 / 3.0
        val c = 1.0 / sqrt(9.0 * d)
        while (true) {
            val x = normal(random)
            val v0 = 1.0 + c * x
            if (v0 <= 0.0) continue
            val v = v0 * v0 * v0
            val u = random.nextDouble()
            if (u < 1.0 - 0.0331 * x * x * x * x) return d * v
            if (ln(u.coerceAtLeast(1e-300)) < 0.5 * x * x + d * (1.0 - v + ln(v))) return d * v
        }
    }

    private fun normal(random: Random): Double {
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * kotlin.math.PI * u2)
    }

    /** Bônus de proximidade em [1, 1+weight]: decai com a distância ao positivo mais próximo. */
    fun proximity(distance: Int?, weight: Double = 0.5, scale: Double = 8.0): Double =
        if (distance == null) 1.0 else 1.0 + weight * exp(-distance / scale)
}
