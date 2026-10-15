package io.ecucore

import io.ecucore.model.MegaSpeedAuxPort
import io.ecucore.model.MegaSpeedOutputTest
import io.ecucore.model.MegaSpeedTestCommand
import io.ecucore.model.MegaSpeedTestParams
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Teste de saídas da MegaSpeed no client: o que realmente vai (ou não) para a ECU. */
class SpeeduinoClientMegaSpeedOutputTestTest {

    private suspend fun connect(
        kind: ClientFakeEcu.Kind = ClientFakeEcu.Kind.MEGASPEED,
        rpm: Int = 0,
        testMode: Boolean = true,
    ): Pair<SpeeduinoClient, ClientFakeEcu> {
        val ecu = ClientFakeEcu(kind)
        val client = SpeeduinoClient(ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        client.connect()
        setLive(ecu, rpm, testMode)
        return client to ecu
    }

    /** rpm no offset 6 (U16 BE) e status3 no offset 80 do bloco de saída de 219 bytes. */
    private fun setLive(ecu: ClientFakeEcu, rpm: Int, testMode: Boolean) {
        ecu.liveBytes = ByteArray(219).also {
            it[6] = (rpm shr 8).toByte()
            it[7] = rpm.toByte()
            it[MegaSpeedOutputTest.STATUS3_OFFSET] = if (testMode) 0x08 else 0x00
        }
    }

    private fun ClientFakeEcu.commandWrites() =
        writes.filter { it.id == MegaSpeedOutputTest.COMMAND_TABLE && it.offset == MegaSpeedOutputTest.COMMAND_OFFSET }

    private fun codes(ecu: ClientFakeEcu) = ecu.commandWrites().map { it.data[2].toInt() and 0xFF }

    @Test
    fun `bomba ligada envia exatamente o frame do ini`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        c.executeMegaSpeedTestCommand(MegaSpeedTestCommand.FUEL_PUMP_ON)

        val w = ecu.commandWrites().single()
        assertEquals(listOf(0x30, 0x39, 0x05), w.data.map { it.toInt() and 0xFF })
        assertTrue(ecu.burns.isEmpty(), "comando de teste nunca queima configuração")
    }

    @Test
    fun `motor com rotacao recusa e nao envia nada`() = runBlocking<Unit> {
        val (c, ecu) = connect(rpm = 850)
        val e = assertFailsWith<MegaSpeedTestRefusedException> {
            c.executeMegaSpeedTestCommand(MegaSpeedTestCommand.FUEL_PUMP_ON)
        }
        assertTrue("850" in e.reason)
        assertTrue(ecu.commandWrites().isEmpty())
    }

    @Test
    fun `sem modo de teste ativo recusa acionamento mas permite entrar no modo`() = runBlocking<Unit> {
        val (c, ecu) = connect(testMode = false)
        assertFailsWith<MegaSpeedTestRefusedException> {
            c.executeMegaSpeedTestCommand(MegaSpeedTestCommand.auxOn(MegaSpeedAuxPort.D15))
        }
        assertTrue(ecu.commandWrites().isEmpty())

        c.executeMegaSpeedTestCommand(MegaSpeedTestCommand.ENTER_TEST_MODE)
        assertEquals(listOf(0x01), codes(ecu))
    }

    @Test
    fun `entrar no modo de teste com motor girando e recusado`() = runBlocking<Unit> {
        val (c, ecu) = connect(rpm = 1200, testMode = false)
        assertFailsWith<MegaSpeedTestRefusedException> {
            c.executeMegaSpeedTestCommand(MegaSpeedTestCommand.ENTER_TEST_MODE)
        }
        assertTrue(ecu.commandWrites().isEmpty())
    }

    @Test
    fun `desligar nunca e bloqueado pelo guard nem pelo estado`() = runBlocking<Unit> {
        val (c, ecu) = connect(rpm = 3000, testMode = false)
        c.writeGuard = { false } // usuário negou qualquer escrita
        c.executeMegaSpeedTestCommand(MegaSpeedTestCommand.FUEL_PUMP_OFF)
        c.executeMegaSpeedTestCommand(MegaSpeedTestCommand.auxOff(MegaSpeedAuxPort.FIDLE))
        assertEquals(listOf(0x06, 0x9C), codes(ecu)) // cmdfpoff e cmdtest17off do .ini
    }

