package io.ecucore

import io.ecucore.connection.ISpeeduinoConnection
import io.ecucore.model.AfrTable
import io.ecucore.model.ClosedLoopCorrectionConfig
import io.ecucore.model.CutMethod
import io.ecucore.model.DwellTable
import io.ecucore.model.EngineProtectionConfig
import io.ecucore.model.IdleControlSettings
import io.ecucore.model.IgnitionTable
import io.ecucore.model.PressureCalibration
import io.ecucore.model.ProtectionCut
import io.ecucore.model.SecondarySerialConfig
import io.ecucore.model.SecondarySerialProtocol
import io.ecucore.model.TpsCalibration
import io.ecucore.model.TriggerSettings
import io.ecucore.model.ValidationException
import io.ecucore.model.VeTable
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Contratos de segurança das escritas (HARA H1/H7, docs/HARA.md):
 *  - modo somente leitura: nenhuma escrita/burn pode chegar à ECU;
 *  - avanço de ignição perigoso: o client não envia nada nem faz burn.
 */
class SpeeduinoClientWriteSafetyTest {

    @Test
    fun `modo somente leitura bloqueia toda escrita e burn sem enviar bytes`() = runBlocking {
        val ecu = CountingEcu()
        val client = newClient(ecu)
        client.connect()
        client.setManualFirmwareProfile("speeduino 202501", readOnly = true)
        assertTrue(client.isReadOnlySafeMode())
        val sentBefore = ecu.sent

        val operations: Map<String, suspend () -> Unit> = mapOf(
            "writeVeTable" to { client.writeVeTable(veTable(40), 1) },
            "writeIgnitionTable" to { client.writeIgnitionTable(ignitionTable(10), 1) },
            "writeAfrTable" to { client.writeAfrTable(afrTable(147)) },
            "writeDwellTable" to { client.writeDwellTable(DwellTable.createDefault()) },
            "writeIdleControlSettings" to { client.writeIdleControlSettings(IdleControlSettings(), burn = true) },
            "writePressureCalibration" to { client.writePressureCalibration(PressureCalibration(0, 1023, 0, 1023, 0, 1023), burn = true) },
            "writeTpsCalibration" to { client.writeTpsCalibration(TpsCalibration(0, 255), burn = true) },
            "writeSecondarySerialConfig" to { client.writeSecondarySerialConfig(SecondarySerialConfig(true, SecondarySerialProtocol.fromRaw(0), 0), burn = true) },
            "writeClosedLoopCorrectionConfig" to { client.writeClosedLoopCorrectionConfig(ClosedLoopCorrectionConfig(), burn = true) },
            "writeEngineProtectionConfig" to {
                client.writeEngineProtectionConfig(
                    EngineProtectionConfig(
                        protectionCut = ProtectionCut.OFF,
                        cutMethod = CutMethod.FULL,
                        engineProtectionRpmMin = 0,
                        engineProtectEnabled = false,
                        revLimiterEnabled = false,
                        boostLimitEnabled = false,
                        oilPressureProtectionEnabled = false,
                        afrProtectionEnabled = false,
                        coolantProtectionEnabled = false,
                    ),
                    burn = true,
                )
            },
            "writeTriggerSettings" to {
                client.writeTriggerSettings(
                    TriggerSettings(
                        triggerAngleDeg = 0,
                        triggerAngleMultiplier = 1,
                        triggerPattern = 0,
                        primaryBaseTeeth = 36,
                        missingTeeth = 1,
                        primaryTriggerSpeed = TriggerSettings.TriggerSpeed.CRANK,
                        triggerEdge = TriggerSettings.SignalEdge.RISING,
                        secondaryTriggerEdge = TriggerSettings.SignalEdge.RISING,
                        secondaryTriggerType = 0,
                        levelForFirstPhaseHigh = false,
                        skipRevolutions = 0,
                        triggerFilter = TriggerSettings.TriggerFilter.OFF,
                        reSyncEveryCycle = false,
                    ),
                    burn = true,
                )
            },
            "writeRawPage" to { client.writeRawPage(2, ByteArray(16)) },
            "writeRawPageWithoutBurn" to { client.writeRawPageWithoutBurn(2, ByteArray(16)) },
            "writeRawPageChunkedWithoutBurn" to { client.writeRawPageChunkedWithoutBurn(2, ByteArray(16), 8, 0) },
            "burnConfigs" to { client.burnConfigs() },
            "burnLastWrittenLegacyPage" to { client.burnLastWrittenLegacyPage() },
        )

        for ((name, operation) in operations) {
            val error = assertFailsWith<IllegalStateException>("$name deveria lançar em modo somente leitura") { operation() }
            assertTrue(error.message.orEmpty().contains("Read-only safe mode"), "$name: ${error.message}")
        }
        assertEquals(sentBefore, ecu.sent, "nenhum byte pode ir à ECU em modo somente leitura")
        assertEquals(0, ecu.writes)
        assertEquals(0, ecu.burns)
    }

