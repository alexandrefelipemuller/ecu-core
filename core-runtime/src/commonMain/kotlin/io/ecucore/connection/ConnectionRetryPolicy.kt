package io.ecucore.connection

import kotlinx.coroutines.delay

/**
 * Shared connect-time retry policy: attempts [block] up to [maxAttempts] times, waiting
 * [delayMs] between attempts. Ported from the Android app's ConnectionFlowController retry
 * loop so every platform (desktop, iOS, and eventually Android via ecu-core) can share the
 * same backoff behaviour instead of re-implementing it per app.
 *
 * Throws the last error once [maxAttempts] is exhausted.
 */
class ConnectionRetryPolicy(
    private val maxAttempts: Int = 3,
    private val delayMs: Long = 1000L,
) {
    suspend fun <T> connect(onAttemptFailed: (attempt: Int, error: Throwable) -> Unit = { _, _ -> }, block: suspend () -> T): T {
        var lastError: Throwable? = null
        var attempt = 0
        while (attempt < maxAttempts) {
            attempt++
            try {
                return block()
            } catch (e: Throwable) {
                lastError = e
                if (attempt < maxAttempts) {
                    onAttemptFailed(attempt, e)
                    delay(delayMs)
                }
            }
        }
        throw lastError ?: IllegalStateException("Connection failed with no recorded error")
    }
}

/**
 * Coordinates automatic reconnection after an unexpected connection drop (never after a
 * manual disconnect). Callers own the actual reconnect action (rebuilding a transport with
 * the last-used profile); this class only owns the "should we reconnect, and when" decision.
 *
 * Ported from the Android app's `scheduleAutomaticReconnect`/`reconnectJob` pattern.
 */
class AutoReconnectCoordinator(
    private val reconnectDelayMs: Long = 2000L,
) {
    /**
     * Call from the transport's connection-state callback. Returns true when the drop is
     * unexpected (was connected, is no longer connected, and this wasn't a manual disconnect)
     * and the caller should schedule [reconnect] after [reconnectDelayMs].
     */
    fun shouldReconnect(wasConnected: Boolean, isConnectedNow: Boolean, manualDisconnect: Boolean): Boolean {
        return wasConnected && !isConnectedNow && !manualDisconnect
    }

    suspend fun awaitReconnectDelay() {
        delay(reconnectDelayMs)
    }
}
