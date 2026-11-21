package io.ecucore.transport.llm

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExplorationPlanValidatorTest {

    private fun validate(vararg probes: ProbeRequest, max: Int = 40) =
        ExplorationPlanValidator.validate(ExplorationPlan(probes.toList()), max)

    @Test
    fun acceptsReadOnlyProbes() {
        val r = validate(
            ProbeRequest(ReadService.UDS_22, "f190", header = "7e0"),
            ProbeRequest(ReadService.KWP_21, "01", header = "8110F1", protocol = "5"),
            ProbeRequest(ReadService.OBD_MODE_01, "0C", header = "18DA10F1"),
            ProbeRequest(ReadService.UDS_19_DTC_BY_STATUS, header = "7DF"),
        )
        assertEquals(4, r.accepted.size)
        assertEquals("F190", r.accepted[0].id)
        assertEquals("7E0", r.accepted[0].header)
        assertTrue(r.rejected.isEmpty())
    }

    @Test
    fun rejectsInjectionThroughIdHeaderAndProtocol() {
        val r = validate(
            // Tentativa de embutir escrita/reset no id
            ProbeRequest(ReadService.UDS_22, "F1902E"),
            ProbeRequest(ReadService.UDS_22, "F1\r1101"),
            ProbeRequest(ReadService.UDS_22, "2E F1"),
            ProbeRequest(ReadService.KWP_21, "0"),
            ProbeRequest(ReadService.OBD_MODE_01, "ZZ"),
            // id em serviço de requisição fixa
            ProbeRequest(ReadService.UDS_19_DTC_BY_STATUS, "01"),
            // Comando AT disfarçado de header / protocolo
            ProbeRequest(ReadService.UDS_22, "F190", header = "ATMA"),
            ProbeRequest(ReadService.UDS_22, "F190", header = "7E0\rATBRD10"),
            ProbeRequest(ReadService.UDS_22, "F190", protocol = "6\rATMA"),
            ProbeRequest(ReadService.UDS_22, "F190", protocol = "D"),
            // Fora das faixas de endereço
            ProbeRequest(ReadService.UDS_22, "F190", header = "7F8"),
            ProbeRequest(ReadService.UDS_22, "F190", header = "18DA10F2"),
            ProbeRequest(ReadService.UDS_22, "F190", header = "123"),
        )
        assertTrue(r.accepted.isEmpty(), "aceitou: ${r.accepted}")
        assertEquals(13, r.rejected.size)
    }

    @Test
    fun noServiceOutsideReadWhitelistExists() {
        val forbidden = setOf(0x04, 0x10, 0x11, 0x14, 0x27, 0x28, 0x2E, 0x2F, 0x31, 0x34, 0x35, 0x36, 0x37, 0x3B, 0x85)
        assertTrue(ReadService.entries.none { it.serviceByte in forbidden })
        ReadService.entries.forEach { svc ->
            val id = "0".repeat(svc.idHexLength)
            val hex = svc.requestHex(id)
            assertTrue(hex.take(2).toInt(16) !in forbidden, "$svc gera $hex")
        }
    }

    @Test
    fun enforcesBudgetAndDeduplicates() {
        val probes = (0 until 10).map { ProbeRequest(ReadService.UDS_22, "F1" + (it).toString(16).uppercase().padStart(2, '0')) }
        val tried = setOf(ProbeRequest(ReadService.UDS_22, "F100"))
        val r = ExplorationPlanValidator.validate(
            ExplorationPlan(probes + probes.first().copy(id = "f101")),
            maxProbes = 3,
            alreadyTried = tried,
        )
        assertEquals(listOf("F101", "F102", "F103"), r.accepted.map { it.id })
        assertEquals(1, r.rejected.count { it.reason == "duplicate" && it.request.id == "F100" })
        assertTrue(r.rejected.any { it.reason == "duplicate" && it.request.id == "f101" })
        assertEquals(6, r.rejected.count { it.reason == "budget" })
    }
}

class ElmProbeCodecTest {

    @Test
    fun decodesSingleFramePositive() {
        val d = ElmProbeCodec.decode(ProbeRequest(ReadService.UDS_22, "F40C"), "62 F4 0C 1A F8 \r\r>")
        assertEquals(ProbeStatus.POSITIVE, d.status)
        assertEquals(listOf(0x1A, 0xF8), d.data)
    }

