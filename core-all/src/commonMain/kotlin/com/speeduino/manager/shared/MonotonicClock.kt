package com.speeduino.manager.shared

import kotlin.time.TimeSource

/**
 * Monotonic clock used by SpeeduinoClient's live-data pacing/fault-recovery interval checks.
 * Kotlin/Native has no System.currentTimeMillis()/System.nanoTime() (JVM-only); those calls only
 * ever compare deltas against each other here, never wall-clock time, so a monotonic origin works.
 */
object MonotonicClock {
    private val origin = TimeSource.Monotonic.markNow()
    fun nowMillis(): Long = origin.elapsedNow().inWholeMilliseconds
    fun nowNanos(): Long = origin.elapsedNow().inWholeNanoseconds
}
