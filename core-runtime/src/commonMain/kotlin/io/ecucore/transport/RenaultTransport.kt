package io.ecucore.transport

import io.ecucore.shared.Logger
import kotlin.concurrent.Volatile
import io.ecucore.shared.MonotonicClock
import io.ecucore.SpeeduinoLiveData
import io.ecucore.connection.ISpeeduinoConnection
import io.ecucore.definition.IniDefinition
import io.ecucore.ecu.FirmwareInfo
import io.ecucore.model.AfrTable
import io.ecucore.model.DwellTable
import io.ecucore.model.EcuCapabilities
import io.ecucore.model.EcuFamily
import io.ecucore.model.EcuPageDescriptor
import io.ecucore.model.EngineConstants
import io.ecucore.model.EngineProtectionConfig
import io.ecucore.model.IgnitionTable
import io.ecucore.model.PinLayoutInfo
import io.ecucore.model.PressureCalibration
import io.ecucore.model.RusefiInputOutputSnapshot
import io.ecucore.model.SecondarySerialConfig
import io.ecucore.model.TableDefinitions
import io.ecucore.model.TpsCalibration
import io.ecucore.model.TriggerSettings
import io.ecucore.model.VeTable
import io.ecucore.protocol.SerialCapability

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import io.ecucore.transport.EcuTransport
import io.ecucore.transport.Obd2ProfileStore

/**
 * Camada preparatória para fluxo Renault proprietário.
 *
 * Fase atual:
 * - detecta sessão Renault (21/22/10xx)
 * - stream base continua SAE via Obd2Transport
 * - tentativa híbrida: lê sinais OEM (rpm/coolant) por 22F40C/22F40D com fallback automático SAE
 */
