package io.ecucore.model

/**
 * Canais ao vivo da MegaSpeed MS2-X (bloco de saída de 219 bytes, `[OutputChannels]` do .ini da
 * CostaMegaSpeed) e do MS2/Extra (212 bytes, mesmo layout até o offset 211) para a tela de
 * diagnóstico em tempo real.
 *
 * A lista é curada à mão (o parser de .ini não guarda bits de canais de saída, e o .ini tem ramos
 * `#if CELSIUS`), mas cada canal é conferido contra as linhas reais do .ini em teste.
 * Temperaturas chegam em °F×10 (raw); a conversão para °C é `(raw - 320) * 0.05555`, como no .ini.
 */
enum class MegaSpeedChannelGroup(val label: String) {
    ENGINE("Motor"),
    SENSORS("Sensores"),
    MIXTURE("Mistura e correções"),
    IGNITION("Ignição"),
    SYNC("Sincronismo"),
    AUX("Auxiliares"),
}

enum class MegaSpeedChannelType(val bytes: Int) { U08(1), S08(1), U16(2), S16(2), U32(4), S32(4) }

data class MegaSpeedLiveChannel(
    val name: String,
    val label: String,
    val group: MegaSpeedChannelGroup,
    val offset: Int,
    val type: MegaSpeedChannelType,
    val scale: Double,
    val translate: Double = 0.0,
    val digits: Int = 0,
    val unit: String = "",
)

data class MegaSpeedChannelReading(
    val channel: MegaSpeedLiveChannel,
    val raw: Int,
    val value: Double,
    val text: String,
)

/** Bits do byte `engine` (offset 11), nomes e posições do .ini. */
enum class MegaSpeedEngineBit(val bit: Int, val label: String) {
    READY(0, "Pronto"),
    CRANK(1, "Em partida"),
    STARTW(2, "Enriquecimento pós-partida"),
    WARMUP(3, "Aquecimento"),
    TPS_ACCEL(4, "Aceleração TPS"),
    TPS_DECEL(5, "Desaceleração TPS"),
    MAP_ACCEL(6, "Aceleração MAP"),
    MAP_DECEL(7, "Desaceleração MAP"),
}

object MegaSpeedLiveChannels {
    /**
     * Menor bloco aceito: o outpc do MS2Extra (212 bytes). Os canais da MegaSpeed além disso
     * (oil, fuel, runsecs em 212..217) só aparecem quando o bloco os contém.
     */
    const val MIN_BLOCK_SIZE = 212
    const val ENGINE_OFFSET = 11
    const val STATUS3_OFFSET = 80

    private const val TEMP_SCALE = 0.05555
    private const val TEMP_TRANSLATE = -320.0

    private fun c(
        name: String, label: String, group: MegaSpeedChannelGroup, offset: Int, type: MegaSpeedChannelType,
        scale: Double = 1.0, translate: Double = 0.0, digits: Int = 0, unit: String = "",
    ) = MegaSpeedLiveChannel(name, label, group, offset, type, scale, translate, digits, unit)

    private val E = MegaSpeedChannelGroup.ENGINE
    private val S = MegaSpeedChannelGroup.SENSORS
    private val M = MegaSpeedChannelGroup.MIXTURE
    private val I = MegaSpeedChannelGroup.IGNITION
    private val Y = MegaSpeedChannelGroup.SYNC
    private val A = MegaSpeedChannelGroup.AUX
    private val U08 = MegaSpeedChannelType.U08
    private val S08 = MegaSpeedChannelType.S08
    private val U16 = MegaSpeedChannelType.U16
    private val S16 = MegaSpeedChannelType.S16
    private val S32 = MegaSpeedChannelType.S32

