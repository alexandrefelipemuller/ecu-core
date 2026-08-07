package io.ecucore.definition

/**
 * Categories for failures while fetching/validating a remote .ini definition catalog entry.
 * Ported from the Android app's `classifyIniDefinitionError`, generalized so every platform's
 * definition repository (desktop's `DesktopDefinitionRepository`, iOS/Android equivalents) can
 * surface the same user-facing categories instead of a raw exception message.
 */
enum class IniCatalogErrorCategory {
    TIMEOUT,
    DNS,
    CONNECTION_FAILED,
    HASH_INVALID,
    NOT_FOUND,
    UNKNOWN,
}

/**
 * True when the currently-applied definition already matches the one that would be applied
 * again (same firmware signature and same definition id), so callers can skip a redundant
 * re-apply/re-parse. Ported from the Android app's `isAlreadyActiveDefinition` check.
 */
fun isAlreadyActiveDefinition(
    activeSignature: String?,
    activeDefinitionId: String?,
    candidateSignature: String?,
    candidateDefinitionId: String?,
): Boolean {
    if (activeSignature == null || candidateSignature == null) return false
    return activeSignature == candidateSignature && activeDefinitionId == candidateDefinitionId
}

object IniCatalogErrorClassifier {
    fun classify(error: Throwable): IniCatalogErrorCategory {
        val message = (error.message ?: "").lowercase()
        val typeName = error::class.simpleName?.lowercase() ?: ""
        return when {
            typeName.contains("timeout") || message.contains("timeout") || message.contains("timed out") ->
                IniCatalogErrorCategory.TIMEOUT
            typeName.contains("unknownhost") || message.contains("unknown host") || message.contains("dns") ->
                IniCatalogErrorCategory.DNS
            message.contains("sha256") || message.contains("hash") || message.contains("checksum") ->
                IniCatalogErrorCategory.HASH_INVALID
            message.contains("404") || message.contains("not found") ->
                IniCatalogErrorCategory.NOT_FOUND
            typeName.contains("connect") || message.contains("connection") || message.contains("refused") ->
                IniCatalogErrorCategory.CONNECTION_FAILED
            else -> IniCatalogErrorCategory.UNKNOWN
        }
    }
}
