package io.ecucore

import io.ecucore.connection.EcuTcpConnection
import io.ecucore.model.MegaSpeedAuxPort
import io.ecucore.model.MegaSpeedTestCommand
import io.ecucore.model.MegaSpeedTestParams
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Teste de saídas ponta a ponta: EcuClient + EcuTcpConnection reais contra o simulador da
 * CostaMegaSpeed (`simulator/costa_tcp_simulator.py` do app). Só roda com COSTA_SIMULATOR apontando
 * para o script; sem a variável, o teste é ignorado (o ecu-core não carrega o simulador).
 */
class MegaSpeedOutputTestSimulatorE2ETest {

    @Test
    fun `fluxo completo de teste de saidas contra o simulador`() = runBlocking<Unit> {
        val script = System.getenv("COSTA_SIMULATOR")?.let(::File)?.takeIf { it.isFile } ?: return@runBlocking
        val port = ServerSocket(0).use { it.localPort }
        val log = File.createTempFile("costa_sim", ".log")
        val proc = ProcessBuilder("python3", "-u", script.path, "--host", "127.0.0.1", "--port", port.toString())
            .redirectErrorStream(true).redirectOutput(log).start()
        try {
            repeat(50) { if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) return@repeat; Thread.sleep(100) }
            val client = EcuClient(EcuTcpConnection("127.0.0.1", port), {}, {}, {})
            client.connect()

            assertFalse(client.readMegaSpeedTestStatus().testModeActive)
            client.executeMegaSpeedTestCommand(MegaSpeedTestCommand.ENTER_TEST_MODE)
            assertTrue(client.readMegaSpeedTestStatus().testModeActive, "status3 bit 3 deveria ligar")

            client.executeMegaSpeedTestCommand(MegaSpeedTestCommand.FUEL_PUMP_ON)
            client.executeMegaSpeedTestCommand(MegaSpeedTestCommand.FUEL_PUMP_OFF)
            for (p in MegaSpeedAuxPort.entries) {
                client.executeMegaSpeedTestCommand(MegaSpeedTestCommand.auxOn(p))
                client.executeMegaSpeedTestCommand(MegaSpeedTestCommand.auxOff(p))
            }
            client.executeMegaSpeedTestCommand(MegaSpeedTestCommand.IDLE_ON)
            client.executeMegaSpeedTestCommand(MegaSpeedTestCommand.IDLE_OFF)

            val params = MegaSpeedTestParams(
                coilMode = 1, injectorMode = 1, coilSelect = 2, injectorSelect = 1,
                dwellRaw = 20, intervalRaw = 781, pulseWidthRaw = 1502, pulseCount = 10,
            )
            client.writeMegaSpeedTestParams(params)
            assertEquals(params, client.readMegaSpeedTestParams())
            client.executeMegaSpeedTestCommand(MegaSpeedTestCommand.SPARK_PULSE)
            client.executeMegaSpeedTestCommand(MegaSpeedTestCommand.INJECTOR_PULSE)

            assertEquals(0, client.emergencyStopMegaSpeedTest())
            assertFalse(client.readMegaSpeedTestStatus().testModeActive, "parada deveria sair do modo de teste")
            client.disconnect()
        } finally {
            proc.destroy()
            proc.waitFor()
        }
        val text = log.readText()
        println(text.lines().filter { "[TEST]" in it }.joinToString("\n"))
        for (expected in listOf(
            "modo de teste ATIVADO", "bomba de combustível LIGADA", "bomba de combustível desligada",
            "porta D15 LIGADA", "porta FIDLE desligada", "atuador de lenta: posição alvo",
            "pulsando bobina", "pulsando injetor", "modo de teste DESATIVADO",
        )) assertTrue(expected in text, "simulador não registrou: $expected")
        assertFalse("chave inválida" in text || "código desconhecido" in text)
    }
}
