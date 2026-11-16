package io.ecucore

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A página 12 do Speeduino 202201+ guarda a tabela WMI (0..79), a VVT2 (80..159) e só no fim a dwell (160..183).
 * Gravar a dwell já montou a página inteira do zero e zerou WMI e VVT2.
 */
class EcuClientDwellPageTest {
    private val sentinel = 0x5A.toByte()

    private suspend fun connected(): Pair<EcuClient, ClientFakeEcu> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2023)
        ecu.pageSizeLimits[12] = 192
        val c = EcuClient(connection = ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        c.connect()
        return c to ecu
    }

    @Test fun savingTheDwellTableKeepsWmiAndVvt2() = runBlocking {
        val (c, ecu) = connected()
        ecu.load(12, 0, ByteArray(192) { sentinel }.also { for (i in 160..175) it[i] = 25 })
        val before = ecu.mem(12).copyOfRange(0, 192)
        val table = c.readDwellTable()
        ecu.writes.clear()

        c.writeDwellTable(table.copy(values = table.values.map { row -> row.map { 30 } }))

        val after = ecu.mem(12).copyOfRange(0, 192)
        assertEquals(setOf(12), ecu.writes.map { it.id }.toSet(), "só a página 12 pode ser gravada")
        assertContentEquals(before.copyOfRange(0, 160), after.copyOfRange(0, 160), "WMI/VVT2 (0..159) foram alterados")
        assertTrue((160..175).all { after[it].toInt() == 30 }, "a dwell não foi gravada")
    }
}
