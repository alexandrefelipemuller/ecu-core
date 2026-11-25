package io.ecucore.transport.exploration

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ECU em 7E0 com bloco proprietário 4A00..4A1F. O DID 4A10 traz
 * [0x12, ECT+40, tensão mV (2 bytes BE)], que acompanham o modo 01 (0105 / 0142).
 * Cada leitura do 0105 avança o "tempo" (temperatura e tensão mudam), então o fitter
 * precisa parear cada amostra com a referência certa.
 */
private class DriftingEcu {
    val sent = mutableListOf<String>()
    private var header = ""
    private var t = 0

    private val ect get() = 85 + t
    private val millivolts get() = 12_000 + t * 10

    suspend fun send(cmd: String, @Suppress("UNUSED_PARAMETER") timeout: Long): String {
        sent += cmd
        if (cmd.startsWith("ATSH")) header = cmd.removePrefix("ATSH")
        if (cmd.startsWith("AT")) return "OK"
        if (header != "7E0" && header != "7DF") return "NO DATA"
        if (header == "7DF" && !cmd.startsWith("01")) return "NO DATA"
        return when {
            cmd == "0105" -> { t++; "41 05 ${hex(ect + 40)}" }
            cmd == "0142" -> "41 42 ${hex(millivolts shr 8)} ${hex(millivolts and 0xFF)}"
            cmd == "010C" -> "41 0C 00 00"
            cmd.startsWith("01") -> "NO DATA"
            cmd == "1902FF" -> "59 02 FF 01 23 45 08"
            cmd == "22F190" -> "62 F1 90 57 56 57"
            cmd == "224A10" -> "62 4A 10 12 ${hex(ect + 40)} ${hex(millivolts shr 8)} ${hex(millivolts and 0xFF)}"
            cmd.startsWith("224A") && cmd.substring(4, 6).toInt(16) < 0x20 -> "62 4A ${cmd.substring(4, 6)} 55"
            cmd.startsWith("21") || cmd.startsWith("1A") -> "7F ${cmd.take(2)} 11"
            cmd.startsWith("09") -> "NO DATA"
            else -> "7F 22 31"
        }
    }

    private fun hex(v: Int) = v.toString(16).uppercase().padStart(2, '0')
}

/**
 * Espelha o perfil `llm_explore_test` do simulador: nada de modo 01 (BUS INIT ERROR), só UDS
 * em 7E0, com espelhos F4xx dos PIDs e bloco proprietário 1000..1005 (valores fixos).
 */
private class UdsOnlyEcu {
    private var header = ""
    suspend fun send(cmd: String, @Suppress("UNUSED_PARAMETER") timeout: Long): String {
        if (cmd.startsWith("ATSH")) header = cmd.removePrefix("ATSH")
        if (cmd.startsWith("AT")) return "OK"
        if (!cmd.startsWith("22") && !cmd.startsWith("19")) return "BUS INIT: ...ERROR"
        if (header != "7E0") return "NO DATA"
        return when (cmd) {
            "1902FF" -> "59 02 FF 01 71 00 08"
            "22F190" -> "62 F1 90 57 56 57"
            "22F40C" -> "62 F4 0C 00 00"
            "22F405" -> "62 F4 05 82"
            "22F40D" -> "62 F4 0D 00"
            "22F442" -> "62 F4 42 31 2C"
            "221000" -> "62 10 00 01 02"
            "221001" -> "62 10 01 7E"
            "221002" -> "62 10 02 82"
            "221003" -> "62 10 03 00 00"
            "221005" -> "7F 22 33"
            else -> "7F 22 31"
        }
    }
}

class LocalExplorationPlannerTest {

    private var now = 0L
    private val clock = { now.also { now += 10 } }

