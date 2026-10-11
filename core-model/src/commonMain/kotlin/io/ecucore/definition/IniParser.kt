package io.ecucore.definition

object IniParser {

    fun parse(sourceName: String, text: String): IniDefinition {
        val lines = text.lines()
        val macros = collectMacros(lines)
        val expressionVariables = collectExpressionVariables(lines)
        var lastFieldOffset: Int? = null
        var currentSection = ""
        var currentPage: Int? = null
        var signature: String? = null
        var queryCommand: String? = null
        var versionInfoCommand: String? = null
        var nPages: Int? = null
        var ochBlockSize: Int? = null
        val pageSizes = mutableListOf<Int>()
        val pageIdentifiers = mutableListOf<String>()
        val tables = mutableListOf<IniTableDefinition>()
        val outputChannels = mutableListOf<IniOutputChannelDefinition>()
        val fields = mutableListOf<IniFieldDefinition>()

        lines.forEach { rawLine ->
            val line = stripComment(rawLine).trim()
            if (line.isEmpty()) {
                return@forEach
            }

            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.removePrefix("[").removeSuffix("]").trim()
                currentPage = null
                return@forEach
            }

            val entry = parseAssignment(line) ?: return@forEach
            val key = entry.first
            val value = entry.second

            when (currentSection.lowercase()) {
                "megatune", "tunerstudio" -> {
                    when (key.lowercase()) {
                        "signature" -> if (signature == null) signature = unquote(value)
                        "querycommand" -> if (queryCommand == null) queryCommand = unquote(value)
                        "versioninfo" -> if (versionInfoCommand == null) versionInfoCommand = unquote(value)
                    }
                }

                "constants" -> {
                    when (key.lowercase()) {
                        "npages" -> nPages = value.toIntOrNull()
                        "pagesize" -> pageSizes += splitTopLevel(value).mapNotNull { it.trim().toIntOrNull() }
                        "pageidentifier" -> pageIdentifiers += splitTopLevel(value).map { unquote(it.trim()) }
                        "ochblocksize" -> ochBlockSize = value.toIntOrNull()
                        "page" -> currentPage = value.toIntOrNull()
                        else -> {
                            parseFieldDefinition(
                                name = key,
                                rawDefinition = value,
                                section = currentSection,
                                page = currentPage,
                                macros = macros,
                                lastOffset = lastFieldOffset,
                            )?.let { field ->
                                fields += field
                                field.offset?.let { lastFieldOffset = it }
                                field.shape?.takeIf { it.isTable }?.let { shape ->
                                    field.offset?.let { offset ->
                                        tables += IniTableDefinition(
                                            name = field.name,
                                            dataType = field.dataType,
                                            offset = offset,
                                            shape = shape,
                                            units = field.units,
                                            scale = field.scale,
                                            translate = field.translate,
                                            section = field.section,
                                            page = field.page,
                                            rawDefinition = field.rawDefinition,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                "outputchannels" -> {
                    when (key.lowercase()) {
                        "ochblocksize" -> ochBlockSize = value.toIntOrNull()
                        else -> {
                            parseFieldDefinition(
                                name = key,
                                rawDefinition = value,
                                section = currentSection,
                                page = currentPage,
                                macros = macros,
                                lastOffset = null,
                            )?.let { field ->
                                outputChannels += IniOutputChannelDefinition(
                                    name = field.name,
                                    kind = field.kind,
                                    dataType = field.dataType,
                                    offset = field.offset,
                                    shape = field.shape,
                                    units = field.units,
                                    scale = field.scale,
                                    translate = field.translate,
                                    rawDefinition = field.rawDefinition,
                                )
                            }
                        }
                    }
                }
            }
        }

        val resolvedPages = buildPageDefinitions(pageSizes, pageIdentifiers)
        val resolvedSignature = signature ?: error("INI sem signature valida em $sourceName")

        return IniDefinition(
            sourceName = sourceName,
            signature = resolvedSignature,
            family = inferFamily(resolvedSignature),
            queryCommand = queryCommand,
            versionInfoCommand = versionInfoCommand,
            nPages = nPages,
            pageDefinitions = resolvedPages,
            ochBlockSize = ochBlockSize,
            tableDefinitions = tables,
            outputChannels = outputChannels,
            fields = fields,
            expressionVariables = expressionVariables,
        )
    }

    private fun buildPageDefinitions(sizes: List<Int>, identifiers: List<String>): List<IniPageDefinition> {
        val count = maxOf(sizes.size, identifiers.size)
        return (0 until count).map { index ->
            val identifierRaw = identifiers.getOrNull(index)
            IniPageDefinition(
                index = index,
                identifierRaw = identifierRaw,
                resolvedId = identifierRaw?.let(::decodeIdentifier),
                size = sizes.getOrNull(index),
            )
        }
    }

    private fun parseFieldDefinition(
        name: String,
        rawDefinition: String,
        section: String,
        page: Int?,
        macros: Map<String, String> = emptyMap(),
        lastOffset: Int? = null,
    ): IniFieldDefinition? {
        val tokens = splitTopLevel(rawDefinition)
        if (tokens.size < 2) {
            return null
        }

        val kind = parseKind(tokens[0].trim())
        val dataType = tokens[1].trim()
        val offsetToken = tokens.getOrNull(2)?.trim()
        // `lastOffset` = mesmo offset do campo anterior (ex.: afrTable sobrepõe lambdaTable).
        val offset = offsetToken?.toIntOrNull() ?: if (offsetToken == "lastOffset") lastOffset else null

        if (kind == IniFieldKind.BITS) {
            val bitRange = tokens.getOrNull(3)?.let(::parseBitRange)
            // Rótulos como `$loadSourceNames` são macros `#define`: expandir, senão o campo fica
            // sem enumLabels e nenhum valor de MSQ consegue ser gravado nele.
            val labelTokens = tokens.drop(4).flatMap { token ->
                val trimmed = token.trim()
                if (trimmed.startsWith("$")) splitTopLevel(expandMacros(trimmed, macros)) else listOf(token)
            }
            val unresolvedMacro = labelTokens.any { it.trim().startsWith("$") }
            val labels = if (unresolvedMacro) {
                null
            } else {
                labelTokens.map(::cleanValueToken).ifEmpty { null }
            }
            return IniFieldDefinition(
                name = name.trim(),
                kind = kind,
                dataType = dataType,
                offset = offset,
                bitLow = bitRange?.first,
                bitHigh = bitRange?.second,
                enumLabels = labels,
                section = section,
                page = page,
                rawDefinition = rawDefinition.trim(),
            )
        }

        val shapeIndex = tokens.indexOfFirst { it.contains("[") && it.contains("]") }
        val shape = if (shapeIndex >= 0) parseShape(tokens[shapeIndex]) else null

        val unitsIndex = if (shape != null) 4 else 3
        val scaleIndex = if (shape != null) 5 else 4
        val translateIndex = if (shape != null) 6 else 5
        val digitsIndex = if (shape != null) 9 else 8

        return IniFieldDefinition(
            name = name.trim(),
            kind = kind,
            dataType = dataType,
            offset = offset,
            shape = shape,
            units = tokens.getOrNull(unitsIndex)?.let(::cleanValueToken),
            scale = tokens.getOrNull(scaleIndex)?.trim()?.toDoubleOrNull(),
            translate = tokens.getOrNull(translateIndex)?.trim()?.toDoubleOrNull(),
            scaleExpr = tokens.getOrNull(scaleIndex)?.let(::expressionBody),
            translateExpr = tokens.getOrNull(translateIndex)?.let(::expressionBody),
            digits = tokens.getOrNull(digitsIndex)?.trim()?.toIntOrNull(),
            section = section,
            page = page,
            rawDefinition = rawDefinition.trim(),
        )
    }

    private fun parseBitRange(token: String): Pair<Int, Int>? {
        val raw = token.trim().removePrefix("[").removeSuffix("]")
        val pieces = raw.split(":")
        val lo = pieces.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val hi = pieces.getOrNull(1)?.trim()?.toIntOrNull() ?: lo
        return lo to hi
    }

    /** Corpo de um token `{ expr }`, ou null se o token não é uma expressão entre chaves. */
    private fun expressionBody(token: String): String? {
        val trimmed = token.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        return trimmed.removePrefix("{").removeSuffix("}").trim().ifEmpty { null }
    }

    /** Coleta `nome = { expr }` (ex.: `fuelLoadRes = { ... }`), usadas como `{fuelLoadRes}` em escalas. */
    private fun collectExpressionVariables(lines: List<String>): Map<String, String> {
        val variables = LinkedHashMap<String, String>()
        val regex = Regex("^\\s*(\\w+)\\s*=\\s*\\{(.*)\\}\\s*$")
        lines.forEach { raw ->
            val match = regex.find(stripSemicolonComment(raw)) ?: return@forEach
            variables[match.groupValues[1]] = match.groupValues[2].trim()
        }
        return variables
    }

    /** Coleta `#define nome = valor` (valores podem referenciar outras macros via `$outra`). */
    private fun collectMacros(lines: List<String>): Map<String, String> {
        val macros = LinkedHashMap<String, String>()
        val defineRegex = Regex("^\\s*#define\\s+(\\w+)\\s*=\\s*(.*)$")
        lines.forEach { raw ->
            val match = defineRegex.find(stripSemicolonComment(raw)) ?: return@forEach
            macros[match.groupValues[1]] = match.groupValues[2].trim()
        }
        return macros
    }

    /** Remove só comentários `;` (fora de aspas); `#` aqui é diretiva, não comentário. */
    private fun stripSemicolonComment(line: String): String {
        var inQuotes = false
        for (index in line.indices) {
            val char = line[index]
            if (char == '"') inQuotes = !inQuotes else if (char == ';' && !inQuotes) return line.substring(0, index)
        }
        return line
    }

    /** Expande `$nome` recursivamente (com limite de profundidade contra ciclos). */
    private fun expandMacros(value: String, macros: Map<String, String>, depth: Int = 0): String {
        if (depth > 8 || !value.contains('$')) return value
        val refRegex = Regex("\\$(\\w+)")
        return refRegex.replace(value) { match ->
            val body = macros[match.groupValues[1]] ?: return@replace match.value
            expandMacros(body, macros, depth + 1)
        }
    }

    private fun parseAssignment(line: String): Pair<String, String>? {
        var inQuotes = false
        for (index in line.indices) {
            val char = line[index]
            if (char == '"') {
                inQuotes = !inQuotes
            } else if (char == '=' && !inQuotes) {
                return line.substring(0, index).trim() to line.substring(index + 1).trim()
            }
        }
        return null
    }

    private fun stripComment(line: String): String {
        var inQuotes = false
        for (index in line.indices) {
            val char = line[index]
            if (char == '"') {
                inQuotes = !inQuotes
            } else if ((char == ';' || char == '#') && !inQuotes) {
                return line.substring(0, index)
            }
        }
        return line
    }

    private fun splitTopLevel(value: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var bracketDepth = 0
        var braceDepth = 0
        var parenDepth = 0

        value.forEach { char ->
            when (char) {
                '"' -> {
                    inQuotes = !inQuotes
                    current.append(char)
                }
                '[' -> {
                    bracketDepth++
                    current.append(char)
                }
                ']' -> {
                    bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                    current.append(char)
                }
                '{' -> {
                    braceDepth++
                    current.append(char)
                }
                '}' -> {
                    braceDepth = (braceDepth - 1).coerceAtLeast(0)
                    current.append(char)
                }
                '(' -> {
                    parenDepth++
                    current.append(char)
                }
                ')' -> {
                    parenDepth = (parenDepth - 1).coerceAtLeast(0)
                    current.append(char)
                }
                ',' -> {
                    if (!inQuotes && bracketDepth == 0 && braceDepth == 0 && parenDepth == 0) {
                        parts += current.toString().trim()
                        current.clear()
                    } else {
                        current.append(char)
                    }
                }
                else -> current.append(char)
            }
        }

        if (current.isNotEmpty()) {
            parts += current.toString().trim()
        }
        return parts.filter { it.isNotEmpty() }
    }

    private fun parseKind(token: String): IniFieldKind {
        return when (token.trim().lowercase()) {
            "scalar" -> IniFieldKind.SCALAR
            "array" -> IniFieldKind.ARRAY
            "bits" -> IniFieldKind.BITS
            "string" -> IniFieldKind.STRING
            else -> IniFieldKind.UNKNOWN
        }
    }

    private fun parseShape(token: String): IniShape? {
        val raw = token.trim().removePrefix("[").removeSuffix("]")
        val pieces = raw.split("x")
        val rows = pieces.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val columns = pieces.getOrNull(1)?.trim()?.toIntOrNull() ?: 1
        return IniShape(rows = rows, columns = columns)
    }

    private fun cleanValueToken(token: String): String {
        val trimmed = token.trim()
        return unquote(trimmed).removePrefix("{").removeSuffix("}")
    }

    private fun unquote(value: String): String {
        return value.trim().removePrefix("\"").removeSuffix("\"")
    }

    private fun decodeIdentifier(raw: String): Int? {
        val matches = Regex("""\\x([0-9A-Fa-f]{2})""").findAll(raw).map { it.groupValues[1].toInt(16) }.toList()
        if (matches.isEmpty()) {
            return null
        }
        return matches.withIndex().sumOf { (index, byte) -> byte shl (index * 8) }
    }

    private fun inferFamily(signature: String): String {
        val normalized = signature.trim().lowercase()
        return when {
            normalized.startsWith("rusefi") -> "rusefi"
            normalized.startsWith("speeduino") -> "speeduino"
            normalized.startsWith("ms2extra") || normalized.startsWith("ms3") -> "mega"
            else -> "unknown"
        }
    }
}
