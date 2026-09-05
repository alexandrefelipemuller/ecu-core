package io.ecucore.telemetry

import io.ecucore.SpeeduinoLiveData
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
 * @param maxAccelerationPerSecondSq quanto a velocidade estimada pode mudar por segundo — protege
 *   contra uma leitura ruidosa isolada distorcendo a extrapolação.
 * @param maxLookaheadNs além desse tempo sem uma atualização real do campo, a extrapolação passa a
 *   decair exponencialmente em vez de continuar linear, evitando que o valor "fuja" se o link travar.
 * @param correctionHalfLifeMs meia-vida (ms) do erro residual entre o valor extrapolado e o valor
 *   real recém-chegado, usada para reconciliar suavemente sem nunca atrasar a publicação do real.
 */
data class FieldPredictionConfig(
    val maxVelocityPerSecond: Double,
    val maxAccelerationPerSecondSq: Double,
    val maxLookaheadNs: Long,
    val correctionHalfLifeMs: Long
)

object DefaultPredictionConfigs {
    val RPM = FieldPredictionConfig(
        maxVelocityPerSecond = 8000.0,
        maxAccelerationPerSecondSq = 40_000.0,
        maxLookaheadNs = 350_000_000L,
        correctionHalfLifeMs = 80L
    )
    val MAP_PRESSURE = FieldPredictionConfig(
        maxVelocityPerSecond = 120.0,
        maxAccelerationPerSecondSq = 800.0,
        maxLookaheadNs = 350_000_000L,
        correctionHalfLifeMs = 100L
    )
    val TPS = FieldPredictionConfig(
        maxVelocityPerSecond = 400.0,
        maxAccelerationPerSecondSq = 4_000.0,
        maxLookaheadNs = 350_000_000L,
        correctionHalfLifeMs = 60L
    )
    val SPEED_KPH = FieldPredictionConfig(
        maxVelocityPerSecond = 40.0,
        maxAccelerationPerSecondSq = 200.0,
        maxLookaheadNs = 500_000_000L,
        correctionHalfLifeMs = 150L
    )
    val COOLANT_TEMP = FieldPredictionConfig(
        maxVelocityPerSecond = 2.0,
        maxAccelerationPerSecondSq = 5.0,
        maxLookaheadNs = 2_000_000_000L,
        correctionHalfLifeMs = 500L
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
     * [PredictionAggressiveness.LIGHT] ou até [PredictionAggressiveness.OFF].
     */
    fun forAggressiveness(aggressiveness: PredictionAggressiveness): Map<PredictedField, FieldPredictionConfig> =
        defaults().mapValues { (_, config) -> aggressiveness.scale(config) }
}

/**
 * Fator de escala aplicado sobre [DefaultPredictionConfigs] para tornar a extrapolação mais ou
 * menos "ousada", sem precisar redefinir os limites de cada campo manualmente.
 *
 * - Escalas de velocidade/aceleração maiores = a extrapolação confia mais na tendência observada e
 *   se move mais rápido para acompanhar mudanças bruscas (bom para mascarar cadência muito baixa,
 *   como OBD2 a 1-2Hz), ao custo de mais chance de overshoot em ruído.
 * - `lookaheadScale` maior = continua extrapolando por mais tempo antes de decair para o último
 *   valor real quando a próxima amostra atrasa.
 * - `correctionHalfLifeScale` maior = a reconciliação com a amostra real recém-chegada é mais lenta
 *   (mais suave, mas o valor exibido fica mais tempo "atrás" do real após uma correção grande).
 */
data class PredictionAggressiveness(
    val velocityScale: Double,
    val accelerationScale: Double,
    val lookaheadScale: Double,
    val correctionHalfLifeScale: Double
) {
    internal fun scale(config: FieldPredictionConfig): FieldPredictionConfig = config.copy(
        maxVelocityPerSecond = config.maxVelocityPerSecond * velocityScale,
        maxAccelerationPerSecondSq = config.maxAccelerationPerSecondSq * accelerationScale,
        maxLookaheadNs = (config.maxLookaheadNs * lookaheadScale).toLong().coerceAtLeast(1L),
        correctionHalfLifeMs = (config.correctionHalfLifeMs * correctionHalfLifeScale).toLong().coerceAtLeast(1L)
    )

    companion object {
        /** Conservador: extrapola pouco e converge rápido - prioriza fidelidade ao dado real. */
        val LIGHT = PredictionAggressiveness(
            velocityScale = 0.5,
            accelerationScale = 0.5,
            lookaheadScale = 0.6,
            correctionHalfLifeScale = 0.6
        )

        /** Os defaults de [DefaultPredictionConfigs], sem escala. */
        val MODERATE = PredictionAggressiveness(
            velocityScale = 1.0,
            accelerationScale = 1.0,
            lookaheadScale = 1.0,
            correctionHalfLifeScale = 1.0
        )

        /** Ousado: extrapola mais rápido e por mais tempo - mascara melhor cadências muito baixas
         * (ex.: OBD2 a 1-2Hz), aceitando mais risco de overshoot em ruído. */
        val AGGRESSIVE = PredictionAggressiveness(
            velocityScale = 1.6,
            accelerationScale = 1.8,
            lookaheadScale = 1.6,
            correctionHalfLifeScale = 1.4
        )
    }
}

/**
 * Extrapolação client-side ("dead reckoning") de campos de [SpeeduinoLiveData] entre amostras reais.
 *
 * Nunca atrasa a publicação de uma amostra real: [onSample] deve ser chamado imediatamente ao
 * receber cada amostra do transporte, e o valor real deve ser exibido/gravado sem esperar por esta
 * classe. [estimateAt] apenas responde "qual é a melhor estimativa agora", extrapolando a partir da
 * última amostra real conhecida e da velocidade observada — pensado para ser chamado a qualquer
 * cadência (por frame de UI, ou por um ticker de log) sem custo relevante.
 *
 * Especialmente importante para OBD2: como [io.ecucore.transport.Obd2Transport] já faz "hold last
 * value" por PID entre ciclos de poll, um campo pode chegar sem mudança real numa nova amostra —
 * tratar isso como velocidade zero resetaria a extrapolação incorretamente, então só recalculamos
 * velocidade quando o valor de fato mudou.
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
        var correctionOffset: Double = 0.0
        var correctionStartedAtNs: Long = 0L
        var initialized: Boolean = false
    }

    private var lastRealSample: SpeeduinoLiveData? = null
    private val trackers: Map<PredictedField, FieldTracker> = configs.keys.associateWith { FieldTracker() }

    fun onSample(data: SpeeduinoLiveData, timestampNs: Long = MonotonicClock.nowNanos()) {
        lastRealSample = data
        for ((field, config) in configs) {
            val tracker = trackers.getValue(field)
            val newValue = fieldValue(data, field) ?: continue
            updateTracker(tracker, config, newValue, timestampNs)
        }
    }

    private fun updateTracker(tracker: LiveDataPredictor.FieldTracker, config: FieldPredictionConfig, newValue: Double, nowNs: Long) {
        if (!tracker.initialized) {
            tracker.lastRealValue = newValue
            tracker.lastRealAtNs = nowNs
            tracker.velocityPerNs = 0.0
            tracker.correctionOffset = 0.0
            tracker.correctionStartedAtNs = nowNs
            tracker.initialized = true
            return
        }

        if (newValue == tracker.lastRealValue) {
            // Valor "held" (staleness do polling escalonado do OBD2) - não é uma mudança real,
            // não mexe em lastRealAtNs/velocidade para não resetar a tendência em andamento.
            return
        }

        val estimateBeforeUpdate = extrapolate(tracker, config, nowNs)

        val dtNs = (nowNs - tracker.lastRealAtNs).coerceAtLeast(1L)
        val maxVelocityPerNs = config.maxVelocityPerSecond / 1_000_000_000.0
        val instantVelocityPerNs = ((newValue - tracker.lastRealValue) / dtNs)
            .coerceIn(-maxVelocityPerNs, maxVelocityPerNs)

        val maxVelocityDeltaPerNs = config.maxAccelerationPerSecondSq / 1_000_000_000.0 / 1_000_000_000.0 * dtNs
        val velocityDelta = (instantVelocityPerNs - tracker.velocityPerNs)
            .coerceIn(-maxVelocityDeltaPerNs, maxVelocityDeltaPerNs)

        tracker.velocityPerNs = tracker.velocityPerNs + velocityDelta
        tracker.lastRealValue = newValue
        tracker.lastRealAtNs = nowNs

        tracker.correctionOffset = newValue - estimateBeforeUpdate
        tracker.correctionStartedAtNs = nowNs
    }

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

    fun estimateRpmAt(nowNs: Long = MonotonicClock.nowNanos()): Int {
        val tracker = trackers[PredictedField.RPM] ?: return lastRealSample?.rpm ?: 0
        val config = configs[PredictedField.RPM] ?: return lastRealSample?.rpm ?: 0
        if (!tracker.initialized) return lastRealSample?.rpm ?: 0
        return extrapolate(tracker, config, nowNs).toInt()
    }

    fun reset() {
        lastRealSample = null
        for (tracker in trackers.values) {
            tracker.initialized = false
            tracker.lastRealValue = 0.0
            tracker.lastRealAtNs = 0L
            tracker.velocityPerNs = 0.0
            tracker.correctionOffset = 0.0
            tracker.correctionStartedAtNs = 0L
        }
    }

    private fun extrapolate(tracker: FieldTracker, config: FieldPredictionConfig, nowNs: Long): Double {
        val dtNs = (nowNs - tracker.lastRealAtNs).coerceAtLeast(0L)
        val predicted = if (dtNs <= config.maxLookaheadNs) {
            tracker.lastRealValue + tracker.velocityPerNs * dtNs
        } else {
            val overshootNs = dtNs - config.maxLookaheadNs
            val decayFactor = exp(-overshootNs.toDouble() / config.maxLookaheadNs.toDouble())
            tracker.lastRealValue + tracker.velocityPerNs * config.maxLookaheadNs * decayFactor
        }

        val correctionAgeMs = (nowNs - tracker.correctionStartedAtNs).coerceAtLeast(0L) / 1_000_000.0
        val correctionDecay = 0.5.pow(correctionAgeMs / config.correctionHalfLifeMs.toDouble())
        return predicted + tracker.correctionOffset * correctionDecay
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
