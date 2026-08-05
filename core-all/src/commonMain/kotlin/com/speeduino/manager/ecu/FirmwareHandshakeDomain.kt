package com.speeduino.manager.ecu

import com.speeduino.manager.model.EcuCapabilities
import com.speeduino.manager.model.EcuFamily
import com.speeduino.manager.model.FirmwareEra
import com.speeduino.manager.model.UnsupportedFirmwareException
import java.util.Locale

data class FirmwareConsensus(
    val signature: String?,
    val consensusHits: Int,
)

object FirmwareHandshakeDomain {
    fun sanitizeSignature(raw: String): String {
        val asciiOnly = raw.map { ch ->
            when {
                ch == '\t' || ch == '\n' || ch == '\r' -> ' '
                ch in ' '..'~' -> ch
                else -> ' '
            }
        }.joinToString("")

        return asciiOnly
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun normalizeSignature(signature: String): String? {
        val sanitized = sanitizeSignature(signature)
        if (sanitized.isBlank()) return null

        Regex("""(?i)^speeduino\s+(\d{6})(?:\.\d+)?$""")
            .find(sanitized)
            ?.let { return "speeduino ${it.groupValues[1]}" }

        Regex("""(?i)^speeduino\s+(\d{4})\.(\d{2})$""")
            .find(sanitized)
            ?.let { return "speeduino ${it.groupValues[1]}${it.groupValues[2]}" }

        Regex("""(?i)(?:\b[a-z]*duino|speeduino)\s*[_-]?\s*(20\d{4})(?:[._-]?[a-z0-9]+)?""")
            .find(sanitized)
            ?.let { return "speeduino ${it.groupValues[1]}" }

        Regex("""(?i)(?:\b[a-z]*duino|speeduino)\s*[_-]?\s*(20\d{2})\.(\d{2})(?:[._-]?[a-z0-9]+)?""")
            .find(sanitized)
            ?.let { return "speeduino ${it.groupValues[1]}${it.groupValues[2]}" }

        Regex("""(?i)^ms3\s*format\s*([0-9]{4}\.[0-9]{2}[a-z]?)$""")
            .find(sanitized)
            ?.let { return "MS3 Format ${it.groupValues[1].uppercase(Locale.US)}" }

        Regex("""(?i)^ms2extra\s+comms([0-9a-z]+)$""")
            .find(sanitized)
            ?.let { return "MS2Extra comms${it.groupValues[1].lowercase(Locale.US)}" }

        Regex("""(?i)^ms2extra\s+megaspeed(?:\s+.*)?$""")
            .find(sanitized)
            ?.let { return "MS2Extra MegaSpeed" }

        Regex("""(?i)^ms\/extra\s+format\s+(hr_10|hr_11d)(?:\s+.*)?$""")
            .find(sanitized)
            ?.let { return "MS/Extra format ${it.groupValues[1].lowercase(Locale.US)}" }

        Regex("""(?i)^ms1\/extra\s+format\s+([0-9a-z]+)(?:\s+.*)?$""")
            .find(sanitized)
            ?.let { return "MS1/Extra format ${it.groupValues[1].lowercase(Locale.US)}" }

        if (sanitized.startsWith("rusEFI", ignoreCase = true)) {
            return sanitized
        }

        approximateSpeeduinoSignature(sanitized)?.let { return it }

        val hasDuinoToken = Regex("""(?i)\b[a-z]*duino\b""").containsMatchIn(sanitized)
        val v6 = Regex("""\b(20\d{4})\b""").find(sanitized)?.groupValues?.getOrNull(1)
        if (hasDuinoToken && !v6.isNullOrBlank()) {
            return "speeduino $v6"
        }

        val dotted = Regex("""\b(20\d{2})\.(\d{2})\b""").find(sanitized)
        if (hasDuinoToken && dotted != null) {
            return "speeduino ${dotted.groupValues[1]}${dotted.groupValues[2]}"
        }

        return null
    }

    fun resolveConsensus(samples: List<String>): FirmwareConsensus {
        val normalized = samples.mapNotNull(::normalizeSignature)
        if (normalized.isEmpty()) {
            return FirmwareConsensus(signature = null, consensusHits = 0)
        }

        val grouped = normalized.groupingBy { it }.eachCount()
        val best = grouped.maxByOrNull { it.value }
        return FirmwareConsensus(
            signature = best?.key,
            consensusHits = best?.value ?: 0,
        )
    }

    fun selectBestCandidate(candidates: List<String>): String? {
        val sanitizedCandidates = candidates
            .map(::sanitizeSignature)
            .filter(String::isNotBlank)

        sanitizedCandidates.firstNotNullOfOrNull(::normalizeSignature)?.let { return it }
        return sanitizedCandidates.firstOrNull(::looksLikeFirmwareSample)
    }

    fun validateConsensus(consensus: FirmwareConsensus, samples: List<String>) {
        val signature = consensus.signature
        if (signature != null && (samples.size == 1 || consensus.consensusHits >= 2)) {
            return
        }

        if (signature != null && isRusEfiFallbackAllowed(signature, samples)) {
            return
        }

        val errorMessage = buildString {
            appendLine("❌ Assinatura de firmware inválida/ilegível")
            appendLine()
            appendLine("Amostras recebidas:")
            samples.forEach { sample ->
                appendLine("- $sample")
            }
            appendLine()
            appendLine("Isso costuma indicar problema no canal (ruído, baud incorreto, cabo/adaptador, timeout).")
            append("Tente reconectar e verificar a conexão.")
        }
        throw UnsupportedFirmwareException(errorMessage)
    }

    fun normalizeManualProfile(signature: String): String {
        return normalizeSignature(signature)
            ?: throw UnsupportedFirmwareException("Invalid manual firmware profile: $signature")
    }

    fun shouldUseLegacyHandshakeCore(supportsModernProtocol: Boolean): Boolean = !supportsModernProtocol

    private fun looksLikeFirmwareSample(signature: String): Boolean {
        val lower = signature.lowercase(Locale.US)
        val hasVersionDigits = signature.count(Char::isDigit) >= 4
        return (lower.contains("speeduino") && hasVersionDigits) ||
            lower.startsWith("ms2extra ") ||
            lower.contains("ms3format") ||
            lower.startsWith("ms3 format ") ||
            lower.startsWith("ms/extra format hr_10") ||
            lower.startsWith("ms/extra format hr_11d") ||
            lower.startsWith("ms1/extra format") ||
            lower.startsWith("rusefi ")
    }

    private fun isRusEfiFallbackAllowed(signature: String, samples: List<String>): Boolean {
        if (!signature.startsWith("rusEFI", ignoreCase = true)) return false

        val sanitizedSamples = samples.map(::sanitizeSignature)
        val hasRusEfiSample = sanitizedSamples.any { it.startsWith("rusEFI", ignoreCase = true) }
        if (!hasRusEfiSample) return false

        return sanitizedSamples.all { sample ->
            sample.isBlank() ||
                sample.equals("Unknown", ignoreCase = true) ||
                sample.startsWith("rusEFI", ignoreCase = true)
        }
    }

    private fun approximateSpeeduinoSignature(text: String): String? {
        val version = Regex("""\b(20\d{4})\b""").find(text)?.groupValues?.getOrNull(1)
            ?: Regex("""\b(20\d{2})\.(\d{2})\b""").find(text)?.let {
                "${it.groupValues[1]}${it.groupValues[2]}"
            }

        if (version.isNullOrBlank()) return null
        if (!containsApproximateToken(text, "speeduino")) return null

        return "speeduino $version"
    }

    private fun containsApproximateToken(text: String, target: String, maxDistance: Int = 2): Boolean {
        return text.split(Regex("""[^A-Za-z0-9]+"""))
            .filter(String::isNotBlank)
            .any { token -> levenshteinDistance(token.lowercase(Locale.US), target.lowercase(Locale.US)) <= maxDistance }
    }

    private fun levenshteinDistance(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length

        val previous = IntArray(right.length + 1) { it }
        val current = IntArray(right.length + 1)

        for (i in left.indices) {
            current[0] = i + 1
            for (j in right.indices) {
                val substitutionCost = if (left[i] == right[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + substitutionCost,
                )
            }
            for (j in previous.indices) {
                previous[j] = current[j]
            }
        }

        return previous[right.length]
    }
}

data class FirmwareInfo(
    val signature: String,
    val productString: String,
    val era: FirmwareEra,
    val family: EcuFamily = EcuFamily.SPEEDUINO,
    val capabilities: EcuCapabilities = EcuCapabilities(
        supportsModernProtocol = true,
        supportsLegacyProtocol = true,
        supportsPageRead = true,
        supportsPageWrite = true,
        supportsBurn = true,
        supportsLiveData = true,
    ),
)
