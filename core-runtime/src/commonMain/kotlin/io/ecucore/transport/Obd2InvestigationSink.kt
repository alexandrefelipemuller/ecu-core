package io.ecucore.transport

import io.ecucore.SpeeduinoLiveData

/**
 * Generic sink for persisting OBD2/brand-diagnostic investigation sessions (raw command/response
 * timeline, decoded samples, proprietary frames, metadata). The narrow surface here is exactly
 * what the transports in this module call - the concrete implementation (file/zip writing) is
 * platform-specific and lives in the app.
 */
interface Obd2InvestigationSink {
    fun startSession(transport: String, metadataExtras: Map<String, String> = emptyMap())

    fun closeSession(summary: Map<String, String> = emptyMap())

    fun info(stage: String, message: String)

    fun recordCommand(
        transport: String,
        command: String,
        response: String,
        timeoutMs: Long,
        elapsedMs: Long,
        extra: Map<String, String> = emptyMap(),
    )

    fun recordSample(
        source: String,
        sample: SpeeduinoLiveData,
        extras: Map<String, String> = emptyMap(),
    )

    fun recordProprietaryFrame(
        source: String,
        cycle: Long,
        command: String,
        rawHex: String,
        bytes: List<Int>,
        extras: Map<String, String> = emptyMap(),
    )

    fun updateMetadata(key: String, value: Any?)
}

object NoopObd2InvestigationSink : Obd2InvestigationSink {
    override fun startSession(transport: String, metadataExtras: Map<String, String>) {}
    override fun closeSession(summary: Map<String, String>) {}
    override fun info(stage: String, message: String) {}
    override fun recordCommand(
        transport: String,
        command: String,
        response: String,
        timeoutMs: Long,
        elapsedMs: Long,
        extra: Map<String, String>,
    ) {}
    override fun recordSample(source: String, sample: SpeeduinoLiveData, extras: Map<String, String>) {}
    override fun recordProprietaryFrame(
        source: String,
        cycle: Long,
        command: String,
        rawHex: String,
        bytes: List<Int>,
        extras: Map<String, String>,
    ) {}
    override fun updateMetadata(key: String, value: Any?) {}
}
