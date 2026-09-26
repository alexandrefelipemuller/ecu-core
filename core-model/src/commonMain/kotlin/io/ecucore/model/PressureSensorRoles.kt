package io.ecucore.model

/**
 * Sensores de pressão extras (óleo, combustível) em ECUs sem campo dedicado pra eles.
 *
 * O dashboard lê "oilPressure"/"fuelPressure" por nome ([SpeeduinoOutputChannels.getField]).
 * Aqui esses nomes viram aliases ([SpeeduinoOutputChannels.setSensorRoleAliases]):
 * - MS3: alias para o Generic Sensor configurado na ECU ([Ms3GenericSensors]); a ECU já entrega
 *   o valor convertido.
 * - MS2 (MS2Extra): não existe calibração de sensor genérico no firmware - a ECU só manda o ADC
 *   cru (adc6/adc7/gpioadcN) e o TunerStudio converte no PC. O alias faz essa conversão aqui.
 */
enum class SensorRole(val fieldName: String) {
    OIL_PRESSURE("oilPressure"),
    FUEL_PRESSURE("fuelPressure"),
}

/** Entrada analógica crua da MS2Extra, lida do outpc (U16 big-endian, ADC 0..1023 = 0..5V). */
data class Ms2AdcInput(val key: String, val label: String, val offset: Int)

object Ms2AdcInputs {
    private const val ADC_FULL_SCALE = 1023.0
    private const val ADC_REFERENCE_VOLTS = 5.0

    /** Offsets do outpc em megasquirt2.ini / mspnp2.ini (MS2Extra comms342). */
    val INPUTS: List<Ms2AdcInput> = listOf(
        Ms2AdcInput("adc6", "ADC6 (JS5)", 128),
        Ms2AdcInput("adc7", "ADC7 (JS4)", 130),
    ) + (0..7).map { Ms2AdcInput("gpioadc$it", "GPIO ADC $it", 104 + 2 * it) }

    fun input(key: String): Ms2AdcInput? = INPUTS.firstOrNull { it.key == key }

    /**
     * Campo que converte o ADC cru de [input] com a curva linear [spec]:
     * `valor = valueLow + (volts - voltageLow) * inclinação`, com `volts = adc * 5 / 1023`,
     * escrito na forma `(adc + translate) * scale` do [OutputField].
     */
    fun roleField(role: SensorRole, input: Ms2AdcInput, spec: LinearSensorSpec, units: String): OutputField {
        require(spec.voltageHigh > spec.voltageLow) { "Tensão máxima deve ser maior que a mínima" }
        require(spec.valueHigh != spec.valueLow) { "Valores mínimo e máximo não podem ser iguais" }
        val slopePerVolt = (spec.valueHigh - spec.valueLow) / (spec.voltageHigh - spec.voltageLow)
        val scale = slopePerVolt * ADC_REFERENCE_VOLTS / ADC_FULL_SCALE
        val valueAtZeroAdc = spec.valueLow - slopePerVolt * spec.voltageLow
        return OutputField(
            name = role.fieldName,
            offset = input.offset,
            type = DataType.U16,
            scale = scale,
            translate = valueAtZeroAdc / scale,
            units = units,
            byteOrder = EcuByteOrder.BIG_ENDIAN,
        )
    }
}

/**
 * Curvas prontas de sensores de pressão comuns. Faixa de tensão e de pressão vêm do datasheet de
 * cada sensor - o usuário sempre pode editar (preset "personalizado").
 */
data class PressureSensorPreset(val id: String, val label: String, val spec: LinearSensorSpec, val units: String)

object PressureSensorPresets {
    val ALL: List<PressureSensorPreset> = listOf(
        PressureSensorPreset("ps10_bar", "PS-10 (0–10 bar, 0,5–4,5 V)", LinearSensorSpec(0.5, 0.0, 4.5, 10.0), "bar"),
        PressureSensorPreset("generic_100psi", "0–100 psi (0,5–4,5 V)", LinearSensorSpec(0.5, 0.0, 4.5, 100.0), "psi"),
        PressureSensorPreset("generic_150psi", "0–150 psi (0,5–4,5 V)", LinearSensorSpec(0.5, 0.0, 4.5, 150.0), "psi"),
        PressureSensorPreset("generic_10bar_0_5v", "0–10 bar (0–5 V)", LinearSensorSpec(0.0, 0.0, 5.0, 10.0), "bar"),
    )

    fun byId(id: String?): PressureSensorPreset? = ALL.firstOrNull { it.id == id }
}

/** Algo que o usuário precisa confirmar antes de gravar um Generic Sensor na MS3. */
sealed class Ms3SlotWarning {
    /** O slot que o app usava pra esse papel foi reconfigurado fora do app (ex.: TunerStudio). */
    data class SlotChangedOutsideApp(val slot: Int, val currentSource: Int) : Ms3SlotWarning()

    /** Outro slot habilitado já lê a mesma entrada física. */
    data class InputAlreadyUsed(val otherSlot: Int, val source: Int) : Ms3SlotWarning()
}

data class Ms3SlotPlan(val slot: Int, val warnings: List<Ms3SlotWarning>)

object Ms3SlotPlanner {
    /**
     * Escolhe o slot 1..16 para um papel.
     *
     * - [previousSlot]/[previousSource]: o que o app gravou da última vez pra esse papel. Se o slot
     *   ainda está como o app deixou (ou desligado), reaproveita; se alguém mudou a entrada por
     *   fora, reaproveita mas avisa.
     * - Sem slot anterior: o primeiro desligado que não esteja em [reservedSlots] (slots dos
     *   outros papéis). Nunca pega um slot habilitado por outra configuração.
     *
     * @return `null` se não há slot livre.
     */
    fun plan(
        sensors: List<Ms3GenericSensorConfig>,
        source: Int,
        previousSlot: Int?,
        previousSource: Int?,
        reservedSlots: Set<Int>,
    ): Ms3SlotPlan? {
        val bySlot = sensors.associateBy { it.index }
        val warnings = mutableListOf<Ms3SlotWarning>()
        val previous = previousSlot?.let { bySlot[it] }
        val slot = if (previous != null && previous.index !in reservedSlots) {
            if (previous.isEnabled && previous.source != previousSource) {
                warnings += Ms3SlotWarning.SlotChangedOutsideApp(previous.index, previous.source)
            }
            previous.index
        } else {
            sensors.firstOrNull { !it.isEnabled && it.index !in reservedSlots }?.index ?: return null
        }
        sensors
            .filter { it.index != slot && it.isEnabled && it.source == source }
            .forEach { warnings += Ms3SlotWarning.InputAlreadyUsed(it.index, source) }
        return Ms3SlotPlan(slot, warnings)
    }
}