    @Test
    fun decodesIsoTpMultiFrame() {
        val raw = "014\r0: 62 F1 90 57 56 57\r1: 5A 5A 5A 31 4A 5A 58\r2: 57 30 30 30 30 30 31\r\r>"
        val d = ElmProbeCodec.decode(ProbeRequest(ReadService.UDS_22, "F190"), raw)
        assertEquals(ProbeStatus.POSITIVE, d.status)
        assertEquals("WVWZZZ1JZXW000001", d.data.map { it.toChar() }.joinToString(""))
    }

    @Test
    fun decodesNegativeAndSilence() {
        val neg = ElmProbeCodec.decode(ProbeRequest(ReadService.UDS_22, "1234"), "7F 22 31")
        assertEquals(ProbeStatus.NEGATIVE, neg.status)
        assertEquals(0x31, neg.nrc)
        assertEquals(ProbeStatus.NO_RESPONSE, ElmProbeCodec.decode(ProbeRequest(ReadService.KWP_21, "01"), "NO DATA\r>").status)
        assertEquals(ProbeStatus.NO_RESPONSE, ElmProbeCodec.decode(ProbeRequest(ReadService.KWP_21, "01"), "SEARCHING...\rUNABLE TO CONNECT").status)
        assertEquals(ProbeStatus.ERROR, ElmProbeCodec.decode(ProbeRequest(ReadService.KWP_21, "01"), "CAN ERROR").status)
    }

    @Test
    fun builds29BitHeaderCommands() {
        assertEquals(listOf("ATCP18", "ATSHDA10F1"), ElmProbeCodec.headerCommands("18DA10F1"))
        assertEquals(listOf("ATSH7E0"), ElmProbeCodec.headerCommands("7E0"))
    }
}

class PlausibilityCheckerTest {

    private val rpmSignal = CandidateSignal(
        name = "rpm", service = ReadService.UDS_22, id = "F40C",
        byteLength = 2, scale = 0.25, reference = ReferenceQuantity.RPM,
    )

    @Test
    fun decodesLinearFormula() {
        assertEquals(1726.0, PlausibilityChecker.decode(rpmSignal, listOf(0x1A, 0xF8)))
        val signed = CandidateSignal("t", ReadService.KWP_21, "01", byteLength = 1, signed = true)
        assertEquals(-1.0, PlausibilityChecker.decode(signed, listOf(0xFF)))
        assertNull(PlausibilityChecker.decode(rpmSignal, listOf(0x1A)))
    }

    @Test
    fun verdicts() {
        assertEquals(SignalVerification.Verdict.VERIFIED, PlausibilityChecker.verdict(ReferenceQuantity.RPM, 1726.0, 1750.0))
        assertEquals(SignalVerification.Verdict.MISMATCH, PlausibilityChecker.verdict(ReferenceQuantity.RPM, 6904.0, 1726.0))
        assertEquals(SignalVerification.Verdict.UNVERIFIABLE, PlausibilityChecker.verdict(ReferenceQuantity.RPM, 0.0, 0.0))
        assertEquals(SignalVerification.Verdict.VERIFIED, PlausibilityChecker.verdict(ReferenceQuantity.COOLANT_TEMP, 88.0, 90.0))
        assertEquals(SignalVerification.Verdict.DECODE_FAILED, PlausibilityChecker.verdict(ReferenceQuantity.COOLANT_TEMP, null, 90.0))
    }
}

class LlmExplorationSessionTest {

    /** ECU fake: responde ao DID F405 (ECT OEM) e ao 0105 padrão; o resto 7F 22 31. */
    private class FakeEcu {
        val sent = mutableListOf<String>()
        suspend fun send(cmd: String, @Suppress("UNUSED_PARAMETER") timeout: Long): String {
            sent += cmd
            return when {
                cmd.startsWith("AT") -> "OK"
                cmd == "22F405" -> "62 F4 05 82"
                cmd == "0105" -> "41 05 82"
                cmd.startsWith("22") -> "7F 22 31"
                else -> "NO DATA"
            }
        }
    }

    private class ScriptedPlanner(private val steps: List<PlannerStep>) : LlmExplorationPlanner {
        val contexts = mutableListOf<ExplorationContext>()
        private var i = 0
        override suspend fun nextStep(context: ExplorationContext, history: List<ExplorationRound>): PlannerStep {
            contexts += context
            return steps.getOrElse(i++) { steps.last() }
        }
    }

    private var now = 0L
    private val clock = { now.also { now += 10 } }
    private val noSurvey = ExplorationBudget(surveyEnabled = false)

