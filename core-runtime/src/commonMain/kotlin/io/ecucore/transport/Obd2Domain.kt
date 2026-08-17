package io.ecucore.transport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Obd2OptimizationFeature(
    val bitIndex: Int,
    val displayName: String,
) {
    DISABLE_SPACES(bitIndex = 0, displayName = "Disable spaces"),
    FIXED_PROTOCOL(bitIndex = 1, displayName = "Fixed protocol"),
    PRIORITY_SCHEDULER(bitIndex = 2, displayName = "Priority scheduler"),
    FAST_TIMEOUT(bitIndex = 3, displayName = "Fast timeout"),
    TIGHT_READ_LOOP(bitIndex = 4, displayName = "Tight read loop"),
    FAST_POLL_INTERVAL(bitIndex = 5, displayName = "Fast poll interval"),
    MULTI_PID_EXPERIMENTAL(bitIndex = 6, displayName = "Multi-PID experimental"),
}

data class Obd2OptimizationStatus(
    val appliedMask: Int,
    val testedCount: Int,
    val totalCount: Int,
    val currentFeature: Obd2OptimizationFeature?,
    val message: String,
    val testing: Boolean,
    val fallbackOccurred: Boolean = false,
) {
    val progressFraction: Float
        get() = if (totalCount <= 0) 0f else testedCount.toFloat() / totalCount.toFloat()
}

object Obd2OptimizationStatusBus {
    private val _state = MutableStateFlow<Obd2OptimizationStatus?>(null)
    val state: StateFlow<Obd2OptimizationStatus?> = _state.asStateFlow()

    fun publish(status: Obd2OptimizationStatus) {
        _state.value = status
    }

    fun clear() {
        _state.value = null
    }
}

enum class VehicleBrandHint {
    UNKNOWN,
    PSA,
    RENAULT,
    VAG,
}

data class Obd2PersistedProfile(
    val mask: Int,
    val supportedPids: Set<Int>,
    val preferredO2Pid: Int?,
)

interface Obd2ProfileStore {
    fun load(profileKey: String): Obd2PersistedProfile?

    fun save(profileKey: String, profile: Obd2PersistedProfile)
}

data class PsaPersistedSession(
    val protocol: String,
    val txId: String,
    val rxId: String,
    val hintsCsv: String,
    val oemProfileId: String,
    val functionalHeader: String,
    val c4LiveModeEnabled: Boolean,
    val isFunctional: Boolean,
    val timestampMs: Long,
)

interface PsaSessionStore {
    fun load(): PsaPersistedSession?

    fun save(session: PsaPersistedSession)

    fun clear()
}

fun Set<Obd2OptimizationFeature>.toMask(): Int {
    var mask = 0
    for (feature in this) {
        mask = mask or (1 shl feature.bitIndex)
    }
    return mask
}

fun featuresFromMask(mask: Int): Set<Obd2OptimizationFeature> {
    return Obd2OptimizationFeature.entries
        .filter { feature -> (mask and (1 shl feature.bitIndex)) != 0 }
        .toSet()
}

fun resolveObd2ProfileKey(
    connectionInfo: String,
    vin: String?,
    calibrationId: String?,
): String {
    val trimmedConnection = connectionInfo.trim().ifBlank { "unknown" }
    if (!vin.isNullOrBlank()) {
        return if (calibrationId != null) {
            "vin:$vin|cal:$calibrationId"
        } else {
            "vin:$vin"
        }
    }
    if (!calibrationId.isNullOrBlank()) {
        return "cal:$calibrationId|conn:$trimmedConnection"
    }
    return "conn:$trimmedConnection"
}

