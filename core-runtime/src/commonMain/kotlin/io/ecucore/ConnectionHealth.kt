package io.ecucore

import io.ecucore.shared.MonotonicClock

/** Troca de protocolo no meio da sessão (ex.: live data moderno que falhou e caiu para legacy). */
data class ProtocolSwitch(val from: String, val to: String, val reason: String, val atMs: Long)

/**
 * Resumo de saúde de UMA sessão de conexão. O app lê isto ao desconectar e decide se a sessão
 * merece um não-fatal (ver `ConnectionHealthPolicy`). Tudo aqui já acontecia, mas só chegava ao
 * log local: falhas recuperáveis que se curam sozinhas nunca apareciam em lugar nenhum.
 */
data class ConnectionHealthSnapshot(
    val durationMs: Long,
    val framesReceived: Long,
    val recoverableTimeouts: Int,
    val recoverableDesyncs: Int,
    val modernLiveFailures: Int,
    val streamRecoveries: Int,
    val writeChunkRetries: Int,
    val forcedDisconnects: Int,
    val linkLossesDuringStream: Int,
    val protocolSwitches: List<ProtocolSwitch>,
    val handshakeFallbackReason: String?,
    val legacyLockedOut: Boolean,
    val lastError: String?,
    val lastWriteOperation: String?,
    val msSinceLastWrite: Long?,
    val swallowedErrors: Int = 0,
    val lastSwallowed: String? = null,
) {
    val recoverableFailures: Int get() = recoverableTimeouts + recoverableDesyncs

    /** Falhas por 1000 quadros de live data (0 se ainda não houve quadros). */
    val failuresPer1000Frames: Double
        get() = if (framesReceived <= 0) 0.0 else recoverableFailures * 1000.0 / framesReceived

    /** "modern>legacy:live_data_modern_failed@12s;..." (no máximo [limit] trocas). */
    fun protocolSwitchSummary(limit: Int = 4): String =
        protocolSwitches.take(limit).joinToString(";") { "${it.from}>${it.to}:${it.reason}@${it.atMs / 1000}s" }
}

/** Contadores de uma sessão. Thread-safe: o stream, as gravações e o app escrevem/leem em threads diferentes. */
internal class ConnectionHealth {
    private var startedAtMs = MonotonicClock.nowMillis()
    private var frames = 0L
    private var timeouts = 0
    private var desyncs = 0
    private var modernLiveFailures = 0
    private var streamRecoveries = 0
    private var writeRetries = 0
    private var forcedDisconnects = 0
    private var linkLosses = 0
    private val switches = mutableListOf<ProtocolSwitch>()
    private var lastError: String? = null
    private var lastWriteOperation: String? = null
    private var lastWriteAtMs: Long? = null
    private var swallowed = 0
    private var lastSwallowed: String? = null

    @Synchronized
    fun reset() {
        startedAtMs = MonotonicClock.nowMillis()
        frames = 0; timeouts = 0; desyncs = 0; modernLiveFailures = 0; streamRecoveries = 0
        writeRetries = 0; forcedDisconnects = 0; linkLosses = 0
        switches.clear(); lastError = null; lastWriteOperation = null; lastWriteAtMs = null
        swallowed = 0; lastSwallowed = null
    }

    @Synchronized fun recordFrame() { frames++ }

    @Synchronized
    fun recordRecoverable(isDesync: Boolean, message: String?) {
        if (isDesync) desyncs++ else timeouts++
        lastError = message?.take(MAX_ERROR)
    }

    @Synchronized
    fun recordModernLiveFailure(message: String?) {
        modernLiveFailures++
        lastError = message?.take(MAX_ERROR)
    }

    @Synchronized fun recordStreamRecovery() { streamRecoveries++ }
    @Synchronized fun recordWriteRetry() { writeRetries++ }

    @Synchronized
    fun recordForcedDisconnect(message: String?) {
        forcedDisconnects++
        lastError = message?.take(MAX_ERROR)
    }

    @Synchronized fun recordLinkLossDuringStream() { linkLosses++ }

    @Synchronized
    fun recordProtocolSwitch(from: String, to: String, reason: String) {
        if (switches.size < MAX_SWITCHES) {
            switches += ProtocolSwitch(from, to, reason, MonotonicClock.nowMillis() - startedAtMs)
        }
    }

    @Synchronized
    fun recordWriteOperation(operation: String) {
        lastWriteOperation = operation
        lastWriteAtMs = MonotonicClock.nowMillis()
    }

    /** Erro que o código tratou sem propagar (ver EcuClient.noteSwallowed). Contado para não sumir. */
    @Synchronized
    fun recordSwallowed(where: String, message: String?) {
        swallowed++
        lastSwallowed = "$where: ${message ?: "sem mensagem"}".take(MAX_ERROR)
    }

    @Synchronized
    fun snapshot(handshakeFallbackReason: String?, legacyLockedOut: Boolean): ConnectionHealthSnapshot {
        val now = MonotonicClock.nowMillis()
        return ConnectionHealthSnapshot(
            durationMs = now - startedAtMs,
            framesReceived = frames,
            recoverableTimeouts = timeouts,
            recoverableDesyncs = desyncs,
            modernLiveFailures = modernLiveFailures,
            streamRecoveries = streamRecoveries,
            writeChunkRetries = writeRetries,
            forcedDisconnects = forcedDisconnects,
            linkLossesDuringStream = linkLosses,
            protocolSwitches = switches.toList(),
            handshakeFallbackReason = handshakeFallbackReason,
            legacyLockedOut = legacyLockedOut,
            lastError = lastError,
            lastWriteOperation = lastWriteOperation,
            msSinceLastWrite = lastWriteAtMs?.let { now - it },
            swallowedErrors = swallowed,
            lastSwallowed = lastSwallowed,
        )
    }

    private companion object {
        const val MAX_ERROR = 120
        const val MAX_SWITCHES = 8
    }
}