    @Test
    fun `ignicao com avanco perigoso nao envia nada e nao faz burn`() = runBlocking {
        val ecu = CountingEcu()
        val client = newClient(ecu)
        client.connect()
        val sentBefore = ecu.sent

        val error = assertFailsWith<ValidationException> { client.writeIgnitionTable(ignitionTable(55), 1) }

        assertTrue(error.result.errors.isNotEmpty())
        assertEquals(sentBefore, ecu.sent, "tabela inválida não pode gerar tráfego para a ECU")
        assertEquals(0, ecu.writes)
        assertEquals(0, ecu.burns)
    }

    @Test
    fun `ignicao com avanco alto mas abaixo do limite grava com burn`() = runBlocking {
        val ecu = CountingEcu()
        val client = newClient(ecu)
        client.connect()

        client.writeIgnitionTable(ignitionTable(50), 1)

        assertTrue(ecu.writes > 0)
        assertEquals(1, ecu.burns)
    }

    @Test
    fun `AFR alvo perigosamente pobre nao envia nada e nao faz burn`() = runBlocking {
        val ecu = CountingEcu()
        val client = newClient(ecu)
        client.connect()
        val sentBefore = ecu.sent

        assertFailsWith<ValidationException> { client.writeAfrTable(afrTable(190)) }

        assertEquals(sentBefore, ecu.sent)
        assertEquals(0, ecu.burns)
    }

    @Test
    fun `VE sem combustivel nao envia nada e nao faz burn`() = runBlocking {
        val ecu = CountingEcu()
        val client = newClient(ecu)
        client.connect()
        val sentBefore = ecu.sent

        assertFailsWith<ValidationException> { client.writeVeTable(veTable(0), 1) }

        assertEquals(sentBefore, ecu.sent)
        assertEquals(0, ecu.burns)
    }

    @Test
    fun `calibracao de TPS invalida nao envia nada e nao faz burn`() = runBlocking {
        val ecu = CountingEcu()
        val client = newClient(ecu)
        client.connect()
        val sentBefore = ecu.sent

        assertFailsWith<ValidationException> { client.writeTpsCalibration(TpsCalibration(tpsMin = 200, tpsMax = 100), burn = true) }
        assertFailsWith<ValidationException> { client.writeTpsCalibration(TpsCalibration(tpsMin = 0, tpsMax = 300), burn = true) }

        assertEquals(sentBefore, ecu.sent)
        assertEquals(0, ecu.burns)
    }

    @Test
    fun `protecao do motor com RPM fora da faixa nao envia nada`() = runBlocking {
        val ecu = CountingEcu()
        val client = newClient(ecu)
        client.connect()
        val sentBefore = ecu.sent
        val config = EngineProtectionConfig(
            protectionCut = ProtectionCut.BOTH,
            cutMethod = CutMethod.FULL,
            engineProtectionRpmMin = 40000,
            engineProtectEnabled = true,
            revLimiterEnabled = true,
            boostLimitEnabled = false,
            oilPressureProtectionEnabled = false,
            afrProtectionEnabled = false,
            coolantProtectionEnabled = false,
        )

        assertFailsWith<ValidationException> { client.writeEngineProtectionConfig(config, burn = true) }

        assertEquals(sentBefore, ecu.sent)
        assertEquals(0, ecu.burns)
    }

    @Test
    fun `trigger com angulo fora da faixa nao envia nada`() = runBlocking {
        val ecu = CountingEcu()
        val client = newClient(ecu)
        client.connect()
        val sentBefore = ecu.sent
        val settings = TriggerSettings(
            triggerAngleDeg = 500,
            triggerAngleMultiplier = 1,
            triggerPattern = 0,
            primaryBaseTeeth = 36,
            missingTeeth = 1,
            primaryTriggerSpeed = TriggerSettings.TriggerSpeed.CRANK,
            triggerEdge = TriggerSettings.SignalEdge.RISING,
            secondaryTriggerEdge = TriggerSettings.SignalEdge.RISING,
            secondaryTriggerType = 0,
            levelForFirstPhaseHigh = false,
            skipRevolutions = 0,
            triggerFilter = TriggerSettings.TriggerFilter.OFF,
            reSyncEveryCycle = false,
        )

        assertFailsWith<ValidationException> { client.writeTriggerSettings(settings, burn = true) }

        assertEquals(sentBefore, ecu.sent)
        assertEquals(0, ecu.burns)
    }

