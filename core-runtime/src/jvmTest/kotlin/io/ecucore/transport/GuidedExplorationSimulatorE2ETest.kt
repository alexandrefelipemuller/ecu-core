package io.ecucore.transport

import io.ecucore.connection.EcuTcpConnection
import io.ecucore.transport.exploration.ExplorationBudget
import io.ecucore.transport.exploration.ExplorationRecord
import io.ecucore.transport.exploration.LocalExplorationPlanner
import io.ecucore.transport.exploration.ReferenceQuantity
import io.ecucore.transport.exploration.SignalVerification
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exploração guiada ponta a ponta: Obd2Transport + EcuTcpConnection reais contra o simulador
 * OBD2 do app (`simulator/obd2_tcp_simulator.py --vehicle llm_explore_test`). O preflight
 * falha, o transporte pede consentimento, roda a varredura e a sessão com o planner local.
 * Só roda com OBD2_SIMULATOR apontando para o script; sem a variável, é ignorado.
 */
class GuidedExplorationSimulatorE2ETest {

    @Test
    fun `explora ECU so UDS do simulador e descobre sinais OEM`() = runBlocking<Unit> {
        val script = System.getenv("OBD2_SIMULATOR")?.let(::File)?.takeIf { it.isFile } ?: return@runBlocking
        val port = ServerSocket(0).use { it.localPort }
        val log = File.createTempFile("obd2_sim", ".log")
        val proc = ProcessBuilder(
            "python3", "-u", script.path, "--host", "127.0.0.1", "--port", port.toString(),
            "--vehicle", "llm_explore_test",
        ).redirectErrorStream(true).redirectOutput(log).start()
        var captured: ExplorationRecord? = null
        try {
            repeat(50) { if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) return@repeat; Thread.sleep(100) }
            val hooks = object : Obd2FailureExplorationHooks {
                override suspend fun askConsent(failureSummary: String) = true
                override fun onResult(result: Obd2ExplorationResult) {}
                override suspend fun prepareExploration(result: Obd2ExplorationResult) = ExplorationRequest(
                    planner = LocalExplorationPlanner(random = Random(7)),
                    context = io.ecucore.transport.exploration.ExplorationContext(baselineEntries = result.entries),
                    budget = ExplorationBudget(resampleCount = 3, maxDurationMs = 240_000L),
                )
                override fun onExplorationResult(record: ExplorationRecord) { captured = record }
            }
            val transport = Obd2Transport(
                connection = EcuTcpConnection("127.0.0.1", port),
                onDataReceived = {}, onConnectionStateChanged = {}, onError = {},
                failureExplorationHooks = hooks,
            )
            runCatching { transport.connect() } // preflight falha de propósito
            runCatching { transport.disconnect() }
        } finally {
            proc.destroy()
            proc.waitFor()
        }
        val r = assertNotNull(captured, "exploração não rodou; log do simulador:\n${log.readText().takeLast(2000)}")
        println("stop=${r.stopReason} rounds=${r.rounds.size} probes=${r.rounds.sumOf { it.outcomes.size }} elapsed=${r.elapsedMs}ms")
        println("notes=${r.profile?.notes}")
        r.verifications.forEach { println("${it.signal.name} ${it.signal.id}@${it.signal.byteOffset} oem=${it.oemValue} ref=${it.referenceValue} ${it.verdict}") }

        val profile = assertNotNull(r.profile)
        val ect = profile.signals.single { it.reference == ReferenceQuantity.COOLANT_TEMP }
        assertEquals("1002", ect.id)
        assertEquals("1001", profile.signals.single { it.reference == ReferenceQuantity.MODULE_VOLTAGE }.id)
        assertTrue(r.verifications.all { it.verdict == SignalVerification.Verdict.VERIFIED })
        assertTrue("dtc=7E0:+" in profile.notes.orEmpty())
    }
}
