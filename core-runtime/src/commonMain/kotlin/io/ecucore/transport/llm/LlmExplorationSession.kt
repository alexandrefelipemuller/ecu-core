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
        val tried = mutableSetOf<ProbeRequest>()
        val positiveKeys = mutableSetOf<String>()
        var usage = TokenUsage()
        var totalProbes = 0
        var roundsWithoutProgress = 0
        var profile: CandidateProfile? = null
        var stopDetail: String? = null

        for (setup in ElmProbeCodec.SETUP_COMMANDS) runCatching { send(setup, 1_200L) }

        fun probesLeft() = (budget.maxTotalProbes - totalProbes).coerceAtLeast(0)
        fun context(mustFinish: Boolean) = baseContext.copy(
            mustFinish = mustFinish,
            roundsLeft = (budget.maxRounds - rounds.size).coerceAtLeast(0),
            probesLeft = probesLeft(),
        )

        var stopReason = StopReason.CANCELLED
        loop@ while (true) {
            when {
                !isConnected() -> { stopReason = StopReason.DISCONNECTED; break@loop }
                clock() - startedAt >= budget.maxDurationMs -> { stopReason = StopReason.TIMEOUT; break@loop }
                usage.total >= budget.maxTotalTokens -> { stopReason = StopReason.MAX_TOKENS; break@loop }
                rounds.size >= budget.maxRounds -> { stopReason = StopReason.MAX_ROUNDS; break@loop }
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
                    val outcomes = execute(validation.accepted)
                    tried += validation.accepted
                    totalProbes += outcomes.size
                    val newPositives = outcomes.count {
                        it.status == ProbeStatus.POSITIVE && positiveKeys.add(it.request.toString())
                    }
                    roundsWithoutProgress = if (newPositives > 0) 0 else roundsWithoutProgress + 1
                    rounds += ExplorationRound(
                        index = rounds.size,
                        plan = step.plan,
                        rejected = validation.rejected,
                        outcomes = outcomes,
                        usage = step.usage,
                    )
                }
            }
        }

        // Budget estourou sem o planner encerrar: uma última chamada pedindo o perfil.
        val canAskFinal = profile == null && rounds.isNotEmpty() && stopReason !in setOf(
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

    private suspend fun execute(probes: List<ProbeRequest>): List<ProbeOutcome> {
        val outcomes = mutableListOf<ProbeOutcome>()
        var noResponseStreak = 0
        for (probe in probes) {
            if (!isConnected()) break
            if (noResponseStreak >= budget.maxConsecutiveNoResponse) break
            val outcome = runProbe(probe)
            outcomes += outcome
            noResponseStreak = if (outcome.status == ProbeStatus.NO_RESPONSE) noResponseStreak + 1 else 0
        }
        return outcomes
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