    @Test
    fun discoversOemSignalsAndDtcsOffline() = runTest {
        val ecu = DriftingEcu()
        val record = ExplorationSession(
            planner = LocalExplorationPlanner(random = Random(42)),
            send = ecu::send,
            isConnected = { true },
            isEngineRunning = { false },
            budget = ExplorationBudget(resampleCount = 4),
            clock = clock,
        ).run(ExplorationContext())

        val profile = assertNotNull(record.profile, "stop=${record.stopReason} ${record.stopDetail}")
        val ect = profile.signals.single { it.reference == ReferenceQuantity.COOLANT_TEMP }
        assertEquals("4A10", ect.id)
        assertEquals("7E0", ect.header)
        assertEquals(1, ect.byteOffset)
        assertEquals(1, ect.byteLength)
        assertEquals(-40.0, ect.offset)
        val volts = profile.signals.single { it.reference == ReferenceQuantity.MODULE_VOLTAGE }
        assertEquals(2, volts.byteOffset)
        assertEquals(2, volts.byteLength)
        assertEquals(0.001, volts.scale)

        assertTrue(record.verifications.isNotEmpty())
        assertTrue(record.verifications.all { it.verdict == SignalVerification.Verdict.VERIFIED }, "${record.verifications}")
        assertTrue("dtc=7E0:+" in profile.notes.orEmpty(), profile.notes)
        assertTrue(RoundSource.RESAMPLE in record.rounds.map { it.source })
        assertTrue(RoundSource.PLANNER in record.rounds.map { it.source })
        assertEquals(0, record.totalUsage.total)

        // Nada fora da whitelist de leitura chegou ao barramento.
        val forbidden = listOf("10", "11", "14", "27", "28", "2E", "2F", "31", "34", "3B", "04")
        assertTrue(ecu.sent.filterNot { it.startsWith("AT") }.none { cmd -> forbidden.any { cmd.startsWith(it) } })
    }

    @Test
    fun usesF4MirrorAsReferenceWhenEcuSpeaksOnlyUds() = runTest {
        val record = ExplorationSession(
            planner = LocalExplorationPlanner(random = Random(3)),
            send = UdsOnlyEcu()::send,
            isConnected = { true },
            isEngineRunning = { null },
            budget = ExplorationBudget(resampleCount = 3),
            clock = clock,
        ).run(ExplorationContext())

        val profile = assertNotNull(record.profile, "stop=${record.stopReason} ${record.stopDetail}")
        val ect = profile.signals.single { it.reference == ReferenceQuantity.COOLANT_TEMP }
        assertEquals("1002", ect.id)
        assertEquals(-40.0, ect.offset)
        val volts = profile.signals.single { it.reference == ReferenceQuantity.MODULE_VOLTAGE }
        assertEquals("1001", volts.id)
        assertEquals(0.1, volts.scale)
        // Os espelhos F4xx são referência, não "descoberta".
        assertTrue(profile.signals.none { it.id.startsWith("F4") }, "${profile.signals}")
        assertTrue(record.verifications.all { it.verdict == SignalVerification.Verdict.VERIFIED }, "${record.verifications}")
    }

    @Test
    fun neverRepeatsTriedOrPrunedProbes() = runTest {
        val planner = LocalExplorationPlanner(random = Random(1), batchSize = 50)
        val tried = ProbeRequest(ReadService.UDS_22, "F190", "7E0")
        val kwp = ProbeRequest(ReadService.KWP_21, "01", "7E0")
        val history = listOf(
            ExplorationRound(
                index = 0,
                plan = ExplorationPlan(listOf(tried, kwp)),
                rejected = emptyList(),
                outcomes = listOf(
                    ProbeOutcome(tried, emptyList(), ProbeStatus.POSITIVE, listOf(0x57), rawResponse = "", elapsedMs = 1),
                    ProbeOutcome(kwp, emptyList(), ProbeStatus.NEGATIVE, nrc = 0x11, rawResponse = "", elapsedMs = 1),
                ),
                usage = TokenUsage(),
                source = RoundSource.SURVEY_ECU_MAP,
            ),
        )
        val step = assertIs<PlannerStep.Probe>(planner.nextStep(ExplorationContext(probesLeft = 50), history))
        val probes = step.plan.probes
        assertTrue(probes.isNotEmpty())
        assertTrue(probes.none { it.service == ReadService.UDS_22 && it.id == "F190" && it.header == "7E0" })
        assertTrue(probes.none { it.service == ReadService.KWP_21 && it.header == "7E0" })
        assertTrue(probes.all { ExplorationPlanValidator.rejectionReason(it) == null })
        // Identificação F1xx tem prior alto e já teve acerto: deve dominar o lote.
        assertTrue(probes.count { it.id.startsWith("F1") } > probes.size / 2, "$probes")
    }

