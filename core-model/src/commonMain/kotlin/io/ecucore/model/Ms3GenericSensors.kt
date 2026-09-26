package io.ecucore.model

import kotlin.math.roundToInt

/**
 * Generic Sensor Inputs da MS3 (TunerStudio: Advanced Engine -> Generic Sensor Inputs).
 *
 * São 16 slots para ligar sensores 0-5V extras (pressão de óleo, pressão de combustível, ...).
 * A MS3 não tem campo dedicado a pressão de óleo/combustível: esses sensores entram por aqui.
 *
 * Layout conferido em megasquirt3.ini (0523.15), ms3.ini (0592.13) e ms3pro*.ini (0601.16x),
 * todos na page 2 do .ini = tabela 0x05 (pageIdentifier "\x05"), 1024 bytes, big-endian:
 *
 * | campo            | offset             | tipo                                       |
 * |------------------|--------------------|--------------------------------------------|
 * | sensorNN_source  | 662 + (N-1)        | U08 bits [0:4] (0523) / [0:5] (0592+)      |
 * | sensorNN_trans   | 678 + (N-1)        | U08 bits [0:2] (0523) / [0:3] (0592+)      |
 * | sensorNN_val0    | 694 + 2(N-1)       | S16 x0.1                                   |
 * | sensorNN_max     | 726 + 2(N-1)       | S16 x0.1                                   |
 * | sensorNN_LF      | 758 + (N-1)        | U08 10..100 (%)                            |
 * | sensorNN (outpc) | 104 + 2(N-1)       | S16 x0.1 - valor ao vivo                   |
 *
 * Nome e unidade do sensor não vão para a ECU (no TunerStudio são só rótulos locais).
 */
object Ms3GenericSensors {
    const val TABLE_ID = 0x05
    const val PAGE_SIZE = 1024
    const val SENSOR_COUNT = 16

    const val SOURCE_OFFSET = 662
    const val TRANSFORM_OFFSET = 678
    const val VALUE_LOW_OFFSET = 694
    const val VALUE_HIGH_OFFSET = 726
    const val LAG_FACTOR_OFFSET = 758
    const val LIVE_VALUE_OFFSET = 104

    /** Trecho da página que contém todos os campos dos 16 slots (662..773). */
    val CONFIG_SPAN: IntRange = SOURCE_OFFSET until LAG_FACTOR_OFFSET + SENSOR_COUNT

    const val SOURCE_OFF = 0
    const val MIN_LAG_FACTOR = 10
    const val MAX_LAG_FACTOR = 100
    private const val VALUE_SCALE = 10.0

    /**
     * 0523.x (MS3 1.4) usa source [0:4] e trans [0:2]; a partir do 0592 (MS3 1.5+) os campos
     * ganharam um bit (source [0:5], trans [0:3]) e entrou a transformação "Linear 0.5-4.5V".
     * Os bits acima continuam sem uso nas duas gerações e são preservados na gravação.
     */
    enum class FirmwareGeneration(val sourceMask: Int, val transformMask: Int) {
        MS3_1_4(sourceMask = 0x1F, transformMask = 0x07),
        MS3_1_5_PLUS(sourceMask = 0x3F, transformMask = 0x0F),
    }

    fun generationFor(signature: String): FirmwareGeneration {
        val version = SIGNATURE_VERSION.find(signature)?.groupValues?.get(1)?.toIntOrNull()
            ?: return FirmwareGeneration.MS3_1_4
        return if (version >= 592) FirmwareGeneration.MS3_1_5_PLUS else FirmwareGeneration.MS3_1_4
    }

    fun liveValueOffset(index: Int): Int {
        requireIndex(index)
        return LIVE_VALUE_OFFSET + 2 * (index - 1)
    }

    fun liveFieldName(index: Int): String {
        requireIndex(index)
        return "sensor" + index.toString().padStart(2, '0')
    }

    fun liveField(index: Int, name: String = liveFieldName(index), units: String = ""): OutputField =
        OutputField(
            name = name,
            offset = liveValueOffset(index),
            type = DataType.S16,
            scale = 0.1,
            units = units,
            byteOrder = EcuByteOrder.BIG_ENDIAN,
        )

    fun parse(page: ByteArray, generation: FirmwareGeneration): List<Ms3GenericSensorConfig> {
        require(page.size >= CONFIG_SPAN.last + 1) {
            "Página 0x05 da MS3 curta: esperado >= ${CONFIG_SPAN.last + 1} bytes, recebido ${page.size}"
        }
        return (1..SENSOR_COUNT).map { index ->
            val i = index - 1
            Ms3GenericSensorConfig(
                index = index,
                source = u8(page, SOURCE_OFFSET + i) and generation.sourceMask,
                transform = u8(page, TRANSFORM_OFFSET + i) and generation.transformMask,
                valueLow = s16be(page, VALUE_LOW_OFFSET + 2 * i) / VALUE_SCALE,
                valueHigh = s16be(page, VALUE_HIGH_OFFSET + 2 * i) / VALUE_SCALE,
                lagFactor = u8(page, LAG_FACTOR_OFFSET + i),
            )
        }
    }

