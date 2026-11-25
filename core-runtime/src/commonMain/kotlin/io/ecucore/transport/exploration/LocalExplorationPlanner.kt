package io.ecucore.transport.exploration

import kotlin.math.abs
import kotlin.random.Random

/**
 * Planner 100% local (sem rede, sem tokens): gera candidatos a partir do que já
 * respondeu, pontua cada um por Thompson sampling sobre Beta(prior + evidência da sessão)
 * e envia os melhores. Encerra quando o ganho esperado cai abaixo de [minScore] ou o
 * orçamento acaba, devolvendo o perfil do [SignalFitter].
 *
 * O "modelo" é o [ProbePriors] (tabela Beta por chave, treinada offline a partir dos
 * registros); [ranker] é um gancho opcional para um modelo aprendido (ex.: TFLite no app)
 * que multiplica o score.
 */
class LocalExplorationPlanner(
    private val priors: ProbePriors = ProbePriors.BUILTIN,
    private val random: Random = Random.Default,
    private val batchSize: Int = 30,
    private val minScore: Double = 0.03,
    private val ranker: ProbeRanker? = null,
) : ExplorationPlanner {

    override suspend fun nextStep(context: ExplorationContext, history: List<ExplorationRound>): PlannerStep {
        if (context.mustFinish || context.probesLeft <= 0) return finish(history)
        val evidence = Evidence.from(history)
        val brand = context.brandHint

        val scored = CandidateGenerator.generate(context, evidence)
            .asSequence()
            .filter { ExplorationPlanValidator.rejectionReason(it) == null }
            .filter { ExplorationPlanValidator.probeKey(it) !in evidence.tried }
            .filter { DeterministicSurvey.pruneKey(it) !in evidence.pruned }
            .distinctBy { ExplorationPlanValidator.probeKey(it) }
            .map { probe ->
                val posterior = evidence.posterior(probe, priors.lookup(probe, brand))
                val thompson = BetaSampler.sample(posterior.alpha, posterior.beta, random)
                val score = thompson * BetaSampler.proximity(evidence.distanceToPositive(probe)) *
                    (ranker?.score(probe, context, evidence.summary) ?: 1.0)
                Scored(probe, score, posterior.mean)
            }
            .toList()

        // DTCs são objetivo principal, não aposta: a leitura de cada ECU entra sempre na frente.
        // Depois, parada pelo valor esperado (não pela amostra), para não ficar preso no ruído.
        val (mandatory, optional) = scored.partition { it.probe.service in MANDATORY_SERVICES }
        val chosen = (mandatory + optional.filter { it.expected >= minScore }.sortedByDescending { it.score })
            .take(minOf(batchSize, context.probesLeft))
        if (chosen.isEmpty()) return finish(history)

        return PlannerStep.Probe(
            ExplorationPlan(
                probes = chosen.map { it.probe.copy(hypothesis = "local:p=${fmt(it.expected)}") },
                rationale = "local v${priors.version}: ${scored.size} candidates, " +
                    "best=${chosen.first().probe.service.name}:${chosen.first().probe.id}@${chosen.first().probe.header ?: "*"}",
            ),
        )
    }

    private fun finish(history: List<ExplorationRound>): PlannerStep.Finish {
        val evidence = Evidence.from(history)
        val fitted = SignalFitter.fit(history)
        val notes = listOfNotNull(
            fitted.notes,
            evidence.ecus.takeIf { it.isNotEmpty() }?.let { ecus -> "ecus=" + ecus.joinToString(",") { "${it.header}/sp${it.protocol ?: "-"}" } },
            evidence.dtcSummary().takeIf { it.isNotEmpty() }?.let { "dtc=$it" },
        ).joinToString("; ")
        return PlannerStep.Finish(fitted.copy(notes = notes))
    }

    private companion object {
        val MANDATORY_SERVICES = setOf(ReadService.UDS_19_DTC_BY_STATUS)
    }

    private data class Scored(val probe: ProbeRequest, val score: Double, val expected: Double)

    private fun fmt(v: Double) = ((v * 1000).toInt() / 1000.0).toString()
}