    @Test
    fun finishesWhenAskedOrOutOfBudget() = runTest {
        val planner = LocalExplorationPlanner(random = Random(1))
        assertIs<PlannerStep.Finish>(planner.nextStep(ExplorationContext(mustFinish = true, probesLeft = 10), emptyList()))
        assertIs<PlannerStep.Finish>(planner.nextStep(ExplorationContext(probesLeft = 0), emptyList()))
    }
}

class ProbePriorsTest {

    @Test
    fun parsesAndIgnoresGarbage() {
        val priors = ProbePriors.parse(
            """
            version 3
            # comentário
            UDS_22|F1 30 10
            UDS_22|7E0|4A 5 5   # nível de bloco
            b:fiat|UDS_22|7E0|4A 9 1
            lixo
            UDS_22|F4 -1 2
            UDS_22|F8 NaN 2
            """.trimIndent()
        )
        assertEquals(3, priors.version)
        assertEquals(3, priors.size)
        val probe = ProbeRequest(ReadService.UDS_22, "4A10", "7E0")
        assertEquals(0.9, priors.lookup(probe, "Fiat").mean)
        assertEquals(0.5, priors.lookup(probe, null).mean)
        assertEquals(30.0, priors.lookup(ProbeRequest(ReadService.UDS_22, "F190", "7E1"), null).alpha)
        assertEquals(1.0, priors.lookup(ProbeRequest(ReadService.KWP_21, "01"), null).alpha) // default
    }

    @Test
    fun betaSamplesStayInRangeAndTrackMean() {
        val random = Random(7)
        val samples = List(2_000) { BetaSampler.sample(8.0, 2.0, random) }
        assertTrue(samples.all { it in 0.0..1.0 })
        assertTrue(samples.average() in 0.75..0.85, "${samples.average()}")
        val small = List(500) { BetaSampler.sample(0.5, 0.5, random) }
        assertTrue(small.all { it in 0.0..1.0 })
    }
}

class SignalFitterTest {

    private fun round(vararg outcomes: ProbeOutcome) = ExplorationRound(
        0, ExplorationPlan(emptyList()), emptyList(), outcomes.toList(), TokenUsage(), RoundSource.RESAMPLE,
    )

    private fun ref(pid: String, vararg data: Int) =
        ProbeOutcome(ProbeRequest(ReadService.OBD_MODE_01, pid, "7DF"), emptyList(), ProbeStatus.POSITIVE, data.toList(), rawResponse = "", elapsedMs = 1)

    private fun did(id: String, vararg data: Int) =
        ProbeOutcome(ProbeRequest(ReadService.UDS_22, id, "7E0"), emptyList(), ProbeStatus.POSITIVE, data.toList(), rawResponse = "", elapsedMs = 1)

    @Test
    fun needsEverySampleToMatch() {
        // 2ª amostra diverge (ECT 91 vs byte 0x7D=125-40=85): descartado.
        val profile = SignalFitter.fit(listOf(round(ref("05", 130), did("1234", 130), ref("05", 131), did("1234", 125))))
        assertTrue(profile.signals.isEmpty(), "${profile.signals}")
    }

    @Test
    fun requiresAtLeastTwoSamplesAndSkipsTrivialRpm() {
        assertTrue(SignalFitter.fit(listOf(round(ref("05", 130), did("1234", 130)))).signals.isEmpty())
        val rpm = SignalFitter.fit(listOf(round(ref("0C", 0, 0), did("1234", 0, 0, 7), ref("0C", 0, 0), did("1234", 0, 0, 7))))
        assertTrue(rpm.signals.none { it.reference == ReferenceQuantity.RPM })
    }

    @Test
    fun picksVaryingByteAndDoesNotReuseItForTwoQuantities() {
        // ECT == IAT (motor frio). Byte 0 acompanha a variação; byte 1 é fixo (bateu só por acaso).
        val profile = SignalFitter.fit(
            listOf(
                round(
                    ref("05", 60), ref("0F", 60), did("2000", 60, 60),
                    ref("05", 61), ref("0F", 61), did("2000", 61, 60),
                    ref("05", 62), ref("0F", 62), did("2000", 62, 61),
                ),
            ),
        )
        val bytesUsed = profile.signals.map { it.id to it.byteOffset }
        assertEquals(bytesUsed.toSet().size, bytesUsed.size)
        // O byte que varia junto vence o primeiro lugar; o fixo, no máximo, fica com a outra grandeza.
        assertTrue(profile.signals.any { it.byteOffset == 0 }, "${profile.signals}")
    }
}
