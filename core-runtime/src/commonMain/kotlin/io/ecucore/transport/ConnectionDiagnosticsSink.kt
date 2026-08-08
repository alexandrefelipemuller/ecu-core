package io.ecucore.transport

/**
 * Generic sink for connection diagnostics logging. Implementations are platform-specific
 * (e.g. Android keeps an in-memory ring buffer backed by android.util.Log); transports in
 * this module only depend on this interface, never on a concrete platform logger.
 */
interface ConnectionDiagnosticsSink {
    fun log(transport: String, state: String, message: String)

    fun logError(transport: String, state: String, message: String, throwable: Throwable? = null) {
        log(transport, state, message)
        throwable?.let { log(transport, state, "error: ${it.message ?: it.toString()}") }
    }
}

object NoopConnectionDiagnosticsSink : ConnectionDiagnosticsSink {
    override fun log(transport: String, state: String, message: String) {
        // no-op
    }
}