    private fun veTable(value: Int) = VeTable(
        rpmBins = (1..16).map { it * 500 },
        loadBins = (1..16).map { it * 10 },
        values = List(16) { List(16) { value } },
    )

    private fun ignitionTable(value: Int) = IgnitionTable(
        rpmBins = (1..16).map { it * 500 },
        loadBins = (1..16).map { it * 10 },
        values = List(16) { List(16) { value } },
    )

    private fun afrTable(value: Int) = AfrTable(
        rpmBins = (1..16).map { it * 500 },
        loadBins = (1..16).map { it * 10 },
        values = List(16) { List(16) { value } },
    )

    private fun newClient(connection: ISpeeduinoConnection) = SpeeduinoClient(
        connection = connection,
        onDataReceived = {},
        onConnectionStateChanged = {},
        onError = {},
    )

    /**
     * ECU 202501 mínima: handshake Q/S e 'p'/'M'/'B' em envelope. Conta tudo que é enviado.
     */
    private class CountingEcu : ISpeeduinoConnection {
        private val pages = mutableMapOf<Int, ByteArray>()
        private val handshake = mutableMapOf(
            'Q'.code.toByte() to ArrayDeque(listOf(byteArrayOf(0xFF.toByte()), byteArrayOf(0xFF.toByte()))),
            'S'.code.toByte() to ArrayDeque(
                listOf(
                    "@@A speeduino 202501 A??".toByteArray(Charsets.US_ASCII),
                    "speeduino202501_BR1 ".toByteArray(Charsets.US_ASCII),
                    "ByRocha1 ".toByteArray(Charsets.US_ASCII),
                )
            ),
        )
        private val pending = ArrayDeque<ByteArray>()
        private var lastCommand: Byte? = null
        private var connected = false

        /** Total de chamadas a send(), incluindo handshake. */
        var sent = 0
            private set
        var writes = 0
            private set
        var burns = 0
            private set

        private fun page(page: Int) = pages.getOrPut(page) { ByteArray(1024) }

        override fun send(data: ByteArray) {
            sent++
            val command = ModernEnvelopeFake.commandOf(data)
            lastCommand = command
            pending.clear()
            if (command == null || !ModernEnvelopeFake.isModernFrame(data)) return
            val p = data.copyOfRange(3, data.size - 4)
            fun u16(i: Int) = (p[i].toInt() and 0xFF) or ((p[i + 1].toInt() and 0xFF) shl 8)
            when (command) {
                'p'.code.toByte() -> {
                    val content = page(p[1].toInt() and 0xFF).copyOfRange(u16(2), u16(2) + u16(4))
                    pending += ModernEnvelopeFake.framedResponse(content)
                }
                'M'.code.toByte() -> {
                    writes++
                    val length = u16(4)
                    p.copyInto(page(p[1].toInt() and 0xFF), u16(2), 6, 6 + length)
                    pending += rc(0x00)
                }
                'B'.code.toByte() -> {
                    burns++
                    pending += rc(0x04)
                }
                else -> pending += ModernEnvelopeFake.framedResponse(nextHandshake(command))
            }
        }

        private fun rc(code: Int): List<ByteArray> = listOf(byteArrayOf(0x00, 0x01), byteArrayOf(code.toByte()), ByteArray(4))

        private fun nextHandshake(command: Byte): ByteArray = handshake[command]?.removeFirstOrNull() ?: ByteArray(0)

        override fun receive(size: Int): ByteArray {
            pending.removeFirstOrNull()?.let { return it }
            val command = lastCommand ?: error("receive() sem send()")
            return if (command == 'Q'.code.toByte() || command == 'S'.code.toByte()) nextHandshake(command) else ByteArray(0)
        }

        override suspend fun connect() {
            connected = true
        }

        override fun disconnect() {
            connected = false
        }

        override fun isConnected(): Boolean = connected
        override fun getConnectionInfo(): String = "tcp:10.0.0.5:5555"
        override fun supportsModernProtocol(): Boolean = false
        override fun supportsModernProtocolFallback(): Boolean = false
        override fun prefersLegacyProtocol(): Boolean = true
        override fun legacyFirmwareHandshakeAttempts(): Int = 2
        override fun setOnConnectionStateChanged(callback: (Boolean) -> Unit) = Unit
        override fun setOnError(callback: (String) -> Unit) = Unit
        override fun clearInputBuffer() = Unit
    }
}