    /**
     * Devolve uma cópia de [page] com o slot [config] gravado. Só mexe nos bits/bytes do slot -
     * bits acima da máscara de source/trans e o resto da página ficam intactos.
     */
    fun apply(page: ByteArray, config: Ms3GenericSensorConfig, generation: FirmwareGeneration): ByteArray {
        require(page.size >= CONFIG_SPAN.last + 1) {
            "Página 0x05 da MS3 curta: esperado >= ${CONFIG_SPAN.last + 1} bytes, recebido ${page.size}"
        }
        validate(config, generation)
        val i = config.index - 1
        val updated = page.copyOf()
        writeMasked(updated, SOURCE_OFFSET + i, config.source, generation.sourceMask)
        writeMasked(updated, TRANSFORM_OFFSET + i, config.transform, generation.transformMask)
        writeS16be(updated, VALUE_LOW_OFFSET + 2 * i, toRaw(config.valueLow))
        writeS16be(updated, VALUE_HIGH_OFFSET + 2 * i, toRaw(config.valueHigh))
        updated[LAG_FACTOR_OFFSET + i] = config.lagFactor.toByte()
        return updated
    }

    fun validate(config: Ms3GenericSensorConfig, generation: FirmwareGeneration) {
        requireIndex(config.index)
        require(config.source in 0..generation.sourceMask) {
            "Entrada ${config.source} inválida para ${generation.name}"
        }
        require(Ms3SensorTransform.fromRaw(config.transform)?.availableIn(generation) == true) {
            "Transformação ${config.transform} não suportada em ${generation.name}"
        }
        require(config.lagFactor in MIN_LAG_FACTOR..MAX_LAG_FACTOR) {
            "Filtro ${config.lagFactor}% fora de $MIN_LAG_FACTOR..$MAX_LAG_FACTOR"
        }
        toRaw(config.valueLow)
        toRaw(config.valueHigh)
    }

    /**
     * Converte a curva de um sensor linear (tensão -> valor) nos dois pontos que a MS3 grava.
     *
     * Com firmware 0592+ e sensor 0,5-4,5V usa a transformação "Linear 0.5-4.5V" direto (pontos
     * iguais aos do datasheet). Nos outros casos usa "Linear" (pontos em 0V e 5V), extrapolando a
     * reta do sensor até 0V e 5V.
     */
    fun linearCalibration(
        spec: LinearSensorSpec,
        generation: FirmwareGeneration,
    ): Triple<Ms3SensorTransform, Double, Double> {
        require(spec.voltageHigh > spec.voltageLow) { "Tensão máxima deve ser maior que a mínima" }
        if (
            generation == FirmwareGeneration.MS3_1_5_PLUS &&
            spec.voltageLow == 0.5 && spec.voltageHigh == 4.5
        ) {
            return Triple(Ms3SensorTransform.LINEAR_05_45V, spec.valueLow, spec.valueHigh)
        }
        val slope = (spec.valueHigh - spec.valueLow) / (spec.voltageHigh - spec.voltageLow)
        val at0V = spec.valueLow - slope * spec.voltageLow
        val at5V = spec.valueLow + slope * (5.0 - spec.voltageLow)
        return Triple(Ms3SensorTransform.LINEAR, roundTenth(at0V), roundTenth(at5V))
    }

    /**
     * Entradas analógicas nomeadas da placa, a partir da assinatura. Os valores são os índices
     * de ADC do firmware (iguais em todas as placas); só os rótulos mudam. CAN ADC01..24 = 8..31.
     * MS3 1.4 (0523) só enxerga até 31.
     */
    fun boardInputs(signature: String): List<Ms3AdcInput> {
        val variant = boardVariant(signature)
        val generation = generationFor(signature)
        val named = when (variant) {
            null -> listOf(
                1 to "JS5 (ADC6)", 2 to "JS4 (ADC7)", 3 to "EXT_MAP (ADC11)",
                4 to "EGO2 (ADC12)", 5 to "Spare ADC (ADC13)",
            )
            '8' -> PRO_BASE + listOf(32 to "Analog In 4", 33 to "Analog In 5")
            'E' -> PRO_BASE + listOf(32 to "Analog In 4", 33 to "Analog In 5", 34 to "Analog In 6", 35 to "Analog In 7")
            'I' -> PRO_BASE + listOf(32 to "Analog In 4")
            'M', 'U' -> PRO_BASE + listOf(
                32 to "Analog In 4", 33 to "Analog In 5", 34 to "Analog In 6",
                35 to "Analog In 7", 36 to "Analog In 8",
            )
            else -> PRO_BASE
        }
        val can = (1..24).map { (7 + it) to "CAN ADC" + it.toString().padStart(2, '0') }
        return (named + can)
            .filter { (raw, _) -> raw <= generation.sourceMask }
            .map { (raw, label) -> Ms3AdcInput(raw, label) }
    }

    /** Sufixo da assinatura MS3Pro (P, 8, E, I, M, U) ou `null` para MS3 com MS3X/placa base. */
    fun boardVariant(signature: String): Char? =
        SIGNATURE_VERSION.find(signature)?.groupValues?.get(2)?.firstOrNull()?.takeIf { !it.isWhitespace() }

