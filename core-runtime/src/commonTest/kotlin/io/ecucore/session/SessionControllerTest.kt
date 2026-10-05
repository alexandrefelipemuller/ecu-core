package io.ecucore.session

import io.ecucore.model.EcuCapabilities
import io.ecucore.model.EcuFamily
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionControllerTest {

    // ---- estados derivados --------------------------------------------------------------------------------

    @Test
    fun `connection state derives connected and active from the status`() {
        val expected = mapOf(
            SessionConnectionStatus.Disconnected to (false to false),
            SessionConnectionStatus.Connecting to (false to true),
            SessionConnectionStatus.Connected to (true to true),
            SessionConnectionStatus.Failed to (false to false),
        )
        for ((status, flags) in expected) {
            val s = SessionConnectionState(status = status)
            assertEquals(flags.first, s.isConnected, status.name)
            assertEquals(flags.second, s.isActive, status.name)
        }
    }

    @Test
    fun `default session state is empty and disconnected`() {
        val s = SessionState()
        assertEquals(SessionConnectionStatus.Disconnected, s.connection.status)
        assertEquals(SessionTransportType.Unknown, s.connection.transport)
        assertFalse(s.sync.isBusy)
        assertEquals(EcuFamily.UNKNOWN, s.ecuFamily)
        assertNull(s.ecuCapabilities)
        assertNull(s.connectedSinceMs)
        assertEquals(0, s.sessionActionCount)
        assertFalse(s.sessionHadError)
        assertEquals(listOf(1), s.tables.availableVeMapIndices)
        assertEquals(1, s.tables.selectedIgnitionMapIndex)
        assertTrue(s.tables.unavailableConfigPages.isEmpty())
        assertTrue(s.definitions.availableIniDefinitions.isEmpty())
        assertFalse(s.settings.readOnlySafeModeEnabled)
        assertNull(s.diagnostics.lastErrorMessage)
        assertTrue(SessionError("x").recoverable)
    }

    // ---- SessionControllerStore -----------------------------------------------------------------------------

    @Test
    fun `connect intent records the transport and endpoint`() = runTest {
        val store = SessionControllerStore()
        store.dispatch(SessionIntent.Connect(SessionTransportType.Bluetooth, "HC-05"))
        assertEquals(SessionTransportType.Bluetooth, store.state.value.connection.transport)
        assertEquals("HC-05", store.state.value.connection.detail)
        store.dispatch(SessionIntent.Connect(SessionTransportType.Tcp))
        assertEquals(SessionTransportType.Tcp, store.state.value.connection.transport)
        assertNull(store.state.value.connection.detail)
    }

    @Test
    fun `import intent queues a message effect`() = runTest {
        val store = SessionControllerStore()
        assertNull(store.effects.value)
        store.dispatch(SessionIntent.ImportIni("speeduino.ini"))
        assertEquals(SessionEffect.ShowMessage("Import request queued: speeduino.ini"), store.effects.value)
        store.clearEffect()
        assertNull(store.effects.value)
    }

    @Test
    fun `the remaining intents are accepted without changing the state`() = runTest {
        val store = SessionControllerStore()
        val before = store.state.value
        for (intent in listOf(
            SessionIntent.Disconnect, SessionIntent.StartStream(100), SessionIntent.StopStream, SessionIntent.PauseStream,
            SessionIntent.DownloadConfigs(), SessionIntent.DownloadConfigs(autoRestartStream = false),
            SessionIntent.RefreshTables("manual"), SessionIntent.RefreshDefinitions,
        )) store.dispatch(intent)
        assertEquals(before, store.state.value)
        assertNull(store.effects.value)
    }

    @Test
    fun `settings updates`() {
        val store = SessionControllerStore()
        store.updateConnectionSettings(SessionTransportType.UsbSerial, "COM3")
        assertEquals("COM3", store.state.value.connection.detail)
        store.updateConnectionSettings(SessionTransportType.Obd2)
        assertNull(store.state.value.connection.detail)
        store.updateFirmwareProfile("speeduino 202501", readOnlySafeMode = true)
        assertEquals("speeduino 202501", store.state.value.settings.manualFirmwareProfile)
        assertTrue(store.state.value.settings.readOnlySafeModeEnabled)
        store.updateFirmwareProfile(null)
        assertNull(store.state.value.settings.manualFirmwareProfile)
        assertFalse(store.state.value.settings.readOnlySafeModeEnabled)
        val before = store.state.value
        store.updateTelemetryContext()
        assertEquals(before, store.state.value)
    }

    @Test
    fun `state slices are replaced independently`() {
        val store = SessionControllerStore()
        store.updateConnectionStatus(SessionConnectionState(SessionConnectionStatus.Connected, SessionTransportType.Tcp, "10.0.0.5"))
        store.updateSyncState(SessionSyncState(isBusy = true, progressPercent = 40, message = "baixando", currentStep = "p2"))
        store.updateDefinitionState(SessionDefinitionState(importedIniDefinitions = listOf(SessionImportedIniDefinition("a.ini", "sig", "SPEEDUINO"))))
        store.updateTableState(SessionTableState(selectedVeMapIndex = 2, closedLoopCorrectionSupported = true))
        store.updateDiagnosticsState(SessionDiagnosticsState(lastErrorMessage = "boom", latestLogPath = "/tmp/x"))
        val s = store.state.value
        assertTrue(s.connection.isConnected)
        assertEquals(40, s.sync.progressPercent)
        assertEquals("a.ini", s.definitions.importedIniDefinitions.single().fileName)
        assertEquals(2, s.tables.selectedVeMapIndex)
        assertEquals("boom", s.diagnostics.lastErrorMessage)
        // uma fatia não apaga as outras
        store.updateSyncState(SessionSyncState())
        assertTrue(store.state.value.connection.isConnected)
        assertEquals(2, store.state.value.tables.selectedVeMapIndex)
    }

    @Test
    fun `session meta keeps previous values for omitted arguments`() {
        val store = SessionControllerStore()
        val caps = EcuCapabilities(true, true, true, true, true, true)
        store.updateSessionMeta(ecuFamily = EcuFamily.MS3, ecuCapabilities = caps, connectedSinceMs = 123L, sessionActionCount = 2, sessionHadError = true)
        store.updateSessionMeta(sessionActionCount = 3)
        val s = store.state.value
        assertEquals(EcuFamily.MS3, s.ecuFamily)
        assertEquals(caps, s.ecuCapabilities)
        assertEquals(123L, s.connectedSinceMs)
        assertEquals(3, s.sessionActionCount)
        assertTrue(s.sessionHadError)
        store.updateSessionMeta(ecuCapabilities = null, connectedSinceMs = null, sessionHadError = false)
        assertNull(store.state.value.ecuCapabilities)
        assertNull(store.state.value.connectedSinceMs)
        assertFalse(store.state.value.sessionHadError)
    }

    @Test
    fun `effects can be emitted and replaced`() {
        val store = SessionControllerStore()
        store.emitEffect(SessionEffect.ShowError("falha"))
        assertEquals(SessionEffect.ShowError("falha"), store.effects.value)
        store.emitEffect(SessionEffect.RequestPermission("bluetooth"))
        store.emitEffect(SessionEffect.OpenScreen("/tables"))
        assertEquals(SessionEffect.OpenScreen("/tables"), store.effects.value)
    }

    @Test
    fun `initial state is honoured`() {
        val initial = SessionState(sessionActionCount = 7, ecuFamily = EcuFamily.RUSEFI)
        val store = SessionControllerStore(initial)
        assertEquals(initial, store.state.value)
    }

    // ---- SyncControllerStore ------------------------------------------------------------------------------------

    @Test
    fun `sync controller mirrors its state into the session`() {
        val session = SessionControllerStore()
        val sync = SyncControllerStore(session)
        assertFalse(sync.state.value.isBusy)

        sync.setBusy("iniciando", "passo 1")
        assertEquals(SessionSyncState(true, 0, "iniciando", "passo 1"), sync.state.value)
        assertEquals(sync.state.value, session.state.value.sync)

        sync.updateProgress(55, "baixando", "passo 2")
        assertEquals(SessionSyncState(true, 55, "baixando", "passo 2"), session.state.value.sync)

        sync.setIdle("concluído")
        assertFalse(sync.state.value.isBusy)
        assertEquals(55, sync.state.value.progressPercent) // progresso final é mantido
        assertEquals("concluído", session.state.value.sync.message)
        assertNull(session.state.value.sync.currentStep)

        sync.setBusy()
        assertEquals(0, sync.state.value.progressPercent)
        assertNull(sync.state.value.message)
        sync.updateProgress(10)
        sync.setIdle(currentStep = "fim")
        assertEquals("fim", sync.state.value.currentStep)
    }

    @Test
    fun `sync controller starts from the session sync state`() {
        val session = SessionControllerStore(SessionState(sync = SessionSyncState(isBusy = true, progressPercent = 30)))
        val sync = SyncControllerStore(session)
        assertEquals(30, sync.state.value.progressPercent)
        assertTrue(sync.state.value.isBusy)
    }
}
