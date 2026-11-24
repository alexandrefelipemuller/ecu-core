package io.ecucore.transport.exploration

/**
 * Barreira determinística entre o planner e o barramento do carro.
 *
 * Tudo que o planner devolve passa por aqui. Não há caminho para comandos crus: o
 * serviço é um enum fechado de leitura, e header/id/protocolo são conferidos por regex
 * estrita (só hex, tamanho exato, faixas de endereço conhecidas).
 */
object ExplorationPlanValidator {

    data class Result(val accepted: List<ProbeRequest>, val rejected: List<RejectedProbe>)

    private val HEX = Regex("^[0-9A-F]+$")
    private val CAN_11BIT = Regex("^7[0-9A-F]{2}$")
    private val CAN_29BIT_PHYSICAL = Regex("^18DA[0-9A-F]{2}F1$")
    private const val CAN_29BIT_FUNCTIONAL = "18DB33F1"
    /** KWP2000 (80-83/C0-C2) e J1850 (68/6C) com tester F1. */
    private val KWP_J1850 = Regex("^(68|6C|80|81|82|83|C0|C1|C2)[0-9A-F]{2}F1$")
    private val PROTOCOL = Regex("^[0-9A-C]$")
    private const val MAX_11BIT = 0x7F7
    private const val FUNCTIONAL_11BIT = 0x7DF

    /**
     * @param maxProbes quantos testes podem ser aceitos nesta rodada (já descontado o
     *   orçamento total). O excedente é rejeitado com motivo "budget".
     * @param alreadyTried requisições já executadas em rodadas anteriores (evita repetir).
     */
    fun validate(
        plan: ExplorationPlan,
        maxProbes: Int,
        alreadyTried: Set<ProbeRequest> = emptySet(),
    ): Result {
        val accepted = mutableListOf<ProbeRequest>()
        val rejected = mutableListOf<RejectedProbe>()
        val seen = alreadyTried.map { it.key() }.toMutableSet()

        for (raw in plan.probes) {
            val normalized = raw.normalized()
            val reason = when {
                rejectionReason(normalized) != null -> rejectionReason(normalized)
                normalized.key() in seen -> "duplicate"
                accepted.size >= maxProbes -> "budget"
                else -> null
            }
            if (reason != null) {
                rejected += RejectedProbe(raw, reason)
            } else {
                seen += normalized.key()
                accepted += normalized
            }
        }
        return Result(accepted, rejected)
    }

    /** null = aceito. */
    fun rejectionReason(probe: ProbeRequest): String? {
        val id = probe.id
        if (probe.service.idHexLength == 0) {
            if (id.isNotEmpty()) return "id_not_allowed"
        } else {
            if (id.length != probe.service.idHexLength || !HEX.matches(id)) return "invalid_id"
        }
        probe.header?.let { if (!isAllowedHeader(it)) return "invalid_header" }
        probe.protocol?.let { if (!PROTOCOL.matches(it)) return "invalid_protocol" }
        return null
    }

    fun isAllowedHeader(header: String): Boolean {
        if (CAN_11BIT.matches(header)) {
            val value = header.toInt(16)
            return value <= MAX_11BIT || value == FUNCTIONAL_11BIT
        }
        return CAN_29BIT_PHYSICAL.matches(header) ||
            header == CAN_29BIT_FUNCTIONAL ||
            KWP_J1850.matches(header)
    }

    private fun ProbeRequest.normalized() = copy(
        id = id.trim().uppercase(),
        header = header?.trim()?.uppercase()?.ifEmpty { null },
        protocol = protocol?.trim()?.uppercase()?.ifEmpty { null },
    )

    private fun ProbeRequest.key() = "${service.name}|$id|${header.orEmpty()}|${protocol.orEmpty()}"

    /** Chave de deduplicação (serviço|id|header|protocolo normalizados, sem a hipótese). */
    fun probeKey(probe: ProbeRequest): String = probe.normalized().key()
}