fun classifyVehicleBrandHint(vin: String?, calibrationId: String?): VehicleBrandHint {
    val vinUpper = vin.orEmpty().uppercase()
    val calibrationUpper = calibrationId.orEmpty().uppercase()
    return when {
        vinUpper.startsWith("VF7") ||
            vinUpper.startsWith("VF3") ||
            vinUpper.startsWith("VR7") ||
            vinUpper.startsWith("VR3") ||
            calibrationUpper.contains("PSA") ||
            calibrationUpper.contains("PEUGEOT") ||
            calibrationUpper.contains("CITROEN") ||
            calibrationUpper.contains("DS") -> VehicleBrandHint.PSA
        vinUpper.startsWith("VF1") ||
            calibrationUpper.contains("RENAULT") ||
            calibrationUpper.contains("DACIA") -> VehicleBrandHint.RENAULT
        vinUpper.startsWith("WVW") ||
            vinUpper.startsWith("WAU") ||
            vinUpper.startsWith("VSS") ||
            vinUpper.startsWith("SKZ") ||
            calibrationUpper.contains("VAG") ||
            calibrationUpper.contains("VOLKSWAGEN") ||
            calibrationUpper.contains("AUDI") ||
            calibrationUpper.contains("SEAT") ||
            calibrationUpper.contains("SKODA") -> VehicleBrandHint.VAG
        else -> VehicleBrandHint.UNKNOWN
    }
}

fun shouldPromoteToPsa(vehicleBrandHint: VehicleBrandHint): Boolean = vehicleBrandHint == VehicleBrandHint.PSA

enum class DtcStatus {
    ACTIVE,
    PENDING,
}

data class DtcCode(
    val code: String,
    val description: String? = null,
    val status: DtcStatus,
)

/**
 * Decodifica um par de bytes de DTC (SAE J2012 / OBD2 Mode 03/07/0A) no formato textual padrão
 * (ex.: "P0133"). byte0 carrega a letra (bits 7-6) e o primeiro dígito (bits 5-4) mais o segundo
 * dígito (bits 3-0); byte1 carrega o terceiro e quarto dígitos (nibble alto/baixo).
 */
fun decodeDtc(byte0: Int, byte1: Int): String {
    val letter = when ((byte0 shr 6) and 0x3) {
        0 -> 'P'
        1 -> 'C'
        2 -> 'B'
        else -> 'U'
    }
    val digit0 = (byte0 shr 4) and 0x3
    val digit1 = byte0 and 0xF
    val digit2 = (byte1 shr 4) and 0xF
    val digit3 = byte1 and 0xF
    return buildString {
        append(letter)
        append(digit0.toString(16).uppercase())
        append(digit1.toString(16).uppercase())
        append(digit2.toString(16).uppercase())
        append(digit3.toString(16).uppercase())
    }
}

/**
 * Extrai os DTCs de uma resposta ELM327 a Mode 03 (ativos, header 0x43) ou Mode 07 (pendentes,
 * header 0x47). Cada par de bytes após o header vira um código; pares "00 00" são ignorados
 * (slots vazios/terminador, comportamento típico de adaptadores ELM327).
 */
fun parseDtcResponse(response: String, status: DtcStatus): List<DtcCode> {
    val normalized = response.uppercase()
    if ("NO DATA" in normalized) return emptyList()
    val headerByte = if (status == DtcStatus.ACTIVE) 0x43 else 0x47
    val bytes = Regex("[0-9A-F]{2}")
        .findAll(normalized)
        .map { it.value.toInt(16) }
        .toList()
    val headerIndex = bytes.indexOf(headerByte)
    if (headerIndex < 0) return emptyList()
    val dataBytes = bytes.subList(headerIndex + 1, bytes.size)
    val codes = mutableListOf<DtcCode>()
    var index = 0
    while (index + 1 < dataBytes.size) {
        val byte0 = dataBytes[index]
        val byte1 = dataBytes[index + 1]
        if (byte0 != 0 || byte1 != 0) {
            val code = decodeDtc(byte0, byte1)
            codes.add(DtcCode(code = code, description = DtcDescriptions.lookup(code), status = status))
        }
        index += 2
    }
    return codes
}

/**
 * Verifica se a resposta a um comando Mode 04 (clear DTCs) indica sucesso. A ECU confirma com
 * "44" (positive response, sem dados extras); uma resposta negativa vem como "7F 04 xx".
 */
fun isDtcClearAcknowledged(response: String): Boolean {
    val normalized = response.uppercase()
    if ("NO DATA" in normalized || "ERROR" in normalized) return false
    val bytes = Regex("[0-9A-F]{2}")
        .findAll(normalized)
        .map { it.value.toInt(16) }
        .toList()
    if (bytes.isEmpty()) return false
    val negativeResponse = bytes.windowed(2, 1).any { it[0] == 0x7F && it[1] == 0x04 }
    if (negativeResponse) return false
    return bytes.contains(0x44)
}
