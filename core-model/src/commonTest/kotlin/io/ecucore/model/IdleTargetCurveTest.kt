package io.ecucore.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IdleTargetCurveTest {
    private fun page6(): ByteArray {
        val data = ByteArray(IdleControlSettings.PAGE_LENGTH) { 0x55 }
        val rpmRaw = intArrayOf(130, 124, 118, 112, 106, 100, 96, 92, 88, 84) // x10 rpm
        val tempRaw = intArrayOf(0, 20, 40, 60, 80, 100, 110, 120, 130, 140) // °C + 40
        for (i in 0 until 10) {
            data[64 + i] = rpmRaw[i].toByte()
            data[94 + i] = tempRaw[i].toByte()
        }
        return data
    }

    @Test fun readsTenPointsWithScaling() {
        val curve = IdleControlSettings.readTargetCurve(page6())
        assertEquals(listOf(-40, -20, 0, 20, 40, 60, 70, 80, 90, 100), curve.tempsC)
        assertEquals(listOf(1300, 1240, 1180, 1120, 1060, 1000, 960, 920, 880, 840), curve.rpm)
    }

    @Test fun writingKnownCurveKeepsItsShapeAndOtherBytes() {
        val original = page6()
        val curve = IdleControlSettings.readTargetCurve(original)
        val edited = curve.copy(rpm = curve.rpm.toMutableList().also { it[3] = 1500 })
        val out = IdleControlSettings(idleTargetCurve = edited).applyToPage(original)
        assertEquals(150, out[64 + 3].toInt() and 0xFF)
        assertEquals(130, out[64].toInt() and 0xFF) // demais pontos intactos (não achatou a curva)
        assertContentEquals(original.copyOfRange(94, 104), out.copyOfRange(94, 104))
        // 0..63 só muda onde a marcha lenta mora: 47 (frequência) e 60 (bit de rodar antes de ligar)
        for (i in 0 until 64) if (i != 47 && i != 60) assertEquals(original[i], out[i], "byte $i mudou")
        // 104..115 e 120..127 não pertencem à marcha lenta; 116..119 recebem o modo/passo (padrões do IdleControlSettings)
        assertContentEquals(original.copyOfRange(104, 116), out.copyOfRange(104, 116))
        assertContentEquals(original.copyOfRange(120, 128), out.copyOfRange(120, 128))
    }

    @Test fun curveWinsOverScalarTargetAndIsClamped() {
        val curve = IdleTargetCurve(List(10) { it * 10 }, List(10) { 9999 })
        val out = IdleControlSettings(idleTargetRpm = 700, idleTargetCurve = curve).applyToPage(page6())
        assertEquals(255, out[64].toInt() and 0xFF)
    }

    @Test fun withoutCurveLegacyScalarBehaviourRemains() {
        val out = IdleControlSettings(idleTargetRpm = 900).applyToPage(page6())
        assertEquals(90, out[64 + 5].toInt() and 0xFF)
        assertNull(IdleControlSettings().idleTargetCurve)
    }

    @Test fun averageRpmMatchesScalarCompat() {
        assertEquals(1000, IdleTargetCurve(List(10) { it }, List(10) { 1000 }).averageRpm)
    }
}
