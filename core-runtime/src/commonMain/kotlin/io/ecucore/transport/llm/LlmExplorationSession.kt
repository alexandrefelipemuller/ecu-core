package io.ecucore.transport.llm

import io.ecucore.shared.MonotonicClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Loop agêntico: planner propõe → [ExplorationPlanValidator] filtra → executa só leitura →
 * devolve as respostas ao planner → … até o planner encerrar ou o [ExplorationBudget] acabar.
 * Ao final verifica o perfil candidato contra o modo 01 ([PlausibilityChecker]).
 *
 * Não conhece transporte: recebe uma função de envio ELM ([send]). O chamador deve segurar
 * o mutex de IO do transporte durante [run].
 *
 * @param send envia um comando ELM (sem `\r`) e devolve a resposta crua.
 * @param isConnected false interrompe a sessão.
 * @param isEngineRunning checado antes de cada rodada; true aborta (exploração só com
 *   motor desligado). null = não foi possível determinar, segue. Obrigatório de propósito.
 */
class LlmExplorationSession(
    private val planner: LlmExplorationPlanner,
    private val send: suspend (command: String, timeoutMs: Long) -> String,
    private val isConnected: () -> Boolean,
    private val isEngineRunning: suspend () -> Boolean?,
    private val budget: ExplorationBudget = ExplorationBudget(),
    private val clock: () -> Long = { MonotonicClock.nowMillis() },
) {
    private var currentHeader: String? = null
    private var currentProtocol: String? = null
    private var lastSendAt: Long? = null

    suspend fun run(baseContext: ExplorationContext): LlmExplorationRecord {
        val startedAt = clock()
        val rounds = mutableListOf<ExplorationRound>()
        val triedKeys = mutableSetOf<String>()
        val tried = mutableSetOf<ProbeRequest>()
        val positiveKeys = mutableSetOf<String>()
        val expandedKeys = mutableSetOf<String>()
        val pruned = mutableSetOf<String>()
        var usage = TokenUsage()
        var totalProbes = 0
        var deterministicProbes = 0
        var roundsWithoutProgress = 0
        var profile: CandidateProfile? = null
        var stopDetail: String? = null

        for (setup in ElmProbeCodec.SETUP_COMMANDS) runCatching { send(setup, 1_200L) }

        fun probesLeft() = (budget.maxTotalProbes - totalProbes).coerceAtLeast(0)
        fun deterministicLeft() = (budget.maxDeterministicProbes - deterministicProbes).coerceAtLeast(0)
        fun llmRounds() = rounds.count { it.source == RoundSource.LLM }
        fun outOfTime() = clock() - startedAt >= budget.maxDurationMs
        fun context(mustFinish: Boolean) = baseContext.copy(
            mustFinish = mustFinish,
            roundsLeft = (budget.maxRounds - llmRounds()).coerceAtLeast(0),
            probesLeft = probesLeft(),
        )

        /** Registra a rodada e devolve quantos positivos inéditos ela trouxe. */
        fun record(source: RoundSource, plan: ExplorationPlan, rejected: List<RejectedProbe>, result: Execution, usage: TokenUsage): Int {
            result.outcomes.forEach { triedKeys += ExplorationPlanValidator.probeKey(it.request) }
            tried += result.outcomes.map { it.request }
            val newPositives = result.outcomes.count {
                it.status == ProbeStatus.POSITIVE && positiveKeys.add(ExplorationPlanValidator.probeKey(it.request))
            }
            rounds += ExplorationRound(
                index = rounds.size,
                plan = plan,
                rejected = rejected + result.skipped,
                outcomes = result.outcomes,
                usage = usage,
                source = source,
            )
            return newPositives
        }

        /** Executa uma etapa determinística (com orçamento próprio). Null se nada a fazer. */
        suspend fun deterministic(
            source: RoundSource,
            candidates: List<ProbeRequest>,
            rationale: String,
            abortOnSilence: Boolean,
        ): ExplorationRound? {
            if (!budget.surveyEnabled || !isConnected() || outOfTime()) return null
            val fresh = candidates
                .filter { ExplorationPlanValidator.rejectionReason(it) == null }
                .filter { ExplorationPlanValidator.probeKey(it) !in triedKeys }
                .distinctBy { ExplorationPlanValidator.probeKey(it) }
                .take(deterministicLeft())
            if (fresh.isEmpty()) return null
            val result = execute(fresh, pruned, abortOnSilence)
            deterministicProbes += result.outcomes.size
            record(source, ExplorationPlan(fresh, rationale), emptyList(), result, TokenUsage())
            return rounds.last()
        }

        /** Vizinhos (±radius) de tudo que respondeu de forma interessante e ainda não foi expandido. */
        suspend fun expandNeighbors(from: ExplorationRound): Int {
            val origins = from.outcomes
                .filter { DeterministicSurvey.isInteresting(it) }
                .map { it.request }
                .filter { expandedKeys.add(ExplorationPlanValidator.probeKey(it)) }
            if (origins.isEmpty()) return 0
            val round = deterministic(
                source = RoundSource.NEIGHBORS,
                candidates = origins.flatMap { DeterministicSurvey.neighborProbes(it, budget.neighborRadius) },
                rationale = "neighbors of ${origins.joinToString(",") { it.id }.take(200)}",
                abortOnSilence = true,
            ) ?: return 0
            return round.outcomes.count { it.status == ProbeStatus.POSITIVE }
        }

        var stopReason = StopReason.CANCELLED
        val engineRunningAtStart = runCatching { isEngineRunning() }.getOrNull() == true

        // 1) Etapas determinísticas: mapa de ECUs → faixas de DID → vizinhos. Zero tokens.
        if (!engineRunningAtStart) {
            val pings = DeterministicSurvey.ecuMapProbes(baseContext.respondingProtocols)
            val found = mutableListOf<ProbeOutcome>()
            // Um protocolo por vez; achou ECU numa largura de header (11/29-bit), não tenta as outras taxas.
            val foundWidths = mutableSetOf<Int>()
            for ((protocol, group) in pings.groupBy { it.protocol }) {
                val width = group.first().header?.length ?: 0
                if (width in foundWidths) continue
                val round = deterministic(RoundSource.SURVEY_ECU_MAP, group, "ecu map sp$protocol", abortOnSilence = false)
                    ?: continue
                val hits = round.outcomes.filter { DeterministicSurvey.ecuFound(it) }
                if (hits.isNotEmpty()) foundWidths += width
                found += hits
            }
            // ECU de motor (7E0 / 18DA10F1) primeiro.
            val ecus = found.map { it.request }
                .sortedBy { if (it.header == "7E0" || it.header == "18DA10F1") 0 else 1 }
                .take(budget.surveyMaxEcus)
            for (ecu in ecus) {
                val ranges = deterministic(
                    source = RoundSource.SURVEY_DID_RANGES,
                    candidates = DeterministicSurvey.didRangeProbes(ecu.header!!, ecu.protocol),
                    rationale = "did blocks @${ecu.header}",
                    abortOnSilence = true,
                ) ?: continue
                expandNeighbors(ranges)
            }
            // Os pings positivos (ex.: VIN) também ganham vizinhos (bloco F18x de identificação).
            rounds.filter { it.source == RoundSource.SURVEY_ECU_MAP }.toList().forEach { expandNeighbors(it) }
        }

        // 2) Loop do LLM, com expansão de vizinhos após cada rodada.
        loop@ while (true) {
            when {
                engineRunningAtStart -> { stopReason = StopReason.ENGINE_RUNNING; break@loop }
                !isConnected() -> { stopReason = StopReason.DISCONNECTED; break@loop }
                outOfTime() -> { stopReason = StopReason.TIMEOUT; break@loop }
                usage.total >= budget.maxTotalTokens -> { stopReason = StopReason.MAX_TOKENS; break@loop }
                llmRounds() >= budget.maxRounds -> { stopReason = StopReason.MAX_ROUNDS; break@loop }
                probesLeft() == 0 -> { stopReason = StopReason.MAX_PROBES; break@loop }
                roundsWithoutProgress >= budget.maxRoundsWithoutProgress -> { stopReason = StopReason.NO_PROGRESS; break@loop }
            }
            if (runCatching { isEngineRunning() }.getOrNull() == true) {
                stopReason = StopReason.ENGINE_RUNNING
                break@loop
            }

            val step = try {
                planner.nextStep(context(mustFinish = false), rounds.toList())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stopDetail = (e.message ?: e::class.simpleName)?.take(120)
                stopReason = StopReason.PLANNER_ERROR
                break@loop
            }
            usage += step.usage
            when (step) {
                is PlannerStep.Finish -> {
                    profile = step.profile
                    stopReason = StopReason.PLANNER_FINISHED
                    break@loop
                }
                is PlannerStep.Probe -> {
                    val validation = ExplorationPlanValidator.validate(
                        plan = step.plan,
                        maxProbes = minOf(budget.maxProbesPerRound, probesLeft()),
                        alreadyTried = tried,
                    )
                    val result = execute(validation.accepted, pruned, abortOnSilence = true)
                    totalProbes += result.outcomes.size
                    var newPositives = record(RoundSource.LLM, step.plan, validation.rejected, result, step.usage)
                    newPositives += expandNeighbors(rounds.last { it.source == RoundSource.LLM })
                    roundsWithoutProgress = if (newPositives > 0) 0 else roundsWithoutProgress + 1
                }
            }
        }

        // Budget estourou sem o planner encerrar: uma última chamada pedindo o perfil.
        val canAskFinal = profile == null && llmRounds() > 0 && stopReason !in setOf(
            StopReason.PLANNER_ERROR, StopReason.ENGINE_RUNNING, StopReason.DISCONNECTED,
        )
        if (canAskFinal) {
            try {
                val final = planner.nextStep(context(mustFinish = true), rounds.toList())
                usage += final.usage
                if (final is PlannerStep.Finish) profile = final.profile
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stopDetail = stopDetail ?: "final:${(e.message ?: e::class.simpleName)?.take(100)}"
            }
        }

        val canVerify = stopReason != StopReason.DISCONNECTED && stopReason != StopReason.ENGINE_RUNNING
        val verifications = profile?.takeIf { canVerify }?.let { verify(it) }.orEmpty()

        return LlmExplorationRecord(
            context = baseContext,
            rounds = rounds,
            profile = profile,
            verifications = verifications,
            stopReason = stopReason,
            totalUsage = usage,
            elapsedMs = clock() - startedAt,
            stopDetail = stopDetail,
        )
    }

    private data class Execution(val outcomes: List<ProbeOutcome>, val skipped: List<RejectedProbe>)

    /**
     * Executa em ordem. [pruned] guarda header+serviço que já responderam "serviço não
     * suportado" (NRC 0x11/0x7F): testes seguintes do mesmo par são pulados.
     */
    private suspend fun execute(
        probes: List<ProbeRequest>,
        pruned: MutableSet<String>,
        abortOnSilence: Boolean,
    ): Execution {
        val outcomes = mutableListOf<ProbeOutcome>()
        val skipped = mutableListOf<RejectedProbe>()
        var noResponseStreak = 0
        for (probe in probes) {
            if (!isConnected()) break
            if (abortOnSilence && noResponseStreak >= budget.maxConsecutiveNoResponse) break
            if (DeterministicSurvey.pruneKey(probe) in pruned) {
                skipped += RejectedProbe(probe, "service_unsupported")
                continue
            }
            val outcome = runProbe(probe)
            outcomes += outcome
            noResponseStreak = if (outcome.status == ProbeStatus.NO_RESPONSE) noResponseStreak + 1 else 0
            if (outcome.status == ProbeStatus.NEGATIVE && outcome.nrc in DeterministicSurvey.PRUNE_NRCS) {
                pruned += DeterministicSurvey.pruneKey(probe)
            }
        }
        return Execution(outcomes, skipped)
    }

    private suspend fun runProbe(probe: ProbeRequest): ProbeOutcome {
        val commands = mutableListOf<String>()
        val startedAt = clock()
        val raw = try {
            probe.protocol?.takeIf { it != currentProtocol }?.let {
                sendThrottled(ElmProbeCodec.protocolCommand(it), commands)
                currentProtocol = it
            }
            probe.header?.takeIf { it != currentHeader }?.let { header ->
                ElmProbeCodec.headerCommands(header).forEach { sendThrottled(it, commands) }
                currentHeader = header
            }
            sendThrottled(probe.service.requestHex(probe.id), commands)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ProbeOutcome(
                request = probe,
                commands = commands,
                status = ProbeStatus.ERROR,
                rawResponse = "ERR:${e.message ?: e::class.simpleName}".take(80),
                elapsedMs = clock() - startedAt,
            )
        }
        val decoded = ElmProbeCodec.decode(probe, raw)
        return ProbeOutcome(
            request = probe,
            commands = commands,
            status = decoded.status,
            data = decoded.data,
            nrc = decoded.nrc,
            rawResponse = compact(raw),
            elapsedMs = clock() - startedAt,
        )
    }

    private suspend fun sendThrottled(command: String, log: MutableList<String>): String {
        lastSendAt?.let { last ->
            val wait = budget.minProbeIntervalMs - (clock() - last)
            if (wait > 0) delay(wait)
        }
        log += command
        val isAt = command.startsWith("AT")
        return try {
            send(command, if (isAt) 1_200L else budget.probeTimeoutMs)
        } finally {
            lastSendAt = clock()
        }
    }

    /** Lê cada sinal (revalidado — o perfil também vem do LLM) e a referência do modo 01. */
    private suspend fun verify(profile: CandidateProfile): List<SignalVerification> {
        return profile.signals.take(MAX_VERIFIED_SIGNALS).map { signal ->
            val oemProbe = ProbeRequest(service = signal.service, id = signal.id.trim().uppercase(), header = signal.header?.trim()?.uppercase())
            if (ExplorationPlanValidator.rejectionReason(oemProbe) != null || !isConnected()) {
                return@map SignalVerification(signal, null, null, SignalVerification.Verdict.DECODE_FAILED)
            }
            val oem = runProbe(oemProbe)
            val oemValue = oem.takeIf { it.status == ProbeStatus.POSITIVE }
                ?.let { PlausibilityChecker.decode(signal, it.data) }
            val reference = signal.reference
            if (reference == null) {
                val verdict = if (oemValue == null) SignalVerification.Verdict.DECODE_FAILED else SignalVerification.Verdict.UNVERIFIABLE
                return@map SignalVerification(signal, oemValue, null, verdict)
            }
            // Referência pelo header funcional do protocolo atual (modo 01 padrão).
            val refProbe = ProbeRequest(service = ReadService.OBD_MODE_01, id = reference.mode01Pid, header = functionalHeaderFor(oemProbe.header))
            val ref = runProbe(refProbe)
            val referenceValue = ref.takeIf { it.status == ProbeStatus.POSITIVE }?.let { reference.decodeMode01(it.data) }
            SignalVerification(signal, oemValue, referenceValue, PlausibilityChecker.verdict(reference, oemValue, referenceValue))
        }
    }

    private fun functionalHeaderFor(header: String?): String? = when {
        header == null -> null
        header.length == 3 -> "7DF"
        header.length == 8 -> "18DB33F1"
        header.startsWith("68") || header.startsWith("6C") -> "686AF1"
        else -> "C133F1"
    }

    private fun compact(response: String) =
        response.replace('\r', ' ').replace('\n', ' ').replace(">", "").replace(Regex("\\s+"), " ").trim().take(120)

    companion object {
        const val MAX_VERIFIED_SIGNALS = 12
    }
}
