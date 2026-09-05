package io.ecucore.telemetry

import io.ecucore.SpeeduinoLiveData
import io.ecucore.shared.MonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Decorator reutilizável em volta de qualquer transporte (Speeduino/MS2/MS3/rusEFI, OBD2, PSA, ...)
 * que adiciona extrapolação preditiva ("dead reckoning") de baixa camada, para que tanto a UI quanto
 * os logs se beneficiem de uma cadência de amostras mais alta e mais fluida do que a cadência real de
 * leitura do protocolo (particularmente relevante para OBD2, limitado a 1-2Hz em muitos veículos).
 *
 * Uso:
 * - Chame [ingestRealSample] a partir do `onDataReceived` real do transporte, assim que a amostra
 *   chega — ela é publicada em [samples] IMEDIATAMENTE, sem nenhum atraso adicional.
 * - Chame [start] uma vez por sessão de conexão para também emitir, a cada [tickIntervalMs], uma
 *   amostra extrapolada em [samples] — é isso que dá a consumidores mais lentos (como um log gravado
 *   a cada N ms) uma cadência mais alta e suave do que a cadência real do protocolo.
 * - Para consumidores de UI que já rodam seu próprio loop de frame (ex.: `withFrameNanos`), prefira
 *   chamar [estimateAt]/[estimateRpmAt] diretamente a cada frame em vez de coletar [samples] — evita
 *   o overhead do Flow e desacopla a cadência visual (60-120Hz) da cadência de log.
 * - Chame [reset] ao desconectar/reconectar para não herdar tendência de uma sessão anterior.
 */
class LiveDataUpsampler(
    private val predictor: LiveDataPredictor = LiveDataPredictor(),
    private val tickIntervalMs: Long = DEFAULT_TICK_INTERVAL_MS
) {
    private val _samples = MutableSharedFlow<SpeeduinoLiveData>(extraBufferCapacity = SAMPLE_BUFFER_CAPACITY)

    /** Amostras reais (imediatas) intercaladas com amostras extrapoladas emitidas a cada [tickIntervalMs]. */
    val samples: SharedFlow<SpeeduinoLiveData> = _samples

    private var tickerJob: Job? = null

    /** Publica a amostra real imediatamente e alimenta o preditor. Nunca atrasa a publicação. */
    fun ingestRealSample(data: SpeeduinoLiveData, timestampNs: Long = MonotonicClock.nowNanos()) {
        predictor.onSample(data, timestampNs)
        _samples.tryEmit(data)
    }

    /** Inicia o ticker de baixa cadência que emite amostras extrapoladas para consumidores como logs. */
    fun start(scope: CoroutineScope) {
        stop()
        tickerJob = scope.launch {
            while (isActive) {
                delay(tickIntervalMs)
                predictor.estimateAt()?.let { _samples.tryEmit(it) }
            }
        }
    }

    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
    }

    /** Consulta pontual e barata, pensada para ser chamada a cada frame de UI (60-120Hz). */
    fun estimateAt(nowNs: Long = MonotonicClock.nowNanos()): SpeeduinoLiveData? = predictor.estimateAt(nowNs)

    fun estimateRpmAt(nowNs: Long = MonotonicClock.nowNanos()): Int = predictor.estimateRpmAt(nowNs)

    fun reset() {
        predictor.reset()
    }

    companion object {
        const val DEFAULT_TICK_INTERVAL_MS = 50L
        private const val SAMPLE_BUFFER_CAPACITY = 8
    }
}
