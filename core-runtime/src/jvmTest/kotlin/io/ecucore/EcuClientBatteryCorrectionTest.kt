package io.ecucore

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A correção de tensão de bateria do injetor (`brvBins` 15, `injBatRates` 21) mora na página 6 do .ini. Antes era lida
 * e gravada na página 1, sobrescrevendo tachoPin/tachoDuration/maeThresh/taeThresh/aeTime/... (offsets 16..23).
 */
class EcuClientBatteryCorrectionTest {
    private val sentinel = 0x5A.toByte()

    private suspend fun connected(): Pair<EcuClient, ClientFakeEcu> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025)
        ecu.pageSizeLimits[1] = 128
        ecu.pageSizeLimits[6] = 128
        val c = EcuClient(connection = ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        c.connect()
        return c to ecu
    }

    private fun load(ecu: ClientFakeEcu) {
        ecu.load(1, 0, ByteArray(128) { sentinel }.also { it[15] = 3 /* pinLayout válido */; it[36] = 0x40; it[37] = 0x21; it[50] = 147.toByte(); it[24] = 80; it[25] = 1 })
        val p6 = ByteArray(128) { 0x22 }
        for (i in 0..5) { p6[15 + i] = (60 + i * 20).toByte(); p6[21 + i] = (160 - i * 10).toByte() }
        ecu.load(6, 0, p6)
    }

    @Test fun readsTheBatteryTablesFromPage6() = runBlocking {
        val (c, ecu) = connected()
        load(ecu)
        val ec = c.readEngineConstants()
        assertTrue(ec.batteryCorrectionLoaded)
        assertEquals(listOf(6.0f, 8.0f, 10.0f, 12.0f, 14.0f, 16.0f), ec.batteryVoltageBins.map { kotlin.math.round(it * 10f) / 10f })
        assertEquals(listOf(160, 150, 140, 130, 120, 110), ec.injectorVoltageCorrectionRates)
    }

    @Test fun writesBatteryTablesToPage6AndLeavesPage1BytesOfOtherFieldsAlone() = runBlocking {
        val (c, ecu) = connected()
        load(ecu)
        val p1Before = ecu.mem(1).copyOfRange(0, 128)
        val p6Before = ecu.mem(6).copyOfRange(0, 128)
        val ec = c.readEngineConstants()
        ecu.writes.clear()

        c.writeEngineConstants(
            ec.copy(
                batteryVoltageBins = listOf(7f, 9f, 11f, 13f, 15f, 17f),
                injectorVoltageCorrectionRates = listOf(150, 130, 110, 100, 95, 90),
            )
        )

        val p1After = ecu.mem(1).copyOfRange(0, 128)
        val p6After = ecu.mem(6).copyOfRange(0, 128)
        for (i in 15..23) assertEquals(p1Before[i], p1After[i], "byte $i da página 1 (pinLayout/tacômetro/AE) foi alterado")
        assertEquals(setOf(1, 6), ecu.writes.map { it.id }.toSet())
        val changed6 = p6Before.indices.filter { p6Before[it] != p6After[it] }
        assertTrue((15..26).toSet().containsAll(changed6), "bytes fora de brvBins/injBatRates mudaram: ${changed6 - (15..26).toSet()}")
        assertEquals(70, p6After[15].toInt() and 0xFF)
        assertEquals(150, p6After[21].toInt() and 0xFF)
        assertEquals(listOf(150, 130, 110, 100, 95, 90), c.readEngineConstants().injectorVoltageCorrectionRates)
    }

    @Test fun doesNotTouchPage6WhenNothingChanged() = runBlocking {
        val (c, ecu) = connected()
        load(ecu)
        val ec = c.readEngineConstants()
        ecu.writes.clear()
        c.writeEngineConstants(ec)
        assertTrue(ecu.writes.none { it.id == 6 }, "página 6 não deve ser gravada sem mudança")
    }

    @Test fun doesNotOverwriteBatteryCorrectionWithDefaultsWhenItWasNeverRead() = runBlocking {
        val (c, ecu) = connected()
        load(ecu)
        val p6Before = ecu.mem(6).copyOfRange(0, 128)
        // constantes sem a correção lida da ECU (valores padrão)
        val ec = c.readEngineConstants().copy(batteryCorrectionLoaded = false)
        ecu.writes.clear()
        c.writeEngineConstants(ec)
        assertContentEquals(p6Before, ecu.mem(6).copyOfRange(0, 128), "padrões sobrescreveram a correção da ECU")
    }
}
