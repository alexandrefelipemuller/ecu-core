package io.ecucore.safety

/**
 * Estima se o veículo está em movimento a partir das velocidades disponíveis (HARA H9).
 * É best-effort: Speeduino não expõe velocidade no live data decodificado; vem de OBD2/PSA
 * (`candidateSpeedKph`) ou do GPS, quando ativo. Sem nenhuma fonte, a velocidade é desconhecida
 * e o aviso de movimento não dispara (o aviso de uso da primeira gravação continua valendo).
 */
object VehicleMotion {
    const val MOVING_THRESHOLD_KMH = 5.0

    /** Maior velocidade entre as fontes disponíveis; `null` se não há nenhuma. */
    fun speedKmh(candidateSpeedKph: Int?, gpsSpeedKph: Double?): Double? =
        listOfNotNull(candidateSpeedKph?.toDouble(), gpsSpeedKph?.takeIf { it >= 0.0 }).maxOrNull()

    fun isMoving(speedKmh: Double?): Boolean = speedKmh != null && speedKmh >= MOVING_THRESHOLD_KMH
}
