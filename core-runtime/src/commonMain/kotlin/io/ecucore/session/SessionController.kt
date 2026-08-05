package io.ecucore.session

import io.ecucore.definition.IniCatalogEntry
import io.ecucore.definition.IniDefinition
import io.ecucore.model.AfrTable
import io.ecucore.model.DwellTable
import io.ecucore.model.ClosedLoopCorrectionConfig
import io.ecucore.model.EcuCapabilities
import io.ecucore.model.EcuFamily
import io.ecucore.model.EngineConstants
import io.ecucore.model.EngineProtectionConfig
import io.ecucore.model.IdleControlSettings
import io.ecucore.model.IgnitionTable
import io.ecucore.model.PinLayoutInfo
import io.ecucore.model.RusefiInputOutputSnapshot
import io.ecucore.model.SecondarySerialConfig
import io.ecucore.model.TriggerSettings
import io.ecucore.model.VeTable
import io.ecucore.protocol.SerialCapability
import io.ecucore.transport.MapSelectionSupport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class SessionConnectionStatus {
    Disconnected,
    Connecting,
    Connected,
    Failed,
}

enum class SessionTransportType {
    Unknown,
    Tcp,
    Bluetooth,
    UsbSerial,
    Obd2,
}

data class SessionError(
    val message: String,
    val causeClass: String? = null,
    val recoverable: Boolean = true,
)

data class SessionConnectionState(
    val status: SessionConnectionStatus = SessionConnectionStatus.Disconnected,
    val transport: SessionTransportType = SessionTransportType.Unknown,
    val detail: String? = null,
    val error: SessionError? = null,
) {
    val isConnected: Boolean
        get() = status == SessionConnectionStatus.Connected

    val isActive: Boolean
        get() = status == SessionConnectionStatus.Connecting || status == SessionConnectionStatus.Connected
}

data class SessionSyncState(
    val isBusy: Boolean = false,
    val progressPercent: Int = 0,
    val message: String? = null,
    val currentStep: String? = null,
)

data class SessionDefinitionState(
    val activeIniCatalogEntry: IniCatalogEntry? = null,
    val activeIniDefinition: IniDefinition? = null,
    val availableIniDefinitions: List<IniCatalogEntry> = emptyList(),
    val importedIniDefinitions: List<SessionImportedIniDefinition> = emptyList(),
)

data class SessionImportedIniDefinition(
    val fileName: String,
    val signature: String,
    val family: String,
)

data class SessionTableState(
    val engineConstants: EngineConstants? = null,
    val triggerSettings: TriggerSettings? = null,
    val idleControlSettings: IdleControlSettings? = null,
    val closedLoopCorrectionConfig: ClosedLoopCorrectionConfig? = null,
    val engineProtectionConfig: EngineProtectionConfig? = null,
    val rusefiInputOutputSnapshot: RusefiInputOutputSnapshot? = null,
    val veTable: VeTable? = null,
    val ignitionTable: IgnitionTable? = null,
    val afrTable: AfrTable? = null,
    val dwellTable: DwellTable? = null,
    val availableVeMapIndices: List<Int> = listOf(1),
    val availableIgnitionMapIndices: List<Int> = listOf(1),
    val selectedVeMapIndex: Int = 1,
    val selectedIgnitionMapIndex: Int = 1,
    val closedLoopCorrectionReloadToken: Int = 0,
    val closedLoopCorrectionSupported: Boolean = false,
    val unavailableConfigPages: Set<Int> = emptySet(),
)

data class SessionSettingsState(
    val firmwareProfileOverride: String? = null,
    val manualFirmwareProfile: String? = null,
    val readOnlySafeModeEnabled: Boolean = false,
    val connectionProfileTag: String? = null,
    val connectionHint: String? = null,
)

data class SessionDiagnosticsState(
    val lastErrorMessage: String? = null,
    val lastFirmwareSignature: String? = null,
    val lastProductString: String? = null,
    val lastSerialCapability: SerialCapability? = null,
    val lastPinLayoutInfo: PinLayoutInfo? = null,
    val lastMapSelectionSupport: MapSelectionSupport? = null,
    val latestLogPath: String? = null,
)

data class SessionState(
    val connection: SessionConnectionState = SessionConnectionState(),
    val sync: SessionSyncState = SessionSyncState(),
    val definitions: SessionDefinitionState = SessionDefinitionState(),
    val tables: SessionTableState = SessionTableState(),
    val settings: SessionSettingsState = SessionSettingsState(),
    val diagnostics: SessionDiagnosticsState = SessionDiagnosticsState(),
    val ecuFamily: EcuFamily = EcuFamily.UNKNOWN,
    val ecuCapabilities: EcuCapabilities? = null,
    val connectedSinceMs: Long? = null,
    val sessionActionCount: Int = 0,
    val sessionHadError: Boolean = false,
)

sealed interface SessionIntent {
    data class Connect(
        val transport: SessionTransportType,
        val endpointLabel: String? = null,
    ) : SessionIntent

    data object Disconnect : SessionIntent

    data class StartStream(
        val intervalMs: Long,
    ) : SessionIntent

    data object StopStream : SessionIntent

    data object PauseStream : SessionIntent

    data class DownloadConfigs(
        val autoRestartStream: Boolean = true,
    ) : SessionIntent

