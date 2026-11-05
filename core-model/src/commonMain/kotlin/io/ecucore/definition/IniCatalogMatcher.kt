package io.ecucore.definition

import kotlin.math.abs

/**
 * Catálogo de definições INI: leitura do manifest.json (sem depender de org.json) e escolha da
 * definição que melhor casa com a assinatura de firmware da ECU (exata, depois a versão mais próxima
 * por família). Compartilhado entre os apps.
 */
object IniCatalogMatcher {
    fun parseCatalog(content: String): List<IniCatalogEntry> {
        val definitionsBlock = Regex(
            """"definitions"\s*:\s*\[(.*)]""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(content)?.groupValues?.getOrNull(1) ?: return emptyList()

        return extractTopLevelObjects(definitionsBlock).mapNotNull { objectText ->
            parseCatalogEntry(objectText)
        }
    }

    private fun parseCatalogEntry(objectText: String): IniCatalogEntry? {
        val fields = Regex(
            """"([^"]+)"\s*:\s*("((?:\\.|[^"\\])*)"|true|false|null|-?\d+)""",
        ).findAll(objectText).associate { match ->
            val key = match.groupValues[1]
            val rawValue = match.groupValues[2]
            key to parseJsonScalar(rawValue)
        }

        val id = fields["id"]?.trim().orEmpty().takeIf { it.isNotBlank() } ?: return null
        val family = fields["family"]?.trim().orEmpty().takeIf { it.isNotBlank() } ?: return null
        val signaturePattern = fields["signature_pattern"]?.trim().orEmpty().takeIf { it.isNotBlank() } ?: return null
        val version = fields["version"]?.trim().orEmpty().ifBlank { "unknown" }
        val url = fields["url"]?.trim().orEmpty().takeIf { it.isNotBlank() } ?: return null
        val sha256 = fields["sha256"]?.trim().orEmpty().takeIf { it.isNotBlank() } ?: return null
        val board = fields["board"]?.trim().orEmpty().ifBlank { "generic" }

        return IniCatalogEntry(
            id = id,
            family = family,
            board = board,
            signaturePattern = signaturePattern,
            version = version,
            url = url,
            sha256 = sha256,
            priority = fields["priority"]?.toIntOrNull() ?: 100,
            bundled = fields["bundled"]?.toBooleanStrictOrNull() ?: false,
            minAppVersion = fields["min_app_version"]?.toIntOrNull(),
            localAssetPath = fields["local_asset_path"],
        )
    }

    private fun parseJsonScalar(rawValue: String): String {
        return when {
            rawValue == "null" -> ""
            rawValue == "true" || rawValue == "false" -> rawValue
            rawValue.startsWith("\"") && rawValue.endsWith("\"") && rawValue.length >= 2 -> {
                unescapeJsonString(rawValue.substring(1, rawValue.length - 1))
            }
            else -> rawValue
        }
    }

    private fun unescapeJsonString(value: String): String {
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char != '\\' || index == value.lastIndex) {
                result.append(char)
                index++
                continue
            }

            val next = value[index + 1]
            when (next) {
                '"', '\\', '/' -> result.append(next)
                'b' -> result.append('\b')
                'f' -> result.append('\u000C')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    val hex = value.substring(index + 2, minOf(index + 6, value.length))
                    result.append(hex.toIntOrNull(16)?.toChar() ?: 'u')
                    index += 4
                }
                else -> result.append(next)
            }
            index += 2
        }
        return result.toString()
    }

    private class TopLevelObjectScanState {
        var depth = 0
        var inString = false
        var escaped = false
        var startIndex = -1
    }

    private fun scanStringChar(state: TopLevelObjectScanState, char: Char) {
        when {
            state.escaped -> state.escaped = false
            char == '\\' -> state.escaped = true
            char == '"' -> state.inString = false
        }
    }

    private fun scanStructuralChar(
        state: TopLevelObjectScanState,
        char: Char,
        index: Int,
        value: String,
        objects: MutableList<String>,
    ) {
        when (char) {
            '"' -> state.inString = true
            '{' -> {
                if (state.depth == 0) {
                    state.startIndex = index
                }
                state.depth++
            }
            '}' -> {
                state.depth--
                if (state.depth == 0 && state.startIndex >= 0) {
                    objects += value.substring(state.startIndex, index + 1)
                    state.startIndex = -1
                }
            }
        }
    }

    private fun extractTopLevelObjects(value: String): List<String> {
        val objects = mutableListOf<String>()
        val state = TopLevelObjectScanState()

        value.forEachIndexed { index, char ->
            if (state.inString) {
                scanStringChar(state, char)
            } else {
                scanStructuralChar(state, char, index, value, objects)
            }
        }

        return objects
    }

    fun findBestMatchingEntry(
        signature: String,
        entries: List<IniCatalogEntry>,
    ): IniCatalogEntry? {
        val exact = entries
            .filter { matchesSignature(signature, it.signaturePattern) }
            .maxByOrNull { it.priority }
        if (exact != null) return exact

        return findNearestSpeeduinoEntry(signature, entries)
            ?: findNearestMs2Entry(signature, entries)
            ?: findNearestMs3Entry(signature, entries)
            ?: findNearestRusefiEntry(signature, entries)
    }

    fun matchesSignature(signature: String, pattern: String): Boolean {
        val normalizedSignature = normalizeForMatch(signature)
        // Espaços colados a um '*' (ex.: "MS/Extra format hr_10 **********") vêm de assinaturas brutas de ECU com
        // sufixo de versão/checksum. A assinatura já normalizada não inclui esse sufixo nem o espaço à frente dele;
        // tirar o espaço da fronteira do wildcard torna o sufixo opcional.
        val normalizedPattern = normalizeForMatch(pattern).replace(Regex("\\s*\\*\\s*"), "*")
        val wildcardRegex = normalizedPattern
            .split('*')
            .joinToString(".*") { Regex.escape(it) }
        val regex = Regex("^$wildcardRegex$", RegexOption.IGNORE_CASE)
        return regex.matches(normalizedSignature)
    }

    private fun findNearestSpeeduinoEntry(
        signature: String,
        entries: List<IniCatalogEntry>,
    ): IniCatalogEntry? {
        val targetVersion = extractSpeeduinoVersion(signature) ?: return null
        val candidates = entries.mapNotNull { entry ->
            val version = extractSpeeduinoVersion(entry.signaturePattern) ?: return@mapNotNull null
            entry to version
        }
        if (candidates.isEmpty()) return null

        val olderOrEqual = candidates
            .filter { (_, version) -> version <= targetVersion }
            .maxWithOrNull(compareBy<Pair<IniCatalogEntry, Int>> { it.second }.thenBy { it.first.priority })
        if (olderOrEqual != null) return olderOrEqual.first

        return candidates
            .minWithOrNull(compareBy<Pair<IniCatalogEntry, Int>> { it.second }.thenByDescending { it.first.priority })
            ?.first
    }

    private fun findNearestMs2Entry(
        signature: String,
        entries: List<IniCatalogEntry>,
    ): IniCatalogEntry? {
        val normalizedSignature = normalizeForMatch(signature)
        if (normalizedSignature.startsWith("ms2extra megaspeed")) {
            return entries
                .filter { it.signaturePattern.startsWith("MS2Extra MegaSpeed", ignoreCase = true) }
                .maxByOrNull { it.priority }
        }

        val target = extractMs2Code(normalizedSignature) ?: return null
        val candidates = entries.mapNotNull { entry ->
            val candidate = extractMs2Code(entry.signaturePattern) ?: return@mapNotNull null
            entry to candidate
        }
        if (candidates.isEmpty()) return null

        candidates
            .filter { (_, candidate) -> candidate == target }
            .maxByOrNull { it.first.priority }
            ?.let { return it.first }

        val targetFamily = target.takeWhile(Char::isDigit)
        val sameFamily = candidates.filter { (_, candidate) ->
            targetFamily.isNotBlank() && candidate.startsWith(targetFamily)
        }
        val pool = sameFamily.ifEmpty { candidates }

        return pool
            .maxWithOrNull(
                compareBy<Pair<IniCatalogEntry, String>> { commonPrefixLength(target, it.second) }
                    .thenBy { -abs(extractFirstNumber(target) - extractFirstNumber(it.second)) }
                    .thenBy { it.first.priority }
            )
            ?.first
    }

    private fun findNearestMs3Entry(
        signature: String,
        entries: List<IniCatalogEntry>,
    ): IniCatalogEntry? {
        val target = extractMs3Version(signature) ?: return null
        val candidates = entries.mapNotNull { entry ->
            val candidate = extractMs3Version(entry.signaturePattern) ?: return@mapNotNull null
            entry to candidate
        }
        if (candidates.isEmpty()) return null

        val targetBoardHint = ms3BoardHint(target.suffix)
        val boardFiltered = if (targetBoardHint != null) {
            candidates.filter { (entry, _) -> normalizeBoard(entry.board) == targetBoardHint }
        } else {
            candidates.filter { (entry, _) -> normalizeBoard(entry.board) == "ms3" }
        }
        val pool = boardFiltered.ifEmpty { candidates }

        return pool
            .minWithOrNull(
                compareBy<Pair<IniCatalogEntry, Ms3Version>> { abs(ms3VersionScore(target) - ms3VersionScore(it.second)) }
                    .thenByDescending { commonPrefixLength(target.raw, it.second.raw) }
                    .thenByDescending { it.first.priority }
            )
            ?.first
    }

    private fun findNearestRusefiEntry(
        signature: String,
        entries: List<IniCatalogEntry>,
    ): IniCatalogEntry? {
        if (!signature.trim().startsWith("rusEFI", ignoreCase = true)) return null

        val normalizedSignature = normalizeForBoardMatch(signature)
        val candidates = entries.filter { it.family.equals("rusefi", ignoreCase = true) }
        if (candidates.isEmpty()) return null

        candidates
            .filter { normalizedSignature.contains(normalizeForBoardMatch(it.board)) }
            .maxWithOrNull(
                compareBy<IniCatalogEntry> { normalizeForBoardMatch(it.board).length }
                    .thenBy { it.priority }
            )
            ?.let { return it }

        val signatureTokens = normalizedSignature.split(' ').filter { it.isNotBlank() }
        return candidates
            .mapNotNull { entry ->
                val board = normalizeForBoardMatch(entry.board)
                val bestTokenScore = signatureTokens.maxOfOrNull { token ->
                    commonPrefixLength(board, token)
                } ?: 0
                if (bestTokenScore < 4) return@mapNotNull null
                entry to bestTokenScore
            }
            .maxWithOrNull(
                compareBy<Pair<IniCatalogEntry, Int>> { it.second }
                    .thenBy { normalizeForBoardMatch(it.first.board).length }
                    .thenBy { it.first.priority }
            )
            ?.first
    }

    private fun normalizeForMatch(value: String): String {
        return value
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase()
    }

    private fun extractSpeeduinoVersion(value: String): Int? {
        val normalized = normalizeForMatch(value)
        Regex("""\bspeeduino\s+(\d{6})(?:[a-z][a-z0-9]*)?(?:\.\d+)?\b""")
            .find(normalized)
            ?.let { return it.groupValues[1].toIntOrNull() }
        Regex("""\bspeeduino\s+(\d{4})\.(\d{2})\b""")
            .find(normalized)
            ?.let { return "${it.groupValues[1]}${it.groupValues[2]}".toIntOrNull() }
        return null
    }

    private fun extractMs2Code(value: String): String? {
        return Regex("""\bms2extra\s+comms([0-9a-z]+)""", RegexOption.IGNORE_CASE)
            .find(normalizeForMatch(value))
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase()
    }

    private data class Ms3Version(
        val major: Int,
        val minor: Int,
        val suffix: String,
        val raw: String,
    )

    private fun extractMs3Version(value: String): Ms3Version? {
        val match = Regex("""\bms3\s*format\s*(\d{4})\.(\d{2})([a-z0-9]*)""", RegexOption.IGNORE_CASE)
            .find(normalizeForMatch(value))
            ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: return null
        val suffix = match.groupValues.getOrNull(3).orEmpty().lowercase().trimEnd('*')
        return Ms3Version(
            major = major,
            minor = minor,
            suffix = suffix,
            raw = "$major.$minor$suffix",
        )
    }

    private fun ms3VersionScore(version: Ms3Version): Int {
        return version.major * 100 + version.minor
    }

    private fun ms3BoardHint(suffix: String): String? {
        return when (suffix.lowercase()) {
            "p" -> "ms3pro"
            "168" -> "ms3pro-comp"
            "e" -> "ms3pro-evo"
            "i" -> "ms3pro-mini"
            "m" -> "ms3pro-plus"
            "u" -> "ms3pro-ult"
            else -> null
        }
    }

    private fun normalizeBoard(board: String): String {
        return normalizeForMatch(board).replace('_', '-')
    }

    private fun normalizeForBoardMatch(value: String): String {
        return value
            .trim()
            .replace(Regex("[^a-zA-Z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .lowercase()
    }

    private fun commonPrefixLength(left: String, right: String): Int {
        val max = minOf(left.length, right.length)
        var index = 0
        while (index < max && left[index] == right[index]) {
            index++
        }
        return index
    }

    private fun extractFirstNumber(value: String): Int {
        return Regex("""\d+""").find(value)?.value?.toIntOrNull() ?: 0
    }
}
