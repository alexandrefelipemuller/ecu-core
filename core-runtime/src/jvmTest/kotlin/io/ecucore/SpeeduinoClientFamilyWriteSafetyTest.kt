package io.ecucore

import io.ecucore.model.AfrTable
import io.ecucore.model.IgnitionTable
import io.ecucore.model.ValidationException
import io.ecucore.model.VeTable
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Paridade de proteção das escritas de VE/Ignição/AFR de MS2, MegaSpeed, MS3 e rusEFI com o
 * Speeduino (docs/HARA.md): validação antes de qualquer envio, confirmação de mudança grande
 * depois da validação, read-back antes do burn e rollback da RAM quando não confirma.
 */
class SpeeduinoClientFamilyWriteSafetyTest {

    private val families = listOf(
        ClientFakeEcu.Kind.MS3,
        ClientFakeEcu.Kind.MS2,
        ClientFakeEcu.Kind.MEGASPEED,
        ClientFakeEcu.Kind.RUSEFI,
    )

    private suspend fun connect(kind: ClientFakeEcu.Kind): Pair<SpeeduinoClient, ClientFakeEcu> {
        val ecu = ClientFakeEcu(kind)
        val client = SpeeduinoClient(ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        client.connect()
        return client to ecu
    }

    /** A ECU confirma ('ok') a escrita de tabela mas não guarda nada. */
    private fun ClientFakeEcu.dropTableWrites() {
        onFrame = { cmd, _ ->
            if (cmd == 'w' || cmd == 'C') ModernEnvelopeFake.framedResponse(ByteArray(0)) else null
        }
    }

    private fun afr(raw: Int, n: Int) = AfrTable(
        rpmBins = (1..n).map { it * 500 },
        loadBins = (1..n).map { it * 10 },
        values = List(n) { List(n) { raw } },
    )

    @Test
    fun `escrita que a ECU nao persiste e detectada, revertida e sem burn`() = runBlocking {
        for (kind in families) {
            val (c, ecu) = connect(kind)
            val anomalies = mutableListOf<PageWriteAnomaly>()
            c.pageWriteAnomalyListener = { anomalies += it }
            ecu.dropTableWrites()

            val error = assertFailsWith<PageWriteVerificationException>(kind.name) {
                c.writeVeTable(VeTable.createDefault(), 1)
            }

            assertTrue(error.rolledBack, "${kind.name}: a RAM nunca mudou, o rollback deve confirmar")
            assertTrue(ecu.burns.isEmpty(), "${kind.name}: sem confirmação não há burn")
            assertTrue(anomalies.isNotEmpty() && anomalies.none { it.recovered }, "${kind.name}: anomalia na telemetria")
        }
    }

    @Test
    fun `AFR perigosamente pobre e rejeitado antes de enviar qualquer coisa`() = runBlocking {
        for (kind in families) {
            val (c, ecu) = connect(kind)
            val n = if (kind == ClientFakeEcu.Kind.RUSEFI || kind == ClientFakeEcu.Kind.MS3) 16 else 12
            assertFailsWith<ValidationException>(kind.name) { c.writeAfrTable(afr(raw = 190, n = n)) }
            assertTrue(ecu.writes.isEmpty(), "${kind.name}: nada pode ser gravado")
            assertTrue(ecu.burns.isEmpty(), kind.name)
        }
    }

    @Test
    fun `guard roda depois da validacao`() = runBlocking {
        for (kind in families) {
            val (c, ecu) = connect(kind)
            var asked = false
            c.tableChangeGuard = { asked = true; false }
            val invalid = VeTable.createDefault().let { it.copy(values = List(16) { List(16) { -40000 } }) }

            assertFailsWith<ValidationException>(kind.name) { c.writeVeTable(invalid, 1) }

            assertEquals(false, asked, "${kind.name}: não se pergunta por uma tabela que será rejeitada")
            assertTrue(ecu.writes.isEmpty(), kind.name)
        }
    }

    @Test
    fun `guard recusa mudanca grande sem enviar nada`() = runBlocking {
        for (kind in families) {
            val (c, ecu) = connect(kind)
            c.tableChangeGuard = { false }

            assertFailsWith<TableChangeRejectedException>(kind.name) { c.writeVeTable(VeTable.createDefault(), 1) }

            assertTrue(ecu.writes.isEmpty(), "${kind.name}: nada pode ser gravado")
            assertTrue(ecu.burns.isEmpty(), kind.name)
        }
    }

    @Test
    fun `gravacao normal continua funcionando e faz burn`() = runBlocking {
        for (kind in families) {
            val (c, ecu) = connect(kind)
            c.writeVeTable(VeTable.createDefault(), 1)
            assertTrue(ecu.writes.isNotEmpty(), kind.name)
            assertTrue(ecu.burns.isNotEmpty(), kind.name)
        }
    }

    @Test
    fun `ignicao tambem e verificada nas familias MS`() = runBlocking {
        for (kind in listOf(ClientFakeEcu.Kind.MS3, ClientFakeEcu.Kind.MS2, ClientFakeEcu.Kind.MEGASPEED)) {
            val (c, ecu) = connect(kind)
            ecu.dropTableWrites()
            val n = if (kind == ClientFakeEcu.Kind.MS3) 16 else 12
            val ign = IgnitionTable(
                rpmBins = (1..n).map { it * 500 },
                loadBins = (1..n).map { it * 10 },
                values = List(n) { List(n) { 20 } },
            )

            assertFailsWith<PageWriteVerificationException>(kind.name) { c.writeIgnitionTable(ign, 1) }

            assertTrue(ecu.burns.isEmpty(), kind.name)
        }
    }

    @Test
    fun `escritas com layout de pagina do Speeduino sao recusadas em MS e rusEFI`() = runBlocking {
        for (kind in families) {
            val (c, ecu) = connect(kind)
            val before = ecu.writes.size
            assertFailsWith<UnsupportedOperationException>(kind.name) {
                c.writeTpsCalibration(io.ecucore.model.TpsCalibration(10, 240), burn = true)
            }
            assertFailsWith<UnsupportedOperationException>(kind.name) {
                c.writePressureCalibration(io.ecucore.model.PressureCalibration(10, 260, 10, 260, 0, 0), burn = true)
            }
            assertFailsWith<UnsupportedOperationException>(kind.name) {
                c.writeSecondarySerialConfig(
                    io.ecucore.model.SecondarySerialConfig(true, io.ecucore.model.SecondarySerialProtocol.fromRaw(0), 0),
                    burn = true,
                )
            }
            assertEquals(before, ecu.writes.size, "${kind.name}: nada pode ser gravado")
            assertTrue(ecu.burns.isEmpty(), kind.name)
        }
    }

    @Test
    fun `constantes do motor MS sao conferidas por read-back so nos bytes alterados`() = runBlocking {
        for (kind in listOf(ClientFakeEcu.Kind.MS3, ClientFakeEcu.Kind.MS2, ClientFakeEcu.Kind.MEGASPEED)) {
            val (c, ecu) = connect(kind)
            val constants = c.readEngineConstants()

            // sem alteração: nada a conferir, a gravação segue normalmente
            c.writeEngineConstants(constants)
            assertTrue(ecu.burns.isNotEmpty(), "${kind.name}: gravação normal faz burn")
            ecu.burns.clear()

            // com alteração e uma ECU que não persiste: detecta, reverte e não faz burn
            ecu.dropTableWrites()
            val changed = constants.copy(reqFuel = constants.reqFuel + 3.0f)
            val error = assertFailsWith<PageWriteVerificationException>(kind.name) { c.writeEngineConstants(changed) }
            assertTrue(error.rolledBack, kind.name)
            assertTrue(ecu.burns.isEmpty(), "${kind.name}: sem confirmação não há burn")
        }
    }
}
