package io.ecucore.telemetry

import io.ecucore.SpeeduinoLiveData
import io.ecucore.shared.JvmSynchronized
import io.ecucore.shared.MonotonicClock
import kotlin.math.exp
import kotlin.math.pow

/**
 * Campos de [SpeeduinoLiveData] elegíveis para extrapolação preditiva ("dead reckoning").
 */
enum class PredictedField {
    RPM,
    MAP_PRESSURE,
    TPS,
    SPEED_KPH,
    COOLANT_TEMP
}

/**
 * Limites de segurança por campo para a extrapolação.
 *
 * @param maxVelocityPerSecond taxa máxima plausível de variação do campo (unidade do campo / s).
 * @param velocitySmoothingHalfLifeMs meia-vida (ms) do filtro passa-baixa (EMA) aplicado sobre a
 *   velocidade estimada: em vez de recalcular a inclinação "crua" entre as duas últimas amostras
 *   reais a cada atualização (sensível a ruído/quantização, produz rampas "em degrau/onda
 *   quadrada"), a nova inclinação instantânea é misturada com a anterior usando essa meia-vida —
 *   dt pequeno entre amostras pesa pouco a inclinação nova (suaviza ruído de leituras próximas),
 *   dt grande pesa quase tudo nela (não trava atrás de uma tendência antiga).
 * @param maxLookaheadNs além desse tempo sem uma atualização real do campo, a extrapolação passa a
 *   decair exponencialmente em vez de continuar linear, evitando que o valor "fuja" se o link travar.
 */
data class FieldPredictionConfig(
    val maxVelocityPerSecond: Double,
    val velocitySmoothingHalfLifeMs: Long,
    val maxLookaheadNs: Long
)

object DefaultPredictionConfigs {
    val RPM = FieldPredictionConfig(
        maxVelocityPerSecond = 8000.0,
        velocitySmoothingHalfLifeMs = 180L,
        maxLookaheadNs = 350_000_000L
    )
    val MAP_PRESSURE = FieldPredictionConfig(
        maxVelocityPerSecond = 120.0,
        velocitySmoothingHalfLifeMs = 200L,
        maxLookaheadNs = 350_000_000L
    )
    val TPS = FieldPredictionConfig(
        maxVelocityPerSecond = 400.0,
        velocitySmoothingHalfLifeMs = 120L,
        maxLookaheadNs = 350_000_000L
    )
    val SPEED_KPH = FieldPredictionConfig(
        maxVelocityPerSecond = 40.0,
        velocitySmoothingHalfLifeMs = 300L,
        maxLookaheadNs = 500_000_000L
    )
    val COOLANT_TEMP = FieldPredictionConfig(
        maxVelocityPerSecond = 2.0,
        velocitySmoothingHalfLifeMs = 1_000L,
        maxLookaheadNs = 2_000_000_000L
    )

    fun defaults(): Map<PredictedField, FieldPredictionConfig> = mapOf(
        PredictedField.RPM to RPM,
        PredictedField.MAP_PRESSURE to MAP_PRESSURE,
        PredictedField.TPS to TPS,
        PredictedField.SPEED_KPH to SPEED_KPH,
        PredictedField.COOLANT_TEMP to COOLANT_TEMP
    )

    /**
     * Aplica [aggressiveness] aos defaults e retorna o mapa de configs pronto para
     * [LiveDataPredictor]. Cada app decide seu próprio nível — por exemplo um app focado em OBD2
     * lento pode preferir [PredictionAggressiveness.AGGRESSIVE] para mascarar mais a cadência baixa,
     * enquanto um app de bancada/tuning que prioriza fidelidade ao dado real pode preferir
     * [PredictionAggressiveness.LIGHT].
     */
    fun forAggressiveness(aggressiveness: PredictionAggressiveness): Map<PredictedField, FieldPredictionConfig> =
        defaults().mapValues { (_, config) -> aggressiveness.scale(config) }
}

/**
 * Fator de escala aplicado sobre [DefaultPredictionConfigs] para tornar a extrapolação mais ou
 * menos "ousada", sem precisar redefinir os limites de cada campo manualmente.
 *
 * - `velocityScale` maior = a extrapolação confia mais na tendência observada e se move mais rápido
 *   para acompanhar mudanças bruscas (bom para mascarar cadência muito baixa, como OBD2 a 1-2Hz).
 * - `velocitySmoothingScale` maior = a inclinação estimada reage mais devagar a leituras novas
 *   (mais filtrada/estável, menos "onda quadrada" em dado ruidoso); menor = reage mais rápido a
 *   mudanças de tendência, ao custo de mais sensibilidade a ruído.
 * - `lookaheadScale` maior = continua extrapolando por mais tempo antes de decair para o último
 *   valor real quando a próxima amostra atrasa.
 */
