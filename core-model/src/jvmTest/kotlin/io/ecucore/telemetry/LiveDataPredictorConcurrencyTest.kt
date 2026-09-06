package io.ecucore.telemetry

import io.ecucore.SpeeduinoLiveData
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Stress test JVM-only (real threads, não corrotina de teste com clock virtual) para o cenário real
 * de uso: [LiveDataPredictor.onSample] é chamado pela thread de streaming do transporte enquanto
 * [LiveDataPredictor.estimateRpmAt] é lido por um ticker/loop de frame de UI em outra thread. Não
 * prova ausência de race (isso exigiria instrumentação), mas é uma rede de segurança: sem
 * `@JvmSynchronized`, esse teste falhava esporadicamente com valores absurdos (fora de qualquer
 * faixa fisicamente plausível) ou exceptions.
 */
class LiveDataPredictorConcurrencyTest {

    @Test
    fun concurrentOnSampleAndEstimate_neverThrowsOrProducesAbsurdValues() {
        val predictor = LiveDataPredictor()
        val stop = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val readyLatch = CountDownLatch(2)

        val writer = Thread {
            readyLatch.countDown()
            var rpm = 800
            var t = 0L
            try {
                while (!stop.get()) {
                    rpm = (rpm + 37) % 8000
                    t += 5_000_000L // +5ms
                    predictor.onSample(liveData(rpm), timestampNs = t)
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }

        val reader = Thread {
            readyLatch.countDown()
            try {
                while (!stop.get()) {
                    val estimated = predictor.estimateRpmAt()
                    // RPM nunca pode ser negativo nem exceder um limite fisicamente absurdo -
                    // um valor fora disso indica leitura inconsistente do FieldTracker (race).
                    assertTrue(
                        estimated in 0..20_000,
                        "estimateRpmAt() produced an out-of-bounds value under concurrent access: $estimated"
                    )
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }

        writer.start()
        reader.start()
        readyLatch.await()
        Thread.sleep(500)
        stop.set(true)
        writer.join(2_000)
        reader.join(2_000)

        failure.get()?.let { throw it }
    }

    @Test
    fun concurrentResetAndEstimate_neverThrows() {
        val predictor = LiveDataPredictor()
        predictor.onSample(liveData(1000), timestampNs = 0L)
        val stop = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)

        val resetter = Thread {
            try {
                while (!stop.get()) {
                    predictor.reset()
                    predictor.onSample(liveData(1200), timestampNs = System.nanoTime())
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }
        val reader = Thread {
            try {
                while (!stop.get()) {
                    predictor.estimateAt() // pode ser null logo após reset(), não deve lançar
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }

        resetter.start()
        reader.start()
        Thread.sleep(300)
        stop.set(true)
        resetter.join(2_000)
        reader.join(2_000)

        failure.get()?.let { throw it }
    }

    private fun liveData(rpm: Int) = SpeeduinoLiveData(
        secl = 0,
        rpm = rpm,
        coolantTemp = 80,
        intakeTemp = 25,
        mapPressure = 100,
        tps = 0,
        batteryVoltage = 12.6,
        advance = 10,
        o2 = 0,
        engineStatus = 1,
        sparkStatus = 1
    )
}
