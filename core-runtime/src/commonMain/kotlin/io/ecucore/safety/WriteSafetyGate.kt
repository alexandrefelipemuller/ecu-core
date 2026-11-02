package io.ecucore.safety

import io.ecucore.shared.MonotonicClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Pedido exibido pela UI antes de uma gravação (HARA H9). */
sealed interface WriteSafetyRequest {
    /** Aviso de uso, mostrado uma vez antes da primeira gravação. */
    data object FirstUseNotice : WriteSafetyRequest

    /** O veículo parece estar em movimento. */
    data class VehicleMoving(val speedKmh: Double) : WriteSafetyRequest
}

/**
 * Autorização antes de qualquer escrita na ECU (`EcuClient.writeGuard`):
 *  1. aviso de uso (off-road/bancada, responsabilidade do usuário) uma única vez;
 *  2. se a velocidade indica movimento, confirma que a edição não é feita por quem dirige; a
 *     resposta vale por [movingAckWindowMs] para não perguntar a cada página de uma restauração.
 */
class WriteSafetyGate(
    private val isNoticeAccepted: () -> Boolean,
    private val markNoticeAccepted: () -> Unit,
    private val currentSpeedKmh: () -> Double?,
    private val nowMs: () -> Long = MonotonicClock::nowMillis,
    private val movingAckWindowMs: Long = MOVING_ACK_WINDOW_MS,
) {
    private val _pending = MutableStateFlow<WriteSafetyRequest?>(null)
    val pending: StateFlow<WriteSafetyRequest?> = _pending.asStateFlow()

    @kotlin.concurrent.Volatile private var decision: CompletableDeferred<Boolean>? = null
    private var movingAckedAtMs: Long? = null

    suspend fun authorize(): Boolean {
        if (!isNoticeAccepted()) {
            if (!ask(WriteSafetyRequest.FirstUseNotice)) return false
            markNoticeAccepted()
        }
        val speed = currentSpeedKmh()
        if (VehicleMotion.isMoving(speed)) {
            val ackedAt = movingAckedAtMs
            val stillAcked = ackedAt != null && nowMs() - ackedAt < movingAckWindowMs
            if (!stillAcked) {
                if (!ask(WriteSafetyRequest.VehicleMoving(speed!!))) return false
                movingAckedAtMs = nowMs()
            }
        }
        return true
    }

    fun resolve(accepted: Boolean) {
        decision?.complete(accepted)
    }

    private suspend fun ask(request: WriteSafetyRequest): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        decision = deferred
        _pending.value = request
        return try {
            deferred.await()
        } finally {
            _pending.value = null
            if (decision === deferred) decision = null
        }
    }

    companion object {
        const val MOVING_ACK_WINDOW_MS = 2 * 60 * 1000L
    }
}