    @Test
    fun `writeGuard negado bloqueia acionamento`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        c.writeGuard = { false }
        assertFailsWith<WriteNotPermittedException> {
            c.executeMegaSpeedTestCommand(MegaSpeedTestCommand.FUEL_PUMP_ON)
        }
        assertTrue(ecu.commandWrites().isEmpty())
    }

    @Test
    fun `parada de emergencia envia tudo na ordem e termina saindo do modo de teste`() = runBlocking<Unit> {
        val (c, ecu) = connect(rpm = 2000, testMode = false) // vale mesmo com estado "ruim"
        c.writeGuard = { false }
        val failures = c.emergencyStopMegaSpeedTest()

        assertEquals(0, failures)
        assertEquals(MegaSpeedTestCommand.STOP_ALL.map { it.code }, codes(ecu))
        assertEquals(0x00, codes(ecu).last())
    }

    @Test
    fun `parametros de bobina e injetor sao validados contra o que esta na ECU`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        // ECU com parâmetros zerados: sem modo/dwell válidos, bobina deve ser recusada
        assertFailsWith<MegaSpeedTestRefusedException> {
            c.executeMegaSpeedTestCommand(MegaSpeedTestCommand.SPARK_PULSE)
        }
        assertTrue(ecu.commandWrites().isEmpty())

        c.writeMegaSpeedTestParams(
            MegaSpeedTestParams(coilMode = 1, coilSelect = 1, dwellRaw = 20, intervalRaw = 800),
        )
        c.executeMegaSpeedTestCommand(MegaSpeedTestCommand.SPARK_PULSE)
        assertEquals(listOf(0x02), codes(ecu))

        // dwell acima do limite do app é recusado ANTES de ir para a ECU
        val tooLong = MegaSpeedTestParams(coilMode = 1, dwellRaw = 255, intervalRaw = 800)
        c.writeMegaSpeedTestParams(tooLong)
        assertFailsWith<MegaSpeedTestRefusedException> {
            c.executeMegaSpeedTestCommand(MegaSpeedTestCommand.SPARK_PULSE)
        }
        assertEquals(listOf(0x02), codes(ecu))
    }

    @Test
    fun `gravar parametros so toca a faixa do teste na RAM e nao queima`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        ecu.load(MegaSpeedOutputTest.PARAMS_PAGE, 0, byteArrayOf(0x11, 0x22)) // vizinhos que não podem mudar
        c.writeMegaSpeedTestParams(MegaSpeedTestParams(coilMode = 1, dwellRaw = 20, intervalRaw = 800))

        val w = ecu.writes.single { it.id == MegaSpeedOutputTest.PARAMS_PAGE }
        assertEquals(MegaSpeedTestParams.OFFSET_OP, w.offset)
        assertEquals(MegaSpeedTestParams.PARAMS_END - MegaSpeedTestParams.OFFSET_OP, w.data.size)
        assertEquals(0x11, ecu.mem(MegaSpeedOutputTest.PARAMS_PAGE)[0].toInt())
        assertTrue(ecu.burns.isEmpty())
    }

    @Test
    fun `watchdog desliga tudo se ninguem desligar`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        c.megaSpeedTestWatchdogOverrideMs = 50
        c.executeMegaSpeedTestCommand(MegaSpeedTestCommand.FUEL_PUMP_ON)
        delay(600)

        val sent = codes(ecu)
        assertEquals(0x05, sent.first())
        assertEquals(MegaSpeedTestCommand.STOP_ALL.map { it.code }, sent.drop(1))
    }

    @Test
    fun `sair do modo de teste cancela o watchdog`() = runBlocking<Unit> {
        val (c, ecu) = connect()
        // Prazo bem maior que o tempo de envio na fake, senão o watchdog expira legitimamente antes do STOP.
        c.megaSpeedTestWatchdogOverrideMs = 1500
        c.executeMegaSpeedTestCommand(MegaSpeedTestCommand.FUEL_PUMP_ON)
        c.executeMegaSpeedTestCommand(MegaSpeedTestCommand.STOP_TEST_MODE)
        delay(2200)
        assertEquals(listOf(0x05, 0x00), codes(ecu))
    }

    @Test
    fun `outras familias nao suportam o teste`() = runBlocking<Unit> {
        for (kind in listOf(ClientFakeEcu.Kind.MS2, ClientFakeEcu.Kind.MS3, ClientFakeEcu.Kind.SPEEDUINO_2025)) {
            val (c, ecu) = connect(kind)
            assertFailsWith<UnsupportedOperationException>(kind.name) {
                c.executeMegaSpeedTestCommand(MegaSpeedTestCommand.FUEL_PUMP_OFF)
            }
            assertFailsWith<UnsupportedOperationException>(kind.name) { c.emergencyStopMegaSpeedTest() }
            assertTrue(ecu.commandWrites().isEmpty(), kind.name)
        }
    }
}
