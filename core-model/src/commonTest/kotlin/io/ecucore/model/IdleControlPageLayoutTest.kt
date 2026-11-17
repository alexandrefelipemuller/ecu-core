package io.ecucore.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Layout da marcha lenta na página 6 do Speeduino 202008+ (conferido com speeduino_202008/202402/202501.ini):
 *
 *   idleFreq 47 (x2 Hz) · iacPWMrun 60 bit 1 · iacCLValues 64..73 (x10 rpm) · iacBins 94..103 (°C+40)
 *   iacAlgorithm 116 [0:2] · iacStepTime 116 [3:5] · iacChannels 116 bit 6 · iacPWMdir 116 bit 7
 *   iacFastTemp 117 (°C+40) · iacStepHome 118 · iacStepHyster 119
 *
 * Regressão: o código lia a página 4 (byte 60 = dfcoRPM) e a 7 (boost/VVT) com esses offsets.
 */
class IdleControlPageLayoutTest {
    private val ownedOffsets: Set<Int> =
        setOf(47, 60, 116, 117, 118, 119) + (64..73) + (94..103)

    private fun page(fill: Int = 0x00) = ByteArray(IdleControlSettings.PAGE_LENGTH) { fill.toByte() }

    private val curve = IdleTargetCurve(
        tempsC = listOf(-20, 0, 15, 30, 45, 60, 75, 85, 95, 110),
        rpm = listOf(1400, 1300, 1200, 1100, 1000, 950, 900, 880, 860, 840),
    )

    private val settings = IdleControlSettings(
        controlMode = IdleControlMode.PWM_CLOSED_LOOP,
        idleTargetRpm = 900,
        fastIdleTempC = 55,
        pwmChannels = 2,
        idleValveFrequencyHz = 150,
        pwmDirectionReverse = true,
        runBeforeStart = true,
        stepTimeMs = 4,
        stepHomeSteps = 120,
        stepMinimumSteps = 5,
        idleTargetCurve = curve,
    )

    @Test fun theIdleFieldsLandOnTheIniOffsetsOfPage6() {
        assertEquals(6, IdleControlSettings.PAGE_NUMBER)
        assertEquals(128, IdleControlSettings.PAGE_LENGTH)
        val out = settings.applyToPage(page())
        assertEquals(75, out[47].toInt() and 0xFF)                       // 150 Hz / 2
        assertEquals(0x02, out[60].toInt() and 0x02)                     // iacPWMrun
        assertEquals(140, out[64].toInt() and 0xFF)                      // 1400 rpm / 10
        assertEquals(84, out[73].toInt() and 0xFF)
        assertEquals(20, out[94].toInt() and 0xFF)                       // -20 °C + 40
        assertEquals(150, out[103].toInt() and 0xFF)                     // 110 °C + 40
        val algo = out[116].toInt() and 0xFF
        assertEquals(3, algo and 0x07)                                   // PWM closed loop
        assertEquals(4, (algo shr 3) and 0x07)                           // step time
        assertEquals(0x40, algo and 0x40)                                // 2 canais
        assertEquals(0x80, algo and 0x80)                                // sentido inverso
        assertEquals(95, out[117].toInt() and 0xFF)                      // 55 °C + 40
        assertEquals(120, out[118].toInt() and 0xFF)
        assertEquals(5, out[119].toInt() and 0xFF)
    }

    @Test fun nothingOutsideTheIdleFieldsIsTouched() {
        val before = ByteArray(IdleControlSettings.PAGE_LENGTH) { (it * 7 + 3).toByte() }
        val after = settings.applyToPage(before)
        val changed = before.indices.filter { before[it] != after[it] }
        assertTrue(ownedOffsets.containsAll(changed), "bytes fora da marcha lenta mudaram: ${changed - ownedOffsets}")
        // em especial: o byte 60 só pode mudar o bit 1 (o resto é do dfcoRPM na página 4 / outro campo na 6)
        assertEquals(before[60].toInt() and 0xFD, after[60].toInt() and 0xFD)
    }

    @Test fun readingWhatWasWrittenGivesTheSameSettings() {
        val read = IdleControlSettings.fromPage(settings.applyToPage(page()))
        assertEquals(settings.controlMode, read.controlMode)
        assertEquals(settings.fastIdleTempC, read.fastIdleTempC)
        assertEquals(settings.pwmChannels, read.pwmChannels)
        assertEquals(settings.idleValveFrequencyHz, read.idleValveFrequencyHz)
        assertEquals(settings.pwmDirectionReverse, read.pwmDirectionReverse)
        assertEquals(settings.runBeforeStart, read.runBeforeStart)
        assertEquals(settings.stepTimeMs, read.stepTimeMs)
        assertEquals(settings.stepHomeSteps, read.stepHomeSteps)
        assertEquals(settings.stepMinimumSteps, read.stepMinimumSteps)
        assertEquals(curve, read.idleTargetCurve)
        assertEquals(1040, read.idleTargetRpm) // média da curva em dezenas de rpm (104 x 10)
    }

    @Test fun clearingTheRunBeforeStartBitKeepsTheRestOfByte60() {
        val before = page(0xFF)
        val out = settings.copy(runBeforeStart = false).applyToPage(before)
        assertEquals(0xFD, out[60].toInt() and 0xFF)
    }

    @Suppress("DEPRECATION")
    @Test fun theOldPageSpecificNamesNowWorkOnPage6() {
        val data = page()
        assertEquals(IdleControlSettings.PAGE_NUMBER, IdleControlSettings.TARGET_PAGE_NUMBER)
        assertEquals(IdleControlSettings.PAGE_LENGTH, IdleControlSettings.TARGET_PAGE_LENGTH)
        assertEquals(settings.applyToPage(data).toList(), settings.applyToPage4(data).toList())
        assertEquals(curve, IdleControlSettings.readTargetCurveFromPage7(settings.applyToPage(data)))
    }
}
