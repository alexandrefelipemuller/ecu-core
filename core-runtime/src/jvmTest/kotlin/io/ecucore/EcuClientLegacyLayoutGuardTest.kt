package io.ecucore

import io.ecucore.model.DwellTable
import io.ecucore.model.PressureCalibration
import io.ecucore.model.SecondarySerialConfig
import io.ecucore.model.SecondarySerialProtocol
import io.ecucore.model.TpsCalibration
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Speeduino 201609: a página 1 é a tabela VE (as constantes do motor, calibrações, trigger e serial secundária
 * têm outro layout, ver speeduino201609.ini). Nenhuma dessas telas pode ler a página 1 como se fosse
 * "Engine Constants" nem gravar nela. Já a tabela de dwell só existe do 202201 em diante (no 202008 a página 12 é a WMI).
 */
class EcuClientLegacyLayoutGuardTest {
    private suspend fun connected(kind: ClientFakeEcu.Kind): Pair<EcuClient, ClientFakeEcu> {
        val ecu = ClientFakeEcu(kind)
        val c = EcuClient(connection = ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        c.connect()
        return c to ecu
    }

    @Test fun firmware201609NeverTouchesPage1AsIfItWereEngineConstants() = runBlocking {
        // Instâncias válidas vêm de uma ECU moderna; o que se testa é o firmware 201609.
        val (modern, _) = connected(ClientFakeEcu.Kind.SPEEDUINO_2025)
        val engineConstants = modern.readEngineConstants()
        val trigger = modern.readTriggerSettings()
        val (c, ecu) = connected(ClientFakeEcu.Kind.SPEEDUINO_LEGACY)
        val veBytes = ByteArray(288) { (it % 251).toByte() }
        ecu.load(1, 0, veBytes)
        ecu.writes.clear()

        assertFailsWith<UnsupportedOperationException> { c.readEngineConstants() }
        assertFailsWith<UnsupportedOperationException> { c.writeEngineConstants(engineConstants) }
        assertFailsWith<UnsupportedOperationException> { c.readPressureCalibration() }
        assertFailsWith<UnsupportedOperationException> { c.writePressureCalibration(PressureCalibration(10, 250, 80, 110, 10, 250), burn = true) }
        assertFailsWith<UnsupportedOperationException> { c.readTpsCalibration() }
        assertFailsWith<UnsupportedOperationException> { c.writeTpsCalibration(TpsCalibration(20, 200), burn = true) }
        assertFailsWith<UnsupportedOperationException> { c.readTriggerSettings() }
        assertFailsWith<UnsupportedOperationException> { c.writeTriggerSettings(trigger, burn = true) }
        assertFailsWith<UnsupportedOperationException> { c.readSecondarySerialConfig() }
        assertFailsWith<UnsupportedOperationException> {
            c.writeSecondarySerialConfig(SecondarySerialConfig(enabled = false, protocol = SecondarySerialProtocol.fromRaw(0), protocolRaw = 0), burn = true)
        }

        assertTrue(ecu.writes.isEmpty(), "201609: nada pode ser gravado por essas telas")
        assertContentEquals(veBytes, ecu.mem(1).copyOfRange(0, 288), "a tabela VE (página 1) foi alterada")
    }

    @Test fun dwellTableNeedsFirmware202201OrNewer() = runBlocking {
        for (kind in listOf(ClientFakeEcu.Kind.SPEEDUINO_LEGACY, ClientFakeEcu.Kind.SPEEDUINO_2020)) {
            val (c, ecu) = connected(kind)
            ecu.writes.clear()
            assertFailsWith<UnsupportedOperationException>("$kind") { c.readDwellTable() }
            assertFailsWith<UnsupportedOperationException>("$kind") {
                c.writeDwellTable(DwellTable(listOf(500, 1000, 2000, 3000), listOf(30, 50, 70, 100), List(4) { List(4) { 20 } }))
            }
            assertTrue(ecu.writes.isEmpty(), "$kind: a página 12 não pode ser gravada")
        }
    }

    @Test fun modernFirmwareStillReadsEngineConstantsAndDwell() = runBlocking {
        val (c, _) = connected(ClientFakeEcu.Kind.SPEEDUINO_2023)
        c.readEngineConstants()
        c.readPressureCalibration()
        c.readDwellTable()
        Unit
    }
}