    data class RefreshTables(
        val reason: String,
    ) : SessionIntent

    data object RefreshDefinitions : SessionIntent

    data class ImportIni(
        val fileName: String,
    ) : SessionIntent
}

sealed interface SessionEffect {
    data class ShowMessage(val message: String) : SessionEffect
    data class ShowError(val message: String) : SessionEffect
    data class RequestPermission(val permissionKey: String) : SessionEffect
    data class OpenScreen(val route: String) : SessionEffect
}

interface SessionController {
    val state: StateFlow<SessionState>
    val effects: StateFlow<SessionEffect?>

    suspend fun dispatch(intent: SessionIntent)
    fun updateConnectionSettings(transport: SessionTransportType, endpointLabel: String? = null)
    fun updateFirmwareProfile(profile: String?, readOnlySafeMode: Boolean = false)
    fun updateTelemetryContext()
    fun clearEffect()
}

interface SyncController {
    val state: StateFlow<SessionSyncState>

    fun setBusy(message: String? = null, currentStep: String? = null)
    fun updateProgress(progressPercent: Int, message: String? = null, currentStep: String? = null)
    fun setIdle(message: String? = null, currentStep: String? = null)
}

class SyncControllerStore(
    private val sessionController: SessionControllerStore,
) : SyncController {
    private val _state = MutableStateFlow(sessionController.state.value.sync)
    override val state: StateFlow<SessionSyncState> = _state

    override fun setBusy(message: String?, currentStep: String?) {
        val next = _state.value.copy(
                isBusy = true,
                progressPercent = 0,
                message = message,
                currentStep = currentStep,
            )
        _state.value = next
        sessionController.updateSyncState(next)
    }

    override fun updateProgress(progressPercent: Int, message: String?, currentStep: String?) {
        val next = _state.value.copy(
                isBusy = true,
                progressPercent = progressPercent,
                message = message,
                currentStep = currentStep,
            )
        _state.value = next
        sessionController.updateSyncState(next)
    }

    override fun setIdle(message: String?, currentStep: String?) {
        val next = _state.value.copy(
                isBusy = false,
                progressPercent = _state.value.progressPercent,
                message = message,
                currentStep = currentStep,
            )
        _state.value = next
        sessionController.updateSyncState(next)
    }
}

class SessionControllerStore(
    initialState: SessionState = SessionState(),
) : SessionController {
    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<SessionState> = _state

    private val _effects = MutableStateFlow<SessionEffect?>(null)
    override val effects: StateFlow<SessionEffect?> = _effects

    override suspend fun dispatch(intent: SessionIntent) {
        // Skeleton: concrete platform controllers will implement behavior later.
        when (intent) {
            is SessionIntent.Connect -> updateConnectionSettings(intent.transport, intent.endpointLabel)
            is SessionIntent.ImportIni -> emitEffect(SessionEffect.ShowMessage("Import request queued: ${intent.fileName}"))
            is SessionIntent.DownloadConfigs,
            is SessionIntent.Disconnect,
            is SessionIntent.PauseStream,
            is SessionIntent.RefreshDefinitions,
            is SessionIntent.RefreshTables,
            is SessionIntent.StartStream,
            is SessionIntent.StopStream -> Unit
        }
    }

    override fun updateConnectionSettings(transport: SessionTransportType, endpointLabel: String?) {
        _state.value = _state.value.copy(
            connection = _state.value.connection.copy(
                transport = transport,
                detail = endpointLabel,
            ),
        )
    }

    override fun updateFirmwareProfile(profile: String?, readOnlySafeMode: Boolean) {
        _state.value = _state.value.copy(
            settings = _state.value.settings.copy(
                manualFirmwareProfile = profile,
                readOnlySafeModeEnabled = readOnlySafeMode,
            ),
        )
    }

    override fun updateTelemetryContext() {
        // Skeleton hook for platform telemetry sync.
    }

    override fun clearEffect() {
        _effects.value = null
    }

    fun updateConnectionStatus(state: SessionConnectionState) {
        _state.value = _state.value.copy(connection = state)
    }

    fun updateSyncState(state: SessionSyncState) {
        _state.value = _state.value.copy(sync = state)
    }

    fun updateDefinitionState(state: SessionDefinitionState) {
        _state.value = _state.value.copy(definitions = state)
    }

    fun updateTableState(state: SessionTableState) {
        _state.value = _state.value.copy(tables = state)
    }

    fun updateDiagnosticsState(state: SessionDiagnosticsState) {
        _state.value = _state.value.copy(diagnostics = state)
    }

    fun updateSessionMeta(
        ecuFamily: EcuFamily = _state.value.ecuFamily,
        ecuCapabilities: EcuCapabilities? = _state.value.ecuCapabilities,
        connectedSinceMs: Long? = _state.value.connectedSinceMs,
        sessionActionCount: Int = _state.value.sessionActionCount,
        sessionHadError: Boolean = _state.value.sessionHadError,
    ) {
        _state.value = _state.value.copy(
            ecuFamily = ecuFamily,
            ecuCapabilities = ecuCapabilities,
            connectedSinceMs = connectedSinceMs,
            sessionActionCount = sessionActionCount,
            sessionHadError = sessionHadError,
        )
    }

    fun emitEffect(effect: SessionEffect) {
        _effects.value = effect
    }
}