/** Gancho para um ranqueador aprendido. Devolve um multiplicador (1.0 = neutro). */
fun interface ProbeRanker {
    fun score(probe: ProbeRequest, context: ExplorationContext, evidence: EvidenceSummary): Double
}

/** Resumo da sessão exposto ao [ProbeRanker] (features). */
data class EvidenceSummary(
    val ecuCount: Int,
    val positives: Int,
    val negatives: Int,
    val silent: Int,
    val interestingBlocks: Set<String>,
)

/** ECU encontrada: header físico que respondeu algo do protocolo. */
data class FoundEcu(val header: String, val protocol: String?)

internal class Evidence private constructor(
    val tried: Set<String>,
    val pruned: Set<String>,
    val ecus: List<FoundEcu>,
    /** blockKey → (sucessos, falhas). */
    private val blocks: Map<String, IntArray>,
    /** blockKey → ids (como int) que responderam de forma interessante. */
    private val hits: Map<String, List<Int>>,
    private val dtc: Map<String, ProbeOutcome>,
    /** blockKey → um teste que respondeu de forma interessante naquele bloco. */
    private val representative: Map<String, ProbeRequest>,
    val summary: EvidenceSummary,
) {
    fun posterior(probe: ProbeRequest, prior: ProbePriors.Beta): ProbePriors.Beta {
        val counts = blocks[ProbePriors.blockKey(probe)] ?: return prior
        return prior.update(counts[0], counts[1])
    }

    fun distanceToPositive(probe: ProbeRequest): Int? {
        val id = probe.id.toIntOrNull(16) ?: return null
        return hits[ProbePriors.blockKey(probe)]?.minOfOrNull { abs(it - id) }
    }

    /** Blocos interessantes: (header, protocolo, serviço, classe do id). */
    fun interestingBlocks(): Collection<ProbeRequest> = representative.values

    fun dtcSummary(): String = dtc.entries.joinToString(",") { (header, o) ->
        when (o.status) {
            ProbeStatus.POSITIVE -> "$header:+${o.data.size}B"
            ProbeStatus.NEGATIVE -> "$header:nrc${o.nrc?.toString(16)}"
            else -> "$header:${o.status.name.lowercase()}"
        }
    }

    companion object {
        fun from(history: List<ExplorationRound>): Evidence {
            val tried = mutableSetOf<String>()
            val pruned = mutableSetOf<String>()
            val ecus = linkedSetOf<FoundEcu>()
            val blocks = mutableMapOf<String, IntArray>()
            val hits = mutableMapOf<String, MutableList<Int>>()
            val representative = linkedMapOf<String, ProbeRequest>()
            val dtc = linkedMapOf<String, ProbeOutcome>()
            var positives = 0
            var negatives = 0
            var silent = 0
            for (round in history) {
                round.rejected.filter { it.reason == "service_unsupported" }
                    .forEach { pruned += DeterministicSurvey.pruneKey(it.request) }
                for (o in round.outcomes) {
                    val request = o.request
                    tried += ExplorationPlanValidator.probeKey(request)
                    if (round.source == RoundSource.RESAMPLE) continue
                    when (o.status) {
                        ProbeStatus.POSITIVE -> positives++
                        ProbeStatus.NEGATIVE -> negatives++
                        else -> silent++
                    }
                    if (o.status == ProbeStatus.NEGATIVE && o.nrc in DeterministicSurvey.PRUNE_NRCS) {
                        pruned += DeterministicSurvey.pruneKey(request)
                    }
                    val header = request.header
                    val physical = header != null && header != "7DF" && header != "18DB33F1" && !header.startsWith("686A") && header != "C133F1"
                    if (physical && DeterministicSurvey.ecuFound(o)) ecus += FoundEcu(header!!, request.protocol)
                    if (request.service == ReadService.UDS_19_DTC_BY_STATUS && header != null) dtc[header] = o

                    val key = ProbePriors.blockKey(request)
                    val counts = blocks.getOrPut(key) { IntArray(2) }
                    val interesting = DeterministicSurvey.isInteresting(o)
                    if (interesting) counts[0]++ else counts[1]++
                    if (interesting && request.service.idHexLength > 0) {
                        request.id.toIntOrNull(16)?.let { hits.getOrPut(key) { mutableListOf() } += it }
                        representative.putIfAbsent(key, request)
                    }
                }
            }
            // ECUs no mesmo header com protocolos diferentes: fica o primeiro.
            val distinctEcus = ecus.distinctBy { it.header }
            return Evidence(
                tried = tried,
                pruned = pruned,
                ecus = distinctEcus,
                blocks = blocks,
                hits = hits,
                dtc = dtc,
                representative = representative,
                summary = EvidenceSummary(distinctEcus.size, positives, negatives, silent, representative.keys.toSet()),
            )
        }
    }
}

