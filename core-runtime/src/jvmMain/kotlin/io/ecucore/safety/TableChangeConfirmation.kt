package io.ecucore.safety

import io.ecucore.model.TableChangeAssessment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/** Pedido de confirmação exibido pela UI enquanto a gravação aguarda. */
data class TableChangeRequest(val assessment: TableChangeAssessment)

/**
 * Ponte entre o `EcuClient.tableChangeGuard` (coroutine de gravação) e o diálogo de
 * confirmação (UI): [confirm] publica o pedido e suspende até [resolve].
 *
 * Fluxos em que a substituição em massa é o objetivo (Base Map Wizard, migração só-mapas) rodam
 * dentro de [withoutConfirmation] e não perguntam.
 */
class TableChangeConfirmation {
    private val _pending = MutableStateFlow<TableChangeRequest?>(null)
    val pending: StateFlow<TableChangeRequest?> = _pending.asStateFlow()

    private val bypassDepth = AtomicInteger(0)
    @Volatile private var decision: CompletableDeferred<Boolean>? = null

    suspend fun confirm(assessment: TableChangeAssessment): Boolean {
        if (bypassDepth.get() > 0) return true
        val deferred = CompletableDeferred<Boolean>()
        decision = deferred
        _pending.value = TableChangeRequest(assessment)
        return try {
            deferred.await()
        } finally {
            _pending.value = null
            if (decision === deferred) decision = null
        }
    }

    fun resolve(accepted: Boolean) {
        decision?.complete(accepted)
    }

    suspend fun <T> withoutConfirmation(block: suspend () -> T): T {
        bypassDepth.incrementAndGet()
        try {
            return block()
        } finally {
            bypassDepth.decrementAndGet()
        }
    }
}