class RenaultTransport(
    private val connection: ISpeeduinoConnection,
    private val onDataReceived: (SpeeduinoLiveData) -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val obd2ProfileStore: Obd2ProfileStore? = null,
    private val investigationRecorder: Obd2InvestigationSink? = null,
    private val diagnosticsSink: ConnectionDiagnosticsSink = NoopConnectionDiagnosticsSink,
) : EcuTransport, Obd2DiagnosticsCapable {
    companion object {
        private const val TAG = "RenaultTransport"
        private const val OEM_POLL_INTERVAL_MS = 1600L
        private const val CONFIDENCE_MIN = 3
        private const val CONFIDENCE_MAX = 8
        private const val SCAN_BUDGET_MS = 35_000L
        private const val MAX_NO_DATA_STREAK = 3

        private data class OemCommandProfile(
            val id: String,
            val keywords: Set<String>,
            val rpmCommands: List<String>,
            val coolantCommands: List<String>,
        )

        private val OEM_PROFILES = listOf(
            OemCommandProfile(
                id = "sirius_32",
                keywords = setOf("SIEMENS", "SIRIUS", "S32", "K4M"),
                rpmCommands = listOf("22F40C", "210C", "22F45C"),
                coolantCommands = listOf("22F40D", "210D", "22F45D")
            ),
            OemCommandProfile(
                id = "bosch_me7",
                keywords = setOf("BOSCH", "ME7", "ME 7", "ME7.4"),
                rpmCommands = listOf("220C", "22F40C", "210C"),
                coolantCommands = listOf("2205", "22F40D", "210D")
            ),
            OemCommandProfile(
                id = "generic",
                keywords = emptySet(),
                rpmCommands = listOf("22F40C", "210C"),
                coolantCommands = listOf("22F40D", "210D")
            )
        )

        /**
         * Siemens Sirius 32 (Renault Clio K4M) não fala SAE J1979 - o dispatcher KWP2000
         * "cru" do firmware só expõe 4 SIDs proprietários (0x27/0x23/0x14/0x2A). O único
         * grupo de leitura por local ID (0x2A) que expõe status de DTC é o 0x0893, e só
         * cobre a "palavra 0" da tabela real de 60 índices (word_index=0): IAT, MAP e CKP.
         * Os demais ~57 códigos existem no binário mas não têm um grupo KWP conhecido que
         * os exponha - ver ecu-core/core-runtime notas de RE / simulador (SIRIUS32_DTC_TABLE
         * em obd2_tcp_simulator.py) para o levantamento completo.
         */
        private data class Sirius32DtcBit(val mask: Int, val code: String, val description: String)

        private val SIRIUS32_WORD0_DTCS = listOf(
            Sirius32DtcBit(0x0400, "DF003", "Circuito do sensor de temperatura do ar (IAT)"),
            Sirius32DtcBit(0x0040, "DF045", "Circuito do sensor de pressão do coletor (MAP)"),
            Sirius32DtcBit(0x0002, "DF025", "Circuito do sensor de rotação/virabrequim (CKP)"),
        )
    }

    private data class RenaultCanCandidate(
        val txId: String,
        val rxId: String,
        val label: String
    )

    private data class RenaultDetection(
        val protocol: String,
        val txId: String? = null,
        val rxId: String? = null,
        val address: String? = null,
        val initMode: String? = null,
        val hints: Set<String> = emptySet()
    )

    private data class OemOverlay(
        val rpm: Int? = null,
        val coolant: Int? = null,
        val rpmConfidence: Int = 0,
        val coolantConfidence: Int = 0,
    )

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var oemPollingJob: Job? = null
    @Volatile
    private var lastSaeSample: SpeeduinoLiveData? = null
    @Volatile
    private var overlay: OemOverlay = OemOverlay()
    @Volatile
    private var oemPollCycle = 0L

    private val obd2Delegate = Obd2Transport(
        connection = connection,
        onDataReceived = { sample -> onDelegateData(sample) },
        onConnectionStateChanged = onConnectionStateChanged,
        onError = onError,
        profileStore = obd2ProfileStore,
        diagnosticsSink = diagnosticsSink,
    )
    private var cachedFirmwareInfo: FirmwareInfo? = null
    private var detectedSession: RenaultDetection? = null
    private var oemProfile = OEM_PROFILES.last()

    override suspend fun connect() {
        try {
            if (!connection.isConnected()) {
                connection.connect()
            }
            investigationRecorder?.startSession(
                transport = "renault",
                metadataExtras = mapOf(
                    "connection" to connection.getConnectionInfo().trim().ifBlank { "unknown" },
                    "campaign" to "exploratory"
                )
            )
            investigationRecorder?.info("campaign", "starting renault exploratory scan")

            val detection = scanRenaultSession()
            if (detection == null || detection.hints.isEmpty()) {
                investigationRecorder?.info("campaign", "renault scan finished without positive hints")
                runCatching { connection.disconnect() }
                throw IllegalStateException("ECU Renault proprietária não detectada")
            }

            detectedSession = detection

            obd2Delegate.connect()
            oemProfile = detectOemProfile()
            val delegateInfo = obd2Delegate.getFirmwareInfoCached()
            val detail = buildString {
                append("RENAULT ").append(detection.protocol.uppercase()).append(" ELM327")
                if (!detection.txId.isNullOrBlank() && !detection.rxId.isNullOrBlank()) {
                    append(" TX=").append(detection.txId).append(" RX=").append(detection.rxId)
                } else if (!detection.address.isNullOrBlank()) {
                    append(" ADDR=").append(detection.address)
                }
                if (!detection.initMode.isNullOrBlank()) {
                    append(" INIT=").append(detection.initMode.uppercase())
                }
            }
            cachedFirmwareInfo = delegateInfo?.copy(signature = detail) ?: delegateInfo
            investigationRecorder?.updateMetadata("renault_protocol", detection.protocol)
            investigationRecorder?.updateMetadata("renault_hints", detection.hints.toList())
            investigationRecorder?.updateMetadata("renault_oem_profile", oemProfile.id)
            investigationRecorder?.updateMetadata("renault_init_mode", detection.initMode ?: "")
            investigationRecorder?.info(
                "campaign",
                "renault detection succeeded protocol=${detection.protocol} tx=${detection.txId ?: "-"} rx=${detection.rxId ?: "-"} addr=${detection.address ?: "-"} init=${detection.initMode ?: "-"} profile=${oemProfile.id}"
            )

            diagnosticsSink.log(
                "renault",
                "probe",
                "detected protocol=${detection.protocol} tx=${detection.txId ?: "-"} rx=${detection.rxId ?: "-"} addr=${detection.address ?: "-"} hints=${detection.hints.joinToString(",")}" 
            )
            diagnosticsSink.log("renault", "oem_profile", "selected=${oemProfile.id}")
        } catch (t: Throwable) {
            investigationRecorder?.info(
                "connect_failure",
                "reason=${t.message ?: t::class.simpleName}"
            )
            detectedSession = null
            cachedFirmwareInfo = null
            oemProfile = OEM_PROFILES.last()
            runCatching { obd2Delegate.disconnect() }
            throw t
        }
    }

    override fun disconnect() {
        val profileAtDisconnect = oemProfile.id
        stopOemPolling()
        detectedSession = null
        cachedFirmwareInfo = null
        overlay = OemOverlay()
        lastSaeSample = null
        oemPollCycle = 0L
        oemProfile = OEM_PROFILES.last()
        obd2Delegate.disconnect()
        investigationRecorder?.closeSession(
            summary = mapOf(
                "transport" to "renault",
                "profile" to profileAtDisconnect
            )
        )
    }

    override fun isConnected(): Boolean = obd2Delegate.isConnected()

    override fun isStreaming(): Boolean = obd2Delegate.isStreaming()

    override fun startLiveDataStream(intervalMs: Long) {
        obd2Delegate.startLiveDataStream(intervalMs)
        startOemPolling()
    }

    override fun stopLiveDataStream() {
        stopOemPolling()
        obd2Delegate.stopLiveDataStream()
    }

    override suspend fun pauseLiveDataStream(timeoutMs: Long) {
        stopOemPolling()
        obd2Delegate.pauseLiveDataStream(timeoutMs)
    }

    override suspend fun getFirmwareInfo(): String {
        return cachedFirmwareInfo?.signature ?: obd2Delegate.getFirmwareInfo()
    }

    override fun getFirmwareInfoCached(): FirmwareInfo? = cachedFirmwareInfo ?: obd2Delegate.getFirmwareInfoCached()

    override fun getEcuFamily(): EcuFamily = obd2Delegate.getEcuFamily()

    override fun getEcuCapabilities(): EcuCapabilities? = obd2Delegate.getEcuCapabilities()

    override fun getTableDefinitions(): TableDefinitions? = obd2Delegate.getTableDefinitions()

    override fun getEcuPageCatalog(): List<EcuPageDescriptor> = obd2Delegate.getEcuPageCatalog()

    override fun getPinLayoutInfoCached(): PinLayoutInfo? = obd2Delegate.getPinLayoutInfoCached()

    override fun cachePinLayoutInfo(info: PinLayoutInfo?) = obd2Delegate.cachePinLayoutInfo(info)

    override fun isReadOnlySafeMode(): Boolean = obd2Delegate.isReadOnlySafeMode()

    override fun applyIniDefinition(definition: IniDefinition): Boolean = obd2Delegate.applyIniDefinition(definition)

    override fun setManualFirmwareProfile(signature: String, readOnly: Boolean) =
        obd2Delegate.setManualFirmwareProfile(signature, readOnly)

    override suspend fun getSerialCapability(): SerialCapability = obd2Delegate.getSerialCapability()

    override suspend fun readFullPage(pageNum: Int, pageSize: Int, blockSize: Int): ByteArray =
        obd2Delegate.readFullPage(pageNum, pageSize, blockSize)

    override suspend fun readVeTable(mapIndex: Int): VeTable = obd2Delegate.readVeTable(mapIndex)

    override suspend fun readIgnitionTable(mapIndex: Int): IgnitionTable = obd2Delegate.readIgnitionTable(mapIndex)

    override suspend fun readAfrTable(): AfrTable = obd2Delegate.readAfrTable()

    override suspend fun readDwellTable(): DwellTable = obd2Delegate.readDwellTable()

    override suspend fun readEngineConstants(): EngineConstants = obd2Delegate.readEngineConstants()

    override suspend fun readTriggerSettings(): TriggerSettings = obd2Delegate.readTriggerSettings()

    override suspend fun readRusefiInputOutputSnapshot(): RusefiInputOutputSnapshot =
        obd2Delegate.readRusefiInputOutputSnapshot()

    override suspend fun readEngineProtectionConfig(): EngineProtectionConfig =
        obd2Delegate.readEngineProtectionConfig()

    override suspend fun readPressureCalibration(): PressureCalibration = obd2Delegate.readPressureCalibration()

    override suspend fun writePressureCalibration(calibration: PressureCalibration, burn: Boolean) =
        obd2Delegate.writePressureCalibration(calibration, burn)

    override suspend fun readTpsCalibration(): TpsCalibration = obd2Delegate.readTpsCalibration()

    override suspend fun writeTpsCalibration(calibration: TpsCalibration, burn: Boolean) =
        obd2Delegate.writeTpsCalibration(calibration, burn)

    override suspend fun readSecondarySerialConfig(): SecondarySerialConfig =
        obd2Delegate.readSecondarySerialConfig()

    override suspend fun writeSecondarySerialConfig(config: SecondarySerialConfig, burn: Boolean) =
        obd2Delegate.writeSecondarySerialConfig(config, burn)

    override suspend fun writeEngineProtectionConfig(config: EngineProtectionConfig, burn: Boolean) =
        obd2Delegate.writeEngineProtectionConfig(config, burn)

    override suspend fun writeTriggerSettings(settings: TriggerSettings, burn: Boolean) =
        obd2Delegate.writeTriggerSettings(settings, burn)

    override suspend fun writeRawPage(pageNum: Int, data: ByteArray) = obd2Delegate.writeRawPage(pageNum, data)

    override suspend fun writeRawPageWithoutBurn(pageNum: Int, data: ByteArray) =
        obd2Delegate.writeRawPageWithoutBurn(pageNum, data)

    override suspend fun burnConfigs() = obd2Delegate.burnConfigs()

    override suspend fun writeVeTable(veTable: VeTable, mapIndex: Int) =
        obd2Delegate.writeVeTable(veTable, mapIndex)

    override suspend fun writeIgnitionTable(ignitionTable: IgnitionTable, mapIndex: Int) =
        obd2Delegate.writeIgnitionTable(ignitionTable, mapIndex)

    override suspend fun writeAfrTable(afrTable: AfrTable) = obd2Delegate.writeAfrTable(afrTable)

    override suspend fun writeDwellTable(dwellTable: DwellTable) = obd2Delegate.writeDwellTable(dwellTable)

    override suspend fun writeEngineConstants(engineConstants: EngineConstants) =
        obd2Delegate.writeEngineConstants(engineConstants)

    override suspend fun readDtcCodes(): List<DtcCode> =
        if (isSirius32()) sirius32ReadDtcCodes(DtcStatus.ACTIVE) else obd2Delegate.readDtcCodes()

    override suspend fun readPendingDtcCodes(): List<DtcCode> =
        if (isSirius32()) sirius32ReadDtcCodes(DtcStatus.PENDING) else obd2Delegate.readPendingDtcCodes()

    override suspend fun clearDtcCodes(): Boolean =
        if (isSirius32()) sirius32ClearDtcCodes() else obd2Delegate.clearDtcCodes()

    private fun onDelegateData(sample: SpeeduinoLiveData) {
        lastSaeSample = sample
        val applied = applyOverlay(sample)
        onDataReceived(applied)
    }

    private fun applyOverlay(sample: SpeeduinoLiveData): SpeeduinoLiveData {
        val current = overlay
        val rpm = if (current.rpmConfidence >= CONFIDENCE_MIN) current.rpm ?: sample.rpm else sample.rpm
        val coolant = if (current.coolantConfidence >= CONFIDENCE_MIN) current.coolant ?: sample.coolantTemp else sample.coolantTemp
        return sample.copy(rpm = rpm, coolantTemp = coolant)
    }

    private fun startOemPolling() {
        if (oemPollingJob?.isActive == true) return
        if (detectedSession == null) return

        oemPollingJob = scope.launch {
            while (isActive && obd2Delegate.isConnected() && obd2Delegate.isStreaming()) {
                runCatching { pollOemOnce() }
                    .onFailure { err ->
                        diagnosticsSink.log("renault", "oem_poll", "failed: ${err.message ?: "unknown"}")
                    }
                delay(OEM_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopOemPolling() {
        oemPollingJob?.cancel()
        oemPollingJob = null
    }

    private suspend fun pollOemOnce() {
        val session = detectedSession ?: return
        val sae = lastSaeSample
        oemPollCycle++

        if (session.hints.any { it.startsWith("session_") }) {
            listOf("1003", "10C0").forEach { cmd ->
                val resp = obd2Delegate.sendRawElmCommand(cmd, timeoutMs = 900L).uppercase()
                if (resp.contains("50")) return@forEach
            }
        }

        var rpmCandidate: Int? = null
        var rpmWinner: String? = null
        for (cmd in oemProfile.rpmCommands) {
            val response = obd2Delegate.sendRawElmCommand(cmd, timeoutMs = 900L)
            val parsed = parseRpmResponse(cmd, response)
            if (parsed != null) {
                rpmCandidate = parsed
                rpmWinner = cmd
                break
            }
        }

        var coolantCandidate: Int? = null
        var coolantWinner: String? = null
        for (cmd in oemProfile.coolantCommands) {
            val response = obd2Delegate.sendRawElmCommand(cmd, timeoutMs = 900L)
            val parsed = parseCoolantResponse(cmd, response)
            if (parsed != null) {
                coolantCandidate = parsed
                coolantWinner = cmd
                break
            }
        }

        val rpmValid = validateRpm(rpmCandidate, sae?.rpm)
        val coolantValid = validateCoolant(coolantCandidate, sae?.coolantTemp)
        overlay = overlay.copy(
            rpm = rpmCandidate ?: overlay.rpm,
            rpmConfidence = computeConfidence(
                previous = overlay.rpmConfidence,
                valid = rpmValid
            ),
            coolant = coolantCandidate ?: overlay.coolant,
            coolantConfidence = computeConfidence(
                previous = overlay.coolantConfidence,
                valid = coolantValid
            )
        )

        diagnosticsSink.log(
            "renault",
            "oem_poll",
            "cycle=$oemPollCycle profile=${oemProfile.id} rpm_cmd=${rpmWinner ?: "-"} rpm_oem=${rpmCandidate ?: -1} rpm_sae=${sae?.rpm ?: -1} rpm_valid=$rpmValid rpm_conf=${overlay.rpmConfidence} clt_cmd=${coolantWinner ?: "-"} clt_oem=${coolantCandidate ?: -99} clt_sae=${sae?.coolantTemp ?: -99} clt_valid=$coolantValid clt_conf=${overlay.coolantConfidence}"
        )
    }

    private fun computeConfidence(previous: Int, valid: Boolean): Int {
        return if (valid) {
            (previous + 1).coerceAtMost(CONFIDENCE_MAX)
        } else {
            (previous - 1).coerceAtLeast(0)
        }
    }

    private fun validateRpm(value: Int?, saeRpm: Int?): Boolean {
        value ?: return false
        if (value !in 0..9000) return false
        val baseline = saeRpm?.takeIf { it > 0 } ?: return value > 250
        val tolerance = max(250, (baseline * 0.35).toInt())
        return abs(value - baseline) <= tolerance
    }

    private fun validateCoolant(value: Int?, saeCoolant: Int?): Boolean {
        value ?: return false
        if (value !in -40..140) return false
        val baseline = saeCoolant ?: return true
        return abs(value - baseline) <= 20
    }

    private fun parseRpmResponse(command: String, response: String): Int? {
        val bytes = parsePositiveResponse(command, response) ?: return null
        if (bytes.size < 2) return null
        return ((bytes[0] shl 8) or bytes[1]) / 4
    }

    private fun parseCoolantResponse(command: String, response: String): Int? {
        val bytes = parsePositiveResponse(command, response) ?: return null
        if (bytes.isEmpty()) return null
        return bytes[0] - 40
    }

    private fun parsePositiveResponse(command: String, response: String): List<Int>? {
        val clean = response.uppercase()
        val bytes = Regex("[0-9A-F]{2}")
            .findAll(clean)
            .map { it.value.toInt(16) }
            .toList()
        if (bytes.isEmpty()) return null

        return when {
            command.startsWith("22") -> {
                if (bytes.size < 5) return null
                val didHi = command.substring(2, 4).toInt(16)
                val didLo = command.substring(4, 6).toInt(16)
                val idx = bytes.windowed(3, 1).indexOfFirst { it[0] == 0x62 && it[1] == didHi && it[2] == didLo }
                if (idx < 0) null else bytes.drop(idx + 3)
            }

            command.startsWith("21") -> {
                if (bytes.size < 3) return null
                val loc = command.substring(2, 4).toInt(16)
                val idx = bytes.windowed(2, 1).indexOfFirst { it[0] == 0x61 && it[1] == loc }
                if (idx < 0) null else bytes.drop(idx + 2)
            }

            else -> null
        }
    }

    private suspend fun detectOemProfile(): OemCommandProfile {
        val fingerprint = buildString {
            append(obd2Delegate.sendRawElmCommand("22F1A0", timeoutMs = 900L)).append(' ')
            append(obd2Delegate.sendRawElmCommand("2180", timeoutMs = 900L)).append(' ')
            append(obd2Delegate.sendRawElmCommand("22F190", timeoutMs = 900L))
        }
        val decoded = decodeAsciiFromHexPayload(fingerprint).uppercase()
        diagnosticsSink.log(
            "renault",
            "oem_fingerprint",
            "raw=${fingerprint.replace(Regex("\\s+"), " ").trim().take(180)} decoded=${decoded.take(80)}"
        )
        val match = OEM_PROFILES.firstOrNull { profile ->
            profile.keywords.isNotEmpty() && profile.keywords.any { it in decoded }
        }
        return match ?: OEM_PROFILES.last()
    }

    private fun decodeAsciiFromHexPayload(raw: String): String {
        val bytes = Regex("[0-9A-F]{2}")
            .findAll(raw.uppercase())
            .map { it.value.toInt(16) }
            .toList()
        if (bytes.isEmpty()) return raw
        return bytes
            .mapNotNull { value ->
                val ch = value.toChar()
                if (ch in ' '..'~') ch else null
            }
            .joinToString("")
    }

    private fun scanRenaultSession(): RenaultDetection? {
        val deadline = MonotonicClock.nowMillis() + SCAN_BUDGET_MS
        val adapterProtocol = sendRawElmCommand("ATDP").uppercase()
        val preferIso = adapterProtocol.contains("KWP FAST") || adapterProtocol.contains("ISO 9141")
        investigationRecorder?.updateMetadata("renault_adapter_protocol", adapterProtocol)
        investigationRecorder?.info(
            "campaign",
            "renault adapter protocol probe=$adapterProtocol preferIso=$preferIso"
        )
        if (preferIso) {
            val isoDetection = scanRenaultIso(deadline)
            if (isoDetection != null && isoDetection.hints.isNotEmpty()) {
                return isoDetection
            }
            if (MonotonicClock.nowMillis() >= deadline) {
                diagnosticsSink.log("renault", "probe", "scan timeout before CAN fallback")
                return isoDetection
            }
            return scanRenaultCan(deadline) ?: isoDetection
        }
        val canDetection = scanRenaultCan(deadline)
        if (canDetection != null && canDetection.hints.isNotEmpty()) {
            return canDetection
        }
        if (MonotonicClock.nowMillis() >= deadline) {
            diagnosticsSink.log("renault", "probe", "scan timeout before ISO fallback")
            return null
        }
        return scanRenaultIso(deadline)
    }

    private fun scanRenaultCan(deadlineMillis: Long): RenaultDetection? {
        if (!initCanElm()) return null

        val protocols = listOf("6", "8")
        val candidates = listOf(
            RenaultCanCandidate(txId = "7E0", rxId = "7E8", label = "engine_primary"),
            RenaultCanCandidate(txId = "7E1", rxId = "7E9", label = "engine_secondary"),
            RenaultCanCandidate(txId = "740", rxId = "760", label = "renault_01"),
            RenaultCanCandidate(txId = "742", rxId = "762", label = "renault_04"),
            RenaultCanCandidate(txId = "743", rxId = "763", label = "renault_51"),
            RenaultCanCandidate(txId = "744", rxId = "764", label = "renault_29"),
            RenaultCanCandidate(txId = "74D", rxId = "76D", label = "renault_27"),
            RenaultCanCandidate(txId = "74E", rxId = "76E", label = "renault_0E"),
            RenaultCanCandidate(txId = "750", rxId = "770", label = "renault_0F"),
            RenaultCanCandidate(txId = "751", rxId = "771", label = "renault_07")
        )

        for (sp in protocols) {
            if (MonotonicClock.nowMillis() >= deadlineMillis) {
                diagnosticsSink.log("renault", "probe", "scan timeout on CAN phase")
                return null
            }
            sendRawElmCommand("ATSP$sp")
            for (candidate in candidates) {
                if (MonotonicClock.nowMillis() >= deadlineMillis) {
                    diagnosticsSink.log("renault", "probe", "scan timeout while iterating CAN candidates")
                    return null
                }
                configureCanAddress(candidate)
                val canSanity = sendRawElmCommand("0210C0").uppercase()
                if ("CAN ERROR" in canSanity) {
                    investigationRecorder?.info(
                        "campaign",
                        "renault can sanity failed protocol=$sp candidate=${candidate.label} resp=${canSanity.take(80)}"
                    )
                    continue
                }
                val hints = probeRenaultCommands(deadlineMillis)
                if (hints.isNotEmpty()) {
                    return RenaultDetection(
                        protocol = if (sp == "6") "can500" else "can250",
                        txId = candidate.txId,
                        rxId = candidate.rxId,
                        hints = hints + setOf("candidate_${candidate.label}")
                    )
                }
            }
        }
        return null
    }

    private fun initCanElm(): Boolean {
        val sequence = listOf(
            "ATZ",
            "ATE0",
            "ATL0",
            "ATH0",
            "ATS0",
            "ATAL",
            "ATCAF0",
            "ATCFC1",
            "ATAT1",
            "ATSTFF",
            "ATSP0"
        )
        for (cmd in sequence) {
            sendRawElmCommand(cmd)
        }
        return true
    }

    private fun configureCanAddress(candidate: RenaultCanCandidate) {
        sendRawElmCommand("ATSH ${candidate.txId}")
        sendRawElmCommand("ATCRA ${candidate.rxId}")
        sendRawElmCommand("ATFCSH ${candidate.txId}")
        sendRawElmCommand("ATFCSD 30 00 00")
        sendRawElmCommand("ATFCSM 1")
    }

    private fun scanRenaultIso(deadlineMillis: Long): RenaultDetection? {
        val candidateAddresses = listOf("7A", "01", "51", "26", "2C", "24", "29", "6E", "04", "07", "27", "32")
        var softCandidate: RenaultDetection? = null
        for (address in candidateAddresses) {
            if (MonotonicClock.nowMillis() >= deadlineMillis) {
                diagnosticsSink.log("renault", "probe", "scan timeout on ISO phase")
                return softCandidate
            }
            for (initMode in listOf("fast", "slow")) {
                val initialized = initIsoElm(address, initMode)
                if (!initialized) continue
                val hints = probeRenaultCommands(deadlineMillis, address = address, initMode = initMode)
                if (hints.isNotEmpty()) {
                    return RenaultDetection(
                        protocol = "iso",
                        address = address,
                        initMode = initMode,
                        hints = hints
                    )
                }
                if (softCandidate == null) {
                    val saeProbe = sendRawElmCommand("0100").uppercase()
                    if (
                        saeProbe.contains("410000000000") ||
                        saeProbe.contains("7F0112") ||
                        saeProbe.contains("7F 01 12")
                    ) {
                        investigationRecorder?.info(
                            "campaign",
                            "renault soft ISO hint address=$address init=$initMode saeProbe=${saeProbe.take(80)}"
                        )
                        softCandidate = RenaultDetection(
                            protocol = "iso",
                            address = address,
                            initMode = initMode,
                            hints = setOf("kwp_fast_detected", "address_$address", "init_$initMode")
                        )
                    }
                }
            }
        }
        return softCandidate
    }

    private fun initIsoElm(address: String, initMode: String): Boolean {
        val initSequence = when (initMode) {
            "slow" -> listOf(
                "ATWS",
                "ATE0",
                "ATL1",
                "ATH0",
                "ATSH81${address}F1",
                "ATSW96",
                "ATIB10",
                "ATAT0",
                "ATSTFF",
                "ATSP4",
                "ATSI",
                "ATAT1"
            )

            else -> listOf(
                "ATWS",
                "ATE0",
                "ATL1",
                "ATH0",
                "ATSH81${address}F1",
                "ATSW96",
                "ATIB10",
                "ATAT0",
                "ATSTFF",
                "ATSP5",
                "ATFI",
                "ATAT1"
            )
        }
        investigationRecorder?.info(
            "campaign",
            "renault iso init address=$address mode=$initMode"
        )
        for (cmd in initSequence) {
            sendRawElmCommand(cmd)
        }
        return true
    }

    private fun probeRenaultCommands(deadlineMillis: Long, address: String? = null, initMode: String? = null): Set<String> {
        val hints = linkedSetOf<String>()
        val probeCommands = listOf("10C0", "1003", "10A0", "10B0", "22F190", "2180", "22F1A0", "2181")
        var noDataStreak = 0
        for (cmd in probeCommands) {
            if (MonotonicClock.nowMillis() >= deadlineMillis) {
                break
            }
            val response = sendRawElmCommand(cmd).uppercase()
            investigationRecorder?.info(
                "campaign",
                "renault probe cmd=$cmd address=${address ?: "-"} init=${initMode ?: "-"} resp=${response.take(120)}"
            )
            val noData = isNoDataLike(response)
            noDataStreak = if (noData) noDataStreak + 1 else 0
            if (response.contains("50C0")) hints.add("session_10C0")
            if (response.contains("5003")) hints.add("session_1003")
            if (response.contains("50A0")) hints.add("session_10A0")
            if (response.contains("50B0")) hints.add("session_10B0")
            if (response.contains("6180")) hints.add("id_2180")
            if (response.contains("6181")) hints.add("vin_2181")
            if (response.contains("62F190")) hints.add("vin_22F190")
            if (response.contains("62F1A0")) hints.add("diag_22F1A0")
            if (hints.isNotEmpty()) continue
            if (noDataStreak >= MAX_NO_DATA_STREAK) break
        }
        return hints
    }

    private fun isNoDataLike(response: String): Boolean {
        if (response.isBlank()) return true
        val normalized = response.uppercase()
        return normalized.contains("NO DATA") ||
            normalized == "?" ||
            normalized.startsWith("?") ||
            normalized.contains("UNABLE TO CONNECT")
    }

    private fun sendRawElmCommand(command: String): String {
        return runCatching {
            val startedAt = MonotonicClock.nowMillis()
            connection.clearInputBuffer()
            connection.send("$command\r".encodeToByteArray())
            val deadline = MonotonicClock.nowMillis() + 1200L
            val builder = StringBuilder()
            while (MonotonicClock.nowMillis() < deadline) {
                val chunk = connection.receive(0)
                if (chunk.isEmpty()) continue
                val text = chunk.decodeToString()
                builder.append(text)
                if (text.contains(">") || builder.contains(">")) {
                    break
                }
            }
            val response = builder.toString().trim()
            investigationRecorder?.recordCommand(
                transport = "renault",
                command = command,
                response = response,
                timeoutMs = 1200L,
                elapsedMs = MonotonicClock.nowMillis() - startedAt,
                extra = mapOf(
                    "detected_protocol" to (detectedSession?.protocol ?: ""),
                    "oem_profile" to oemProfile.id
                )
            )
            response
        }.onFailure { error ->
            Logger.d(TAG, "raw command failed cmd=$command err=${error.message}")
            investigationRecorder?.info(
                "command_failure",
                "cmd=$command reason=${error.message ?: error::class.simpleName}"
            )
        }.getOrDefault("")
    }

    /**
     * O Sirius 32 só fala esse dialeto proprietário via K-line ISO 14230 fast-init - a
     * variante CAN (protocolo "6"/"8") não tem esse dispatcher. O simulador reflete isso
     * (`_handle_sirius32_proprietary` devolve "CAN ERROR" quando `self.protocol == "6"`).
     */
    private fun isSirius32(): Boolean =
        oemProfile.id == "sirius_32" && detectedSession?.protocol == "iso"

    /**
     * SID 0x10: StartDiagnosticSession, parâmetro 0x29 ("sessão estendida"). Sem essa
     * sessão aberta, os SIDs 0x14/0x23/0x2A/0x27 respondem NRC 0x22 (conditionsNotCorrect)
     * no firmware real - por isso todo acesso a DTC no Sirius 32 abre sessão primeiro.
     */
    private suspend fun sirius32StartSession(): Boolean {
        val response = obd2Delegate.sendRawElmCommand("1029", timeoutMs = 900L).uppercase()
        return response.replace(" ", "").contains("5029")
    }

    /**
     * SID 0x2A, local ID 0x0893: única leitura de grupo confirmada que expõe os
     * registradores de status de DTC (0xFDD8=confirmado / 0xFD36=pendente), mas só a
     * palavra 0 (word_index=0). Resposta esperada: "6A 08 93 <confHi> <confLo> <pendHi>
     * <pendLo> ...". Retorna null se a sessão não abriu ou a resposta não bateu.
     */
    private suspend fun sirius32ReadStatusWords(): Pair<Int, Int>? {
        if (!sirius32StartSession()) return null
        val response = obd2Delegate.sendRawElmCommand("2A0893", timeoutMs = 900L).uppercase()
        val bytes = Regex("[0-9A-F]{2}")
            .findAll(response)
            .map { it.value.toInt(16) }
            .toList()
        val headerIndex = bytes.windowed(3, 1).indexOfFirst { it[0] == 0x6A && it[1] == 0x08 && it[2] == 0x93 }
        if (headerIndex < 0) return null
        val payload = bytes.drop(headerIndex + 3)
        if (payload.size < 4) return null
        val confirmedWord = (payload[0] shl 8) or payload[1]
        val pendingWord = (payload[2] shl 8) or payload[3]
        return confirmedWord to pendingWord
    }

    private suspend fun sirius32ReadDtcCodes(status: DtcStatus): List<DtcCode> {
        val words = sirius32ReadStatusWords() ?: return emptyList()
        val word = if (status == DtcStatus.ACTIVE) words.first else words.second
        return SIRIUS32_WORD0_DTCS
            .filter { (word and it.mask) != 0 }
            .map { DtcCode(code = it.code, description = it.description, status = status) }
    }

    /**
     * SID 0x14: ClearDiagnosticInformation, handshake real de 2 passos - 0x20 "arma" a
     * limpeza, só depois 0x6F executa (proteção contra apagar DTC por ruído na linha).
     * Sucesso confirma com "54" (0x6F) / "54 20" (arme); qualquer "7F 14 xx" é negativa.
     */
    private suspend fun sirius32ClearDtcCodes(): Boolean {
        if (!sirius32StartSession()) return false
        val armed = obd2Delegate.sendRawElmCommand("1420", timeoutMs = 900L).uppercase().replace(" ", "")
        if (!armed.contains("5420")) return false
        val executed = obd2Delegate.sendRawElmCommand("146F", timeoutMs = 900L).uppercase().replace(" ", "")
        return executed.contains("54") && !executed.contains("7F14")
    }
}
