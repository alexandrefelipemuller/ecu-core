package io.ecucore

import io.ecucore.model.IdleControlMode
import io.ecucore.model.IdleControlSettings
import io.ecucore.model.IdleTargetCurve
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Marcha lenta do Speeduino pela API do [EcuClient]: só a página 6 é lida e gravada.
 * (Antes lia/gravava as páginas 4 e 7, onde esses offsets são o `dfcoRPM` e as tabelas de boost/VVT.)
 */
class EcuClientIdlePageTest {
    private val sentinel = 0xA5.toByte()
    private val ownedOffsets = setOf(47, 60, 116, 117, 118, 119) + (64..73) + (94..103)

    private val curve = IdleTargetCurve(
        tempsC = listOf(-20, 0, 15, 30, 45, 60, 75, 85, 95, 110),
        rpm = listOf(1400, 1300, 1200, 1100, 1000, 950, 900, 880, 860, 840),
    )

    private suspend fun connected(kind: ClientFakeEcu.Kind): Pair<EcuClient, ClientFakeEcu> {
        val ecu = ClientFakeEcu(kind)
        // Tamanhos reais do firmware: gravar além do fim da página volta RANGE_ERR.
        ecu.pageSizeLimits[4] = 128
        ecu.pageSizeLimits[6] = 128
        ecu.pageSizeLimits[7] = 240
        val c = EcuClient(connection = ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        c.connect()
        return c to ecu
    }

    private fun markOtherPages(ecu: ClientFakeEcu) {
        ecu.load(4, 0, ByteArray(128) { sentinel })
        ecu.load(7, 0, ByteArray(240) { sentinel })
        ecu.load(6, 0, ByteArray(128) { (it * 3 + 1).toByte() })
    }

    @Test fun readsPage6Only() = runBlocking {
        val (c, ecu) = connected(ClientFakeEcu.Kind.SPEEDUINO_2025)
        markOtherPages(ecu)
        val page6 = IdleControlSettings(controlMode = IdleControlMode.STEPPER_OPEN_LOOP, idleTargetCurve = curve, stepHomeSteps = 90)
            .applyToPage(ByteArray(128))
        ecu.load(6, 0, page6)
        val read = c.readIdleControlSettings()
        assertEquals(IdleControlMode.STEPPER_OPEN_LOOP, read.controlMode)
        assertEquals(90, read.stepHomeSteps)
        assertEquals(curve, read.idleTargetCurve)
    }

    @Test fun writesPage6Once_andLeavesPages4And7AndTheRestOfPage6Alone() = runBlocking {
        val (c, ecu) = connected(ClientFakeEcu.Kind.SPEEDUINO_2025)
        markOtherPages(ecu)
        val page6Before = ecu.mem(6).copyOfRange(0, 128)
        val page4Before = ecu.mem(4).copyOfRange(0, 128)
        val page7Before = ecu.mem(7).copyOfRange(0, 240)
        ecu.writes.clear()

        val settings = c.readIdleControlSettings().copy(
            controlMode = IdleControlMode.PWM_CLOSED_LOOP, fastIdleTempC = 55, idleTargetCurve = curve,
        )
        c.writeIdleControlSettings(settings, burn = true)

        assertEquals(listOf(6), ecu.writes.map { it.id }.distinct(), "só a página 6 pode ser gravada")
        assertTrue(ecu.writes.all { it.offset + it.data.size <= 128 }, "escrita além do fim da página 6")
        assertContentEquals(page4Before, ecu.mem(4).copyOfRange(0, 128), "página 4 (gatilho/dfcoRPM) foi alterada")
        assertContentEquals(page7Before, ecu.mem(7).copyOfRange(0, 240), "página 7 (boost/VVT) foi alterada")
        val after = ecu.mem(6).copyOfRange(0, 128)
        val changed = page6Before.indices.filter { page6Before[it] != after[it] }
        assertTrue(ownedOffsets.containsAll(changed), "bytes fora da marcha lenta mudaram: ${changed - ownedOffsets}")
        assertEquals(3, after[116].toInt() and 0x07) // PWM closed loop gravado
        assertEquals(95, after[117].toInt() and 0xFF)
        assertTrue(ecu.burns.isNotEmpty())
    }

    @Test fun roundTripKeepsTheUsersCurveShape() = runBlocking {
        val (c, ecu) = connected(ClientFakeEcu.Kind.SPEEDUINO_2025)
        markOtherPages(ecu)
        val first = c.readIdleControlSettings().copy(idleTargetCurve = curve)
        c.writeIdleControlSettings(first, burn = false)
        val back = c.readIdleControlSettings()
        assertEquals(curve, back.idleTargetCurve) // a curva não é achatada pela média
    }

    @Test fun modernFirmwareFrom202008Works() = runBlocking {
        val (c, _) = connected(ClientFakeEcu.Kind.SPEEDUINO_2020)
        c.readIdleControlSettings() // não lança
        Unit
    }

    @Test fun firmware201609IsRefused_becauseItsLayoutIsDifferent() = runBlocking {
        val (c, ecu) = connected(ClientFakeEcu.Kind.SPEEDUINO_LEGACY)
        markOtherPages(ecu)
        ecu.writes.clear()
        assertFailsWith<UnsupportedOperationException> { c.readIdleControlSettings() }
        assertFailsWith<UnsupportedOperationException> { c.writeIdleControlSettings(IdleControlSettings(), burn = true) }
        assertTrue(ecu.writes.isEmpty(), "nada pode ser gravado num firmware com outro layout")
    }
}