    @Test
    fun runsRoundsValidatesAndVerifiesProfile() = runTest {
        val ecu = FakeEcu()
        val profile = CandidateProfile(
            listOf(CandidateSignal("ect", ReadService.UDS_22, "F405", header = "7E0", offset = -40.0, reference = ReferenceQuantity.COOLANT_TEMP))
        )
        val planner = ScriptedPlanner(
            listOf(
                PlannerStep.Probe(
                    ExplorationPlan(
                        listOf(
                            ProbeRequest(ReadService.UDS_22, "F405", header = "7E0"),
                            ProbeRequest(ReadService.UDS_22, "F406", header = "7E0"),
                            ProbeRequest(ReadService.UDS_22, "2E0101FF"), // rejeitado
                        )
                    ),
                    TokenUsage(1000, 200),
                ),
                PlannerStep.Finish(profile, TokenUsage(1500, 300)),
            )
        )
        val record = LlmExplorationSession(planner, ecu::send, { true }, { false }, budget = noSurvey, clock = clock)
            .run(ExplorationContext(wmi = "9BW"))

        assertEquals(StopReason.PLANNER_FINISHED, record.stopReason)
        assertEquals(1, record.rounds.size)
        assertEquals(1, record.rounds[0].rejected.size)
        assertEquals(listOf(ProbeStatus.POSITIVE, ProbeStatus.NEGATIVE), record.rounds[0].outcomes.map { it.status })
        assertEquals(3000, record.totalUsage.total)
        assertTrue(ecu.sent.none { it.startsWith("2E") })
        assertEquals(SignalVerification.Verdict.VERIFIED, record.verifications.single().verdict)
        assertEquals(90.0, record.verifications.single().oemValue)
    }

    @Test
    fun stopsWithoutProgressAndAsksFinalProfile() = runTest {
        val ecu = FakeEcu()
        var n = 0
        val planner = object : LlmExplorationPlanner {
            var finalAsked = false
            override suspend fun nextStep(context: ExplorationContext, history: List<ExplorationRound>): PlannerStep {
                if (context.mustFinish) {
                    finalAsked = true
                    return PlannerStep.Finish(CandidateProfile(notes = "nada"))
                }
                return PlannerStep.Probe(ExplorationPlan(listOf(ProbeRequest(ReadService.UDS_22, "10" + (n++).toString(16).uppercase().padStart(2, '0')))))
            }
        }
        val record = LlmExplorationSession(planner, ecu::send, { true }, { false }, budget = noSurvey, clock = clock)
            .run(ExplorationContext())
        assertEquals(StopReason.NO_PROGRESS, record.stopReason)
        assertEquals(2, record.rounds.size)
        assertTrue(planner.finalAsked)
        assertEquals("nada", record.profile?.notes)
    }

    @Test
    fun abortsWhenEngineRunning() = runTest {
        val ecu = FakeEcu()
        val planner = ScriptedPlanner(listOf(PlannerStep.Finish(CandidateProfile())))
        val record = LlmExplorationSession(planner, ecu::send, { true }, { true }, budget = noSurvey, clock = clock)
            .run(ExplorationContext())
        assertEquals(StopReason.ENGINE_RUNNING, record.stopReason)
        assertTrue(planner.contexts.isEmpty())
        assertTrue(ecu.sent.all { it.startsWith("AT") })
    }

    @Test
    fun respectsTokenBudget() = runTest {
        val ecu = FakeEcu()
        var n = 0
        val planner = object : LlmExplorationPlanner {
            override suspend fun nextStep(context: ExplorationContext, history: List<ExplorationRound>): PlannerStep =
                if (context.mustFinish) PlannerStep.Finish(CandidateProfile())
                else PlannerStep.Probe(ExplorationPlan(listOf(ProbeRequest(ReadService.UDS_22, "F4" + (n++).toString(16).uppercase().padStart(2, '0')))), TokenUsage(60_000, 0))
        }
        val record = LlmExplorationSession(
            planner, ecu::send, { true }, { false },
            budget = noSurvey.copy(maxRoundsWithoutProgress = 99), clock = clock,
        ).run(ExplorationContext())
        assertEquals(StopReason.MAX_TOKENS, record.stopReason)
        assertEquals(2, record.rounds.size)
    }
}

class DeterministicSurveyTest {