data class PredictionAggressiveness(
    val velocityScale: Double,
    val velocitySmoothingScale: Double,
    val lookaheadScale: Double
) {
    internal fun scale(config: FieldPredictionConfig): FieldPredictionConfig = config.copy(
        maxVelocityPerSecond = config.maxVelocityPerSecond * velocityScale,
        velocitySmoothingHalfLifeMs = (config.velocitySmoothingHalfLifeMs * velocitySmoothingScale).toLong().coerceAtLeast(1L),
        maxLookaheadNs = (config.maxLookaheadNs * lookaheadScale).toLong().coerceAtLeast(1L)
    )

    companion object {
        /** Conservador: extrapola pouco e suaviza mais a inclinação - prioriza fidelidade ao dado
         * real. */
        val LIGHT = PredictionAggressiveness(
            velocityScale = 0.5,
            velocitySmoothingScale = 1.3,
            lookaheadScale = 0.6
        )

        /** Os defaults de [DefaultPredictionConfigs], sem escala. */
        val MODERATE = PredictionAggressiveness(
            velocityScale = 1.0,
            velocitySmoothingScale = 1.0,
            lookaheadScale = 1.0
        )

        /** Ousado: extrapola mais rápido e por mais tempo - mascara melhor cadências muito baixas
         * (ex.: OBD2 a 1-2Hz). A suavização da inclinação NÃO é reduzida aqui (permanece igual ao
         * MODERATE): "agressivo" é sobre até onde/quão rápido extrapolar, não sobre tolerar mais
         * ruído na inclinação estimada - reduzir a suavização é o que produz rampas em "onda
         * quadrada" em vez de rampas suaves. */
        val AGGRESSIVE = PredictionAggressiveness(
            velocityScale = 1.6,
            velocitySmoothingScale = 1.0,
            lookaheadScale = 1.6
        )
    }
}

/**
 * Extrapolação client-side ("dead reckoning") de campos de [SpeeduinoLiveData] entre amostras reais.
 *
 * Nunca atrasa a publicação de uma amostra real: [onSample] deve ser chamado imediatamente ao
 * receber cada amostra do transporte, e o valor real deve ser exibido/gravado sem esperar por esta
 * classe. [estimateAt] apenas responde "qual é a melhor estimativa agora", extrapolando a partir da
 * última amostra real conhecida e da velocidade (suavizada) observada — pensado para ser chamado a
 * qualquer cadência (por frame de UI, ou por um ticker de log) sem custo relevante.
 *
 * A extrapolação sempre reancora exatamente no último valor real (em dt=0, [estimateAt] retorna
 * esse valor sem nenhum ajuste) - não há descontinuidade a corrigir entre amostras, só a inclinação
 * (velocidade) usada para projetar adiante precisa ser estável, daí a suavização em [onSample].
 *
 * Especialmente importante para OBD2: como [io.ecucore.transport.Obd2Transport] já faz "hold last
 * value" por PID entre ciclos de poll, um campo pode chegar sem mudança real numa nova amostra —
 * tratar isso como velocidade zero resetaria a extrapolação incorretamente, então só recalculamos
 * velocidade quando o valor de fato mudou.
 *
 * Thread-safe em JVM/Android via [JvmSynchronized] (necessário: [onSample] é chamado pela thread
 * de streaming do transporte, enquanto [estimateAt]/[estimateRpmAt] tipicamente rodam num ticker ou
 * loop de frame de UI em outra thread - sem isso, [FieldTracker] podia ser lido com uma combinação
 * inconsistente de campos, ex. `lastRealValue` novo com `lastRealAtNs` antigo).
 */