    private val PRO_BASE = listOf(3 to "Analog In 3", 4 to "Analog In 1", 5 to "Analog In 2")

    private val SIGNATURE_VERSION = Regex("""MS3 Format (\d{4})\.\d{2}(\S?)""", RegexOption.IGNORE_CASE)

    private fun requireIndex(index: Int) {
        require(index in 1..SENSOR_COUNT) { "Slot de sensor genérico inválido: $index (1..$SENSOR_COUNT)" }
    }

    private fun toRaw(value: Double): Int {
        val raw = (value * VALUE_SCALE).roundToInt()
        require(raw in Short.MIN_VALUE..Short.MAX_VALUE) { "Valor $value fora do range S16 x0.1" }
        return raw
    }

    /** Meio décimo arredonda para longe do zero, pra reta extrapolada ficar simétrica (-1,25 -> -1,3). */
    private fun roundTenth(value: Double): Double {
        val scaled = kotlin.math.abs(value * VALUE_SCALE)
        return kotlin.math.sign(value) * kotlin.math.floor(scaled + 0.5) / VALUE_SCALE
    }

    private fun u8(data: ByteArray, index: Int): Int = data[index].toInt() and 0xFF

    private fun s16be(data: ByteArray, index: Int): Int =
        ((data[index].toInt() shl 8) or (data[index + 1].toInt() and 0xFF)).toShort().toInt()

    private fun writeS16be(data: ByteArray, index: Int, value: Int) {
        data[index] = ((value shr 8) and 0xFF).toByte()
        data[index + 1] = (value and 0xFF).toByte()
    }

    private fun writeMasked(data: ByteArray, index: Int, value: Int, mask: Int) {
        val preserved = (data[index].toInt() and 0xFF) and mask.inv()
        data[index] = (preserved or (value and mask)).toByte()
    }
}

data class Ms3GenericSensorConfig(
    /** Slot 1..16 (sensor01..sensor16). */
    val index: Int,
    /** Índice de ADC do firmware; 0 = Off. Ver [Ms3GenericSensors.boardInputs]. */
    val source: Int,
    /** Valor bruto de sensorNN_trans. Ver [Ms3SensorTransform]. */
    val transform: Int,
    /** Valor no ponto baixo (0V, ou 0,5V com [Ms3SensorTransform.LINEAR_05_45V]). */
    val valueLow: Double,
    /** Valor no ponto alto (5V, ou 4,5V com [Ms3SensorTransform.LINEAR_05_45V]). */
    val valueHigh: Double,
    /** Filtro: 100 = sem filtro, 10 = máximo. */
    val lagFactor: Int,
) {
    val isEnabled: Boolean get() = source != Ms3GenericSensors.SOURCE_OFF
}

enum class Ms3SensorTransform(val raw: Int, private val sinceGeneration: Ms3GenericSensors.FirmwareGeneration) {
    RAW(0, Ms3GenericSensors.FirmwareGeneration.MS3_1_4),
    LINEAR(1, Ms3GenericSensors.FirmwareGeneration.MS3_1_4),
    SAME_AS_MAP(2, Ms3GenericSensors.FirmwareGeneration.MS3_1_4),
    SAME_AS_CLT(3, Ms3GenericSensors.FirmwareGeneration.MS3_1_4),
    SAME_AS_MAT(4, Ms3GenericSensors.FirmwareGeneration.MS3_1_4),
    SAME_AS_EGO(5, Ms3GenericSensors.FirmwareGeneration.MS3_1_4),
    SAME_AS_MAF_OR_CUSTOM(6, Ms3GenericSensors.FirmwareGeneration.MS3_1_4),
    GM_CALIBRATION(7, Ms3GenericSensors.FirmwareGeneration.MS3_1_4),
    LINEAR_05_45V(9, Ms3GenericSensors.FirmwareGeneration.MS3_1_5_PLUS),
    RAW_DIV_10(10, Ms3GenericSensors.FirmwareGeneration.MS3_1_5_PLUS);

    fun availableIn(generation: Ms3GenericSensors.FirmwareGeneration): Boolean =
        generation.ordinal >= sinceGeneration.ordinal

    companion object {
        fun fromRaw(raw: Int): Ms3SensorTransform? = entries.firstOrNull { it.raw == raw }
    }
}

data class Ms3AdcInput(val source: Int, val label: String)

/** O que a tela de sensores genéricos precisa: geração do firmware, entradas da placa e os 16 slots. */
data class Ms3GenericSensorsSnapshot(
    val generation: Ms3GenericSensors.FirmwareGeneration,
    val boardInputs: List<Ms3AdcInput>,
    val sensors: List<Ms3GenericSensorConfig>,
)

/** Curva de sensor linear do datasheet: [valueLow] em [voltageLow], [valueHigh] em [voltageHigh]. */
data class LinearSensorSpec(
    val voltageLow: Double,
    val valueLow: Double,
    val voltageHigh: Double,
    val valueHigh: Double,
)