    /**
     * ECU só em 7E0 (CAN 11/500): VIN em F190, part number em F187, bloco proprietário
     * 1000..1003. Outros DIDs → 7F 22 31; outros headers → NO DATA; $21 → 7F 21 11.
     */
    private class OemEcu {
        val sent = mutableListOf<String>()
        private var header = ""
        suspend fun send(cmd: String, @Suppress("UNUSED_PARAMETER") timeout: Long): String {
            sent += cmd
            if (cmd.startsWith("ATSH")) header = cmd.removePrefix("ATSH")
            if (cmd.startsWith("AT")) return "OK"
            if (header != "7E0") return "NO DATA"
            return when (cmd) {
                "22F190" -> "62 F1 90 57 56 57"
                "22F187" -> "62 F1 87 30 33"
                "221000", "221001", "221002", "221003" -> "62 ${cmd.substring(2, 4)} ${cmd.substring(4, 6)} 7E"
                else -> if (cmd.startsWith("21")) "7F 21 11" else "7F 22 31"
            }
        }
    }

    private var now = 0L
    private val clock = { now.also { now += 10 } }

    @Test
    fun surveyMapsEcuSamplesBlocksAndExpandsNeighborsWithoutTokens() = runTest {
        val ecu = OemEcu()
        val contexts = mutableListOf<List<ExplorationRound>>()
        val planner = object : LlmExplorationPlanner {
            override suspend fun nextStep(context: ExplorationContext, history: List<ExplorationRound>): PlannerStep {
                contexts += history
                return PlannerStep.Finish(CandidateProfile())
            }
        }
        val record = LlmExplorationSession(planner, ecu::send, { true }, { false }, clock = clock)
            .run(ExplorationContext())

        val bySource = record.rounds.groupBy { it.source }
        assertTrue(RoundSource.SURVEY_ECU_MAP in bySource)
        assertTrue(RoundSource.SURVEY_DID_RANGES in bySource)
        assertTrue(RoundSource.NEIGHBORS in bySource)
        val positives = record.rounds.flatMap { it.outcomes }
            .filter { it.status == ProbeStatus.POSITIVE }
            .map { it.request.id }.toSet()
        // F190 (ping) → F187 (vizinho); 1000 (bloco) → 1001..1003 (vizinhos)
        assertEquals(setOf("F190", "F187", "1000", "1001", "1002", "1003"), positives)
        // Só uma faixa de DIDs (só 7E0 existe) e o LLM já recebe tudo no primeiro passo.
        assertEquals(1, bySource.getValue(RoundSource.SURVEY_DID_RANGES).size)
        assertEquals(record.rounds.size, contexts.first().size)
        assertEquals(0, record.totalUsage.total)
        // Achou ECU no protocolo 6: não tenta a taxa 250k (8) para 11-bit.
        assertTrue("ATSP8" !in ecu.sent)
    }

    @Test
    fun prunesServiceAfterServiceNotSupported() = runTest {
        val ecu = OemEcu()
        var step = 0
        val planner = object : LlmExplorationPlanner {
            override suspend fun nextStep(context: ExplorationContext, history: List<ExplorationRound>): PlannerStep {
                if (step++ > 0) return PlannerStep.Finish(CandidateProfile())
                return PlannerStep.Probe(ExplorationPlan((1..5).map { ProbeRequest(ReadService.KWP_21, "0$it", header = "7E0") }))
            }
        }
        val record = LlmExplorationSession(
            planner, ecu::send, { true }, { false },
            budget = ExplorationBudget(surveyEnabled = false), clock = clock,
        ).run(ExplorationContext())
        val llm = record.rounds.first { it.source == RoundSource.LLM }
        assertEquals(1, llm.outcomes.size)
        assertEquals(4, llm.rejected.count { it.reason == "service_unsupported" })
        assertEquals(1, ecu.sent.count { it.startsWith("21") })
    }

    @Test
    fun neighborProbesStayInRangeAndWidth() {
        val n = DeterministicSurvey.neighborProbes(ProbeRequest(ReadService.UDS_22, "0001", "7E0"), 3)
        assertEquals(listOf("0000", "0002", "0003", "0004"), n.map { it.id }.sorted())
        assertTrue(n.all { ExplorationPlanValidator.rejectionReason(it) == null })
        val k = DeterministicSurvey.neighborProbes(ProbeRequest(ReadService.KWP_21, "FE"), 2)
        assertEquals(listOf("FC", "FD", "FF"), k.map { it.id }.sorted())
    }
}