class LiveDataPredictor(
    private val configs: Map<PredictedField, FieldPredictionConfig> = DefaultPredictionConfigs.defaults()
) {
    /** Atalho para configurar por [PredictionAggressiveness] em vez de montar o mapa manualmente. */
    constructor(aggressiveness: PredictionAggressiveness) : this(DefaultPredictionConfigs.forAggressiveness(aggressiveness))

    private class FieldTracker {
        var lastRealValue: Double = 0.0
        var lastRealAtNs: Long = 0L
        var velocityPerNs: Double = 0.0
        var initialized: Boolean = false
    }

    private var lastRealSample: SpeeduinoLiveData? = null
    private val trackers: Map<PredictedField, FieldTracker> = configs.keys.associateWith { FieldTracker() }

    @JvmSynchronized
    fun onSample(data: SpeeduinoLiveData, timestampNs: Long = MonotonicClock.nowNanos()) {
        lastRealSample = data
        for ((field, config) in configs) {
            val tracker = trackers.getValue(field)
            val newValue = fieldValue(data, field) ?: continue
            updateTracker(tracker, config, newValue, timestampNs)
        }
    }

    private fun updateTracker(tracker: FieldTracker, config: FieldPredictionConfig, newValue: Double, nowNs: Long) {
        if (!tracker.initialized) {
            tracker.lastRealValue = newValue
            tracker.lastRealAtNs = nowNs
            tracker.velocityPerNs = 0.0
            tracker.initialized = true
            return
        }

        if (newValue == tracker.lastRealValue) {
            // Valor "held" (staleness do polling escalonado do OBD2) - não é uma mudança real,
            // não mexe em lastRealAtNs/velocidade para não resetar a tendência em andamento.
            return
        }

        val dtNs = (nowNs - tracker.lastRealAtNs).coerceAtLeast(1L)
        val maxVelocityPerNs = config.maxVelocityPerSecond / 1_000_000_000.0
        val instantVelocityPerNs = ((newValue - tracker.lastRealValue) / dtNs)
            .coerceIn(-maxVelocityPerNs, maxVelocityPerNs)

        // Filtro passa-baixa (EMA) sobre a velocidade, não sobre o valor: em vez de substituir a
        // inclinação pela última medida "crua" (sensível a ruído/quantização da leitura, produz
        // rampas em degrau), mistura com a inclinação anterior usando meia-vida - dt pequeno entre
        // amostras próximas pesa pouco a leitura nova (suaviza), dt grande confia quase só nela.
        val dtMs = dtNs / 1_000_000.0
        val smoothingAlpha = 1.0 - 0.5.pow(dtMs / config.velocitySmoothingHalfLifeMs.toDouble())
        tracker.velocityPerNs += smoothingAlpha * (instantVelocityPerNs - tracker.velocityPerNs)
        tracker.lastRealValue = newValue
        tracker.lastRealAtNs = nowNs
    }

    @JvmSynchronized
    fun estimateAt(nowNs: Long = MonotonicClock.nowNanos()): SpeeduinoLiveData? {
        val base = lastRealSample ?: return null
        var result = base
        for ((field, config) in configs) {
            val tracker = trackers.getValue(field)
            if (!tracker.initialized) continue
            val estimated = extrapolate(tracker, config, nowNs)
            result = applyFieldValue(result, field, estimated)
        }
        return result
    }

    @JvmSynchronized
    fun estimateRpmAt(nowNs: Long = MonotonicClock.nowNanos()): Int {
        val tracker = trackers[PredictedField.RPM] ?: return lastRealSample?.rpm ?: 0
        val config = configs[PredictedField.RPM] ?: return lastRealSample?.rpm ?: 0
        if (!tracker.initialized) return lastRealSample?.rpm ?: 0
        return extrapolate(tracker, config, nowNs).toInt()
    }

    @JvmSynchronized
    fun reset() {
        lastRealSample = null
        for (tracker in trackers.values) {
            tracker.initialized = false
            tracker.lastRealValue = 0.0
            tracker.lastRealAtNs = 0L
            tracker.velocityPerNs = 0.0
        }
    }

    private fun extrapolate(tracker: FieldTracker, config: FieldPredictionConfig, nowNs: Long): Double {
        val dtNs = (nowNs - tracker.lastRealAtNs).coerceAtLeast(0L)
        return if (dtNs <= config.maxLookaheadNs) {
            tracker.lastRealValue + tracker.velocityPerNs * dtNs
        } else {
            val overshootNs = dtNs - config.maxLookaheadNs
            val decayFactor = exp(-overshootNs.toDouble() / config.maxLookaheadNs.toDouble())
            tracker.lastRealValue + tracker.velocityPerNs * config.maxLookaheadNs * decayFactor
        }
    }

    private fun fieldValue(data: SpeeduinoLiveData, field: PredictedField): Double? = when (field) {
        PredictedField.RPM -> data.rpm.toDouble()
        PredictedField.MAP_PRESSURE -> data.mapPressure.toDouble()
        PredictedField.TPS -> data.tps.toDouble()
        PredictedField.SPEED_KPH -> data.candidateSpeedKph?.toDouble()
        PredictedField.COOLANT_TEMP -> data.coolantTemp.toDouble()
    }

    private fun applyFieldValue(data: SpeeduinoLiveData, field: PredictedField, value: Double): SpeeduinoLiveData = when (field) {
        PredictedField.RPM -> data.copy(rpm = value.toInt())
        PredictedField.MAP_PRESSURE -> data.copy(mapPressure = value.toInt())
        PredictedField.TPS -> data.copy(tps = value.toInt())
        PredictedField.SPEED_KPH -> data.copy(candidateSpeedKph = value.toInt())
        PredictedField.COOLANT_TEMP -> data.copy(coolantTemp = value.toInt())
    }
}