    val CHANNELS: List<MegaSpeedLiveChannel> = listOf(
        c("rpm", "Rotação", E, 6, U16, unit = "RPM"),
        c("RPMdot", "Variação de rotação", E, 164, S16, scale = 10.0, unit = "rpm/s"),
        c("pulseWidth1", "Pulso do injetor 1", E, 2, U16, scale = 0.000666, digits = 2, unit = "ms"),
        c("pulseWidth2", "Pulso do injetor 2", E, 4, U16, scale = 0.000666, digits = 2, unit = "ms"),
        c("dwell", "Dwell", E, 62, U16, scale = 0.0666, digits = 2, unit = "ms"),
        c("looptime", "Tempo do laço principal", E, 82, U16, scale = 0.6667, digits = 0, unit = "us"),
        c("seconds", "Tempo ligada", E, 0, U16, unit = "s"),
        c("runsecs", "Tempo em funcionamento", E, 216, U16, unit = "s"),

        c("map", "MAP", S, 18, S16, scale = 0.1, digits = 1, unit = "kPa"),
        c("barometer", "Barométrica", S, 16, S16, scale = 0.1, digits = 1, unit = "kPa"),
        c("mat", "Temperatura do ar (MAT)", S, 20, S16, TEMP_SCALE, TEMP_TRANSLATE, 1, "°C"),
        c("coolant", "Temperatura do motor (CLT)", S, 22, S16, TEMP_SCALE, TEMP_TRANSLATE, 1, "°C"),
        c("tps", "TPS", S, 24, S16, scale = 0.1, digits = 1, unit = "%"),
        c("batteryVoltage", "Bateria", S, 26, S16, scale = 0.1, digits = 1, unit = "V"),
        c("afr1", "AFR 1", S, 28, S16, scale = 0.1, digits = 1, unit = "AFR"),
        c("afr2", "AFR 2", S, 30, S16, scale = 0.1, digits = 1, unit = "AFR"),

        c("afrtgt1", "AFR alvo 1", M, 12, U08, scale = 0.1, digits = 1, unit = "AFR"),
        c("egoCorrection1", "Correção da sonda 1", M, 34, S16, scale = 0.1, digits = 1, unit = "%"),
        c("egoCorrection2", "Correção da sonda 2", M, 36, S16, scale = 0.1, digits = 1, unit = "%"),
        c("airCorrection", "Correção do ar", M, 38, S16, scale = 0.1, digits = 1, unit = "%"),
        c("baroCorrection", "Correção barométrica", M, 46, S16, scale = 0.1, digits = 1, unit = "%"),
        c("warmupEnrich", "Enriquecimento de aquecimento", M, 40, S16, unit = "%"),
        c("accelEnrich", "Enriquecimento de aceleração", M, 42, S16, scale = 0.1, digits = 1, unit = "ms"),
        c("gammaEnrich", "Enriquecimento total", M, 48, S16, unit = "%"),
        c("veCurr1", "VE atual 1", M, 50, S16, scale = 0.1, digits = 1, unit = "%"),
        c("veCurr2", "VE atual 2", M, 52, S16, scale = 0.1, digits = 1, unit = "%"),

        c("advance", "Avanço", I, 8, S16, scale = 0.1, digits = 1, unit = "grau"),
        c("base_advance", "Avanço base", I, 184, S16, scale = 0.1, digits = 1, unit = "grau"),
        c("knock", "Detonação", I, 32, U16, scale = 0.1, digits = 1, unit = "%"),
        c("knockRetard", "Retardo por detonação", I, 71, U08, scale = 0.1, digits = 1, unit = "grau"),

        c("synccnt", "Contagem de perdas de rotação", Y, 94, U08),
        // O .ini não descreve os códigos; é o número gravado pelo firmware no ponto da perda de sync.
        c("syncreason", "Código da última perda de rotação", Y, 139, U08),
        c("timing_err", "Erro de previsão de tempo", Y, 95, S08, scale = 0.1, digits = 1, unit = "%"),
        c("deltaT", "Tempo entre pulsos (normalizado)", Y, 96, S32, unit = "us"),

        c("cl_idle_targ_rpm", "Alvo de marcha lenta", A, 170, S16, unit = "rpm"),
        c("boostduty", "Duty do booster", A, 138, U08, unit = "%"),
        c("oil", "Pressão de óleo", A, 212, S16, scale = 0.1, digits = 1, unit = "bar"),
        c("fuel", "Pressão de combustível", A, 214, S16, scale = 0.1, digits = 1, unit = "bar"),
    )

    /** null se o bloco é curto demais (não mostra valor errado em vez de nada). */
    fun decode(block: ByteArray?): List<MegaSpeedChannelReading>? {
        if (block == null || block.size < MIN_BLOCK_SIZE) return null
        return CHANNELS.filter { it.offset + it.type.bytes <= block.size }.map { ch ->
            val raw = readRaw(block, ch)
            val value = (raw + ch.translate) * ch.scale
            val number = formatFixed(value, ch.digits)
            MegaSpeedChannelReading(ch, raw, value, if (ch.unit.isEmpty()) number else "$number ${ch.unit}")
        }
    }

    fun engineBits(block: ByteArray?): Map<MegaSpeedEngineBit, Boolean>? {
        if (block == null || block.size <= ENGINE_OFFSET) return null
        val b = block[ENGINE_OFFSET].toInt() and 0xFF
        return MegaSpeedEngineBit.entries.associateWith { b and (1 shl it.bit) != 0 }
    }

    fun status3(block: ByteArray?): Int? =
        if (block == null || block.size <= STATUS3_OFFSET) null else block[STATUS3_OFFSET].toInt() and 0xFF

    private fun readRaw(d: ByteArray, ch: MegaSpeedLiveChannel): Int {
        fun u8(i: Int) = d[i].toInt() and 0xFF
        return when (ch.type) {
            MegaSpeedChannelType.U08 -> u8(ch.offset)
            MegaSpeedChannelType.S08 -> d[ch.offset].toInt()
            MegaSpeedChannelType.U16 -> (u8(ch.offset) shl 8) or u8(ch.offset + 1)
            MegaSpeedChannelType.S16 -> ((u8(ch.offset) shl 8) or u8(ch.offset + 1)).let { if (it >= 0x8000) it - 0x10000 else it }
            MegaSpeedChannelType.U32, MegaSpeedChannelType.S32 ->
                (u8(ch.offset) shl 24) or (u8(ch.offset + 1) shl 16) or (u8(ch.offset + 2) shl 8) or u8(ch.offset + 3)
        }
    }
}
