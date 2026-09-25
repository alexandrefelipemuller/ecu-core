package io.ecucore.shared

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Relógio de parede (epoch ms). Use para timestamps que são persistidos e comparados entre
 * execuções do app - [MonotonicClock] conta a partir do início do processo e volta a ~0 a cada
 * reinício, então não serve para isso.
 */
object WallClock {
    @OptIn(ExperimentalTime::class)
    fun nowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
}