/** Gera o universo de candidatos; o ranqueamento e o filtro de já-testados ficam no planner. */
internal object CandidateGenerator {

    private val CAN_PROTOCOLS = setOf("6", "7", "8", "9", "A", "B", "C")
    private val KWP_ISO_PROTOCOLS = setOf("3", "4", "5")
    private val J1850_PROTOCOLS = setOf("1", "2")

    fun generate(context: ExplorationContext, evidence: Evidence): List<ProbeRequest> {
        val out = mutableListOf<ProbeRequest>()
        val ecus = evidence.ecus.ifEmpty { fallbackEcus(context.respondingProtocols) }

        for (ecu in ecus) {
            val h = ecu.header
            val sp = ecu.protocol
            out += ProbeRequest(ReadService.UDS_19_DTC_BY_STATUS, "", h, sp)
            out += listOf("00", "20", "40").map { ProbeRequest(ReadService.OBD_MODE_01, it, h, sp) }
            out += listOf("02", "04", "0A").map { ProbeRequest(ReadService.OBD_MODE_09, it, h, sp) }
            out += (0x80..0x9F).map { ProbeRequest(ReadService.UDS_22, "F1" + hex(it), h, sp) }
            // Espelho dos PIDs do modo 01 (ISO 27145): F400..F4A0.
            out += (0x00..0xA0).map { ProbeRequest(ReadService.UDS_22, "F4" + hex(it), h, sp) }
            out += (0x80..0x9F).map { ProbeRequest(ReadService.KWP_1A, hex(it), h, sp) }
            out += (0x01..0x3F).map { ProbeRequest(ReadService.KWP_21, hex(it), h, sp) }
        }

        // Blocos que já responderam: completa o bloco inteiro (a proximidade ordena).
        for (probe in evidence.interestingBlocks()) {
            when (probe.service.idHexLength) {
                4 -> {
                    val hi = probe.id.take(2)
                    out += (0x00..0xFF).map { probe.copy(id = hi + hex(it), hypothesis = null) }
                }
                2 -> if (probe.service == ReadService.KWP_21 || probe.service == ReadService.KWP_1A) {
                    out += (0x00..0xFF).map { probe.copy(id = hex(it), hypothesis = null) }
                }
            }
        }
        return out
    }

    /** Sem mapa de ECUs (KWP/J1850 puros, ou survey desligado): endereços padrão por família. */
    private fun fallbackEcus(responding: List<String>): List<FoundEcu> {
        val protocols = responding.ifEmpty { listOf<String?>(null) }
        return protocols.flatMap { sp ->
            when (sp) {
                null -> listOf(FoundEcu("7E0", null), FoundEcu("8110F1", null), FoundEcu("6C10F1", null))
                in CAN_PROTOCOLS -> if (sp == "7" || sp == "9") {
                    listOf(FoundEcu("18DA10F1", sp), FoundEcu("18DA18F1", sp))
                } else {
                    listOf(FoundEcu("7E0", sp), FoundEcu("7E1", sp))
                }
                in KWP_ISO_PROTOCOLS -> listOf(FoundEcu("8110F1", sp), FoundEcu("8111F1", sp), FoundEcu("C110F1", sp))
                in J1850_PROTOCOLS -> listOf(FoundEcu("6C10F1", sp), FoundEcu("6C18F1", sp))
                else -> emptyList()
            }
        }
    }

    private fun hex(v: Int) = v.toString(16).uppercase().padStart(2, '0')
}
