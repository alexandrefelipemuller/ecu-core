package io.ecucore.model

/**
 * Simplified idle control settings for Speeduino (firmware 202008+).
 *
 * Todos os campos moram na **página 6** do .ini (128 bytes): `idleFreq` 47, `iacPWMrun` 60 (bit 1),
 * `iacCLValues` 64, `iacBins` 94 e `iacAlgorithm`/`iacStepTime`/`iacChannels`/`iacPWMdir` 116, `iacFastTemp` 117,
 * `iacStepHome` 118, `iacStepHyster` 119. Antes da correção o código lia a página 4 (gatilho: o byte 60 é o
 * `dfcoRPM`) e a 7 (boost/VVT) com esses mesmos offsets; no 201609 o layout é outro e a tela não se aplica.
 *
 * This intentionally exposes only the most useful basic fields:
 * control mode and the small set of PWM/Stepper knobs that are commonly
 * needed to get idle moving.
 */
data class IdleControlSettings(
    val controlMode: IdleControlMode = IdleControlMode.NONE,
    val idleTargetRpm: Int = 900,
    val fastIdleTempC: Int = 40,
    val pwmChannels: Int = 1,
    val idleValveFrequencyHz: Int = 120,
    val pwmDirectionReverse: Boolean = false,
    val runBeforeStart: Boolean = false,
    val stepTimeMs: Int = 3,
    val stepHomeSteps: Int = 100,
    val stepMinimumSteps: Int = 3,
    /**
     * Curva de RPM alvo da marcha lenta por temperatura do motor (`iacCLValues` x `iacBins`).
     * `null` = curva não lida; nesse caso a gravação NÃO toca nela (ao contrário de [idleTargetRpm]).
     */
    val idleTargetCurve: IdleTargetCurve? = null,
) {
    val isPwmMode: Boolean
        get() = controlMode == IdleControlMode.ON_OFF ||
            controlMode == IdleControlMode.PWM_OPEN_LOOP ||
            controlMode == IdleControlMode.PWM_CLOSED_LOOP ||
            controlMode == IdleControlMode.PWM_CLOSED_PLUS_OPEN_LOOP

    val isStepperMode: Boolean
        get() = controlMode == IdleControlMode.STEPPER_OPEN_LOOP ||
            controlMode == IdleControlMode.STEPPER_CLOSED_LOOP ||
            controlMode == IdleControlMode.STEPPER_CLOSED_PLUS_OPEN_LOOP

    companion object {
        const val PAGE_NUMBER: Int = 6
        const val PAGE_LENGTH: Int = 128

        @Deprecated("O RPM alvo e a curva estão na mesma página dos demais campos: use PAGE_NUMBER (6).", ReplaceWith("PAGE_NUMBER"))
        const val TARGET_PAGE_NUMBER: Int = PAGE_NUMBER

        @Deprecated("Use PAGE_LENGTH (128).", ReplaceWith("PAGE_LENGTH"))
        const val TARGET_PAGE_LENGTH: Int = PAGE_LENGTH

        /** Lê tudo da página de marcha lenta: modo, PWM/passo, RPM alvo (média) e a curva. */
        fun fromPage(data: ByteArray, layout: IdleControlLayout = IdleControlLayout.DEFAULT): IdleControlSettings {
            require(data.size >= layout.pageLength) { "Page ${layout.page} deve ter ${layout.pageLength} bytes" }

            val algorithmByte = data[layout.algorithm].toInt() and 0xFF
            val controlMode = IdleControlMode.fromRaw(algorithmByte and 0x07)
            val stepTime = ((algorithmByte shr 3) and 0x07).coerceIn(1, 6)
            val channels = if ((algorithmByte and 0x40) != 0) 2 else 1
            val reverse = (algorithmByte and 0x80) != 0

            val fastTempRaw = data[layout.fastTemp].toInt() and 0xFF
            val stepHome = data[layout.stepHome].toInt() and 0xFF
            val stepHyster = data[layout.stepHyster].toInt() and 0xFF
            val pwmRun = layout.pwmRun?.let { (data[it].toInt() and 0x02) != 0 } ?: false
            val idleFreqRaw = data[layout.frequency].toInt() and 0xFF

            return IdleControlSettings(
                controlMode = controlMode,
                idleTargetRpm = readTargetRpm(data, layout),
                idleTargetCurve = readTargetCurve(data, layout),
                fastIdleTempC = fastTempRaw - 40,
                pwmChannels = channels,
                idleValveFrequencyHz = idleFreqRaw * 2,
                pwmDirectionReverse = reverse,
                runBeforeStart = pwmRun,
                stepTimeMs = stepTime,
                stepHomeSteps = stepHome,
                stepMinimumSteps = stepHyster,
            )
        }

        @Deprecated("Lê a página 6 (não a 4).", ReplaceWith("fromPage(data)"))
        fun fromPage4(data: ByteArray): IdleControlSettings = fromPage(data)

        /** Lê a curva completa (10 pontos) da página de marcha lenta. */
        fun readTargetCurve(data: ByteArray, layout: IdleControlLayout = IdleControlLayout.DEFAULT): IdleTargetCurve {
            require(data.size >= layout.pageLength) { "Page ${layout.page} deve ter ${layout.pageLength} bytes" }
            return IdleTargetCurve(
                tempsC = (0 until IdleControlLayout.CURVE_POINTS).map { (data[layout.bins + it].toInt() and 0xFF) - 40 },
                rpm = (0 until IdleControlLayout.CURVE_POINTS).map { (data[layout.clValues + it].toInt() and 0xFF) * 10 },
            )
        }

        @Deprecated("Lê a página 6 (não a 7).", ReplaceWith("readTargetCurve(data)"))
        fun readTargetCurveFromPage7(data: ByteArray): IdleTargetCurve = readTargetCurve(data)

        fun readTargetRpm(data: ByteArray, layout: IdleControlLayout = IdleControlLayout.DEFAULT): Int {
            require(data.size >= layout.pageLength) { "Page ${layout.page} deve ter ${layout.pageLength} bytes" }

            val rawValues = (0 until IdleControlLayout.CURVE_POINTS).map { index ->
                data[layout.clValues + index].toInt() and 0xFF
            }
            val averageRaw = rawValues.average().toInt()
            return (averageRaw * 10).coerceIn(0, 2550)
        }

        @Deprecated("Lê a página 6 (não a 7).", ReplaceWith("readTargetRpm(data)"))
        fun readTargetRpmFromPage7(data: ByteArray): Int = readTargetRpm(data)
    }

    /** Aplica TODOS os campos (modo, PWM/passo e o RPM alvo/curva) à página de marcha lenta e devolve uma cópia. */
    fun applyToPage(original: ByteArray, layout: IdleControlLayout = IdleControlLayout.DEFAULT): ByteArray {
        require(original.size >= layout.pageLength) { "Page ${layout.page} deve ter ${layout.pageLength} bytes" }
        return applyTargetToPage(applyBasicToPage(original, layout), layout)
    }

    @Deprecated("Grava na página 6 (não na 4) e já inclui o RPM alvo.", ReplaceWith("applyToPage(original)"))
    fun applyToPage4(original: ByteArray): ByteArray = applyToPage(original)

    private fun applyBasicToPage(original: ByteArray, layout: IdleControlLayout): ByteArray {
        val data = original.copyOf()
        var algorithmByte = data[layout.algorithm].toInt() and 0xFF
        algorithmByte = (algorithmByte and 0xF8) or (controlMode.rawValue and 0x07)
        algorithmByte = (algorithmByte and 0xC7) or ((stepTimeMs.coerceIn(1, 6) and 0x07) shl 3)
        algorithmByte = if (pwmChannels >= 2) algorithmByte or 0x40 else algorithmByte and 0xBF
        algorithmByte = if (pwmDirectionReverse) algorithmByte or 0x80 else algorithmByte and 0x7F
        data[layout.algorithm] = algorithmByte.toByte()

        data[layout.fastTemp] = (fastIdleTempC + 40).coerceIn(0, 255).toByte()
        data[layout.frequency] = (idleValveFrequencyHz.coerceAtLeast(0) / 2).coerceIn(0, 255).toByte()

        // Firmware sem iacPWMrun (202008): o bit não existe, então não se grava nada.
        layout.pwmRun?.let { offset ->
            val pwmRunByte = data[offset].toInt() and 0xFF
            data[offset] = (if (runBeforeStart) pwmRunByte or 0x02 else pwmRunByte and 0xFD).toByte()
        }

        data[layout.stepHome] = stepHomeSteps.coerceIn(0, 255).toByte()
        data[layout.stepHyster] = stepMinimumSteps.coerceIn(0, 255).toByte()

        return data
    }

    @Deprecated("Grava na página 6 (não na 7).", ReplaceWith("applyToPage(original)"))
    fun applyTargetRpmToPage7(original: ByteArray): ByteArray = applyTargetToPage(original, IdleControlLayout.DEFAULT)

    private fun applyTargetToPage(original: ByteArray, layout: IdleControlLayout): ByteArray {
        require(original.size >= layout.pageLength) { "Page ${layout.page} deve ter ${layout.pageLength} bytes" }

        val data = original.copyOf()
        val curve = idleTargetCurve
        if (curve != null) {
            // Curva conhecida: grava ponto a ponto, preservando a forma que o usuário montou.
            for (index in 0 until IdleControlLayout.CURVE_POINTS) {
                data[layout.clValues + index] = (curve.rpm[index].coerceIn(0, 2550) / 10).toByte()
                data[layout.bins + index] = (curve.tempsC[index] + 40).coerceIn(0, 255).toByte()
            }
            return data
        }
        val rawValue = (idleTargetRpm.coerceIn(0, 2550) / 10).toByte()
        for (index in 0 until IdleControlLayout.CURVE_POINTS) {
            data[layout.clValues + index] = rawValue
        }
        return data
    }
}

/** Curva RPM alvo × temperatura (10 pontos, temperaturas crescentes em °C). */
data class IdleTargetCurve(val tempsC: List<Int>, val rpm: List<Int>) {
    init {
        require(tempsC.size == rpm.size) { "tempsC e rpm devem ter o mesmo tamanho" }
        require(tempsC.size == 10) { "A curva de marcha lenta do Speeduino tem 10 pontos" }
    }

    /** Média arredondada — mantém compatibilidade com o campo escalar `idleTargetRpm`. */
    val averageRpm: Int get() = rpm.average().toInt()
}

enum class IdleControlMode(val rawValue: Int) {
    NONE(0),
    ON_OFF(1),
    PWM_OPEN_LOOP(2),
    PWM_CLOSED_LOOP(3),
    STEPPER_OPEN_LOOP(4),
    STEPPER_CLOSED_LOOP(5),
    PWM_CLOSED_PLUS_OPEN_LOOP(6),
    STEPPER_CLOSED_PLUS_OPEN_LOOP(7);

    companion object {
        fun fromRaw(value: Int): IdleControlMode {
            return values().firstOrNull { it.rawValue == value } ?: NONE
        }
    }
}
