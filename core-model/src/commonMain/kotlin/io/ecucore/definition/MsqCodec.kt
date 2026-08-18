package io.ecucore.definition

data class MsqConstant(
    val name: String,
    val units: String?,
    val digits: Int?,
    val rawText: String,
)

data class MsqPage(
    val number: Int,
    val size: Int?,
    val constants: List<MsqConstant>,
)

data class MsqDocument(
    val signature: String,
    val firmwareInfo: String?,
    val pages: List<MsqPage>,
)

/**
 * Converts named ECU constants (decoded via [PageFieldCodec] from raw page bytes) to/from the
 * TunerStudio MSQ XML tune-file format, so tunes can be interchanged with TunerStudio.
 *
 * Only the `<page number="N"><constant .../></page>` blocks that map to real ECU memory are
 * emitted/consumed - the top `<page>` block with `<pcVariable>` entries holds TunerStudio-side
 * UI preferences (not ECU data) and is intentionally left out.
 */
object MsqCodec {

    fun encode(
        signature: String,
        firmwareInfo: String?,
        pages: List<Pair<Int, List<DecodedField>>>,
        author: String = "SpeeduinoManager",
    ): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<msq xmlns=\"http://www.msefi.com/:msq\">\n")
        sb.append("    <bibliography author=\"${xmlEscape(author)}\" tuneComment=\"\" writeDate=\"\"/>\n")
        sb.append(
            "    <versionInfo fileFormat=\"5.0\" firmwareInfo=\"${xmlEscape(firmwareInfo.orEmpty())}\" " +
                "nPages=\"${pages.size}\" signature=\"${xmlEscape(signature)}\"/>\n",
        )
        pages.forEach { (pageNumber, fields) ->
            if (fields.isEmpty()) {
                sb.append("    <page number=\"$pageNumber\"/>\n")
            } else {
                sb.append("    <page number=\"$pageNumber\">\n")
                fields.forEach { decoded -> sb.append(encodeConstant(decoded)) }
                sb.append("    </page>\n")
            }
        }
        sb.append("</msq>\n")
        return sb.toString()
    }

    private fun encodeConstant(decoded: DecodedField): String {
        val field = decoded.definition
        val attrs = StringBuilder()
        field.shape?.let { shape ->
            attrs.append(" cols=\"${shape.columns}\" rows=\"${shape.rows}\"")
        }
        field.digits?.let { attrs.append(" digits=\"$it\"") }
        attrs.append(" name=\"${xmlEscape(field.name)}\"")
        field.units?.takeIf { it.isNotBlank() }?.let { attrs.append(" units=\"${xmlEscape(it)}\"") }

        val digits = field.digits ?: 2
        val text = when (val value = decoded.value) {
            is FieldValue.Numeric -> formatNumber(value.value, digits)
            is FieldValue.Label -> "\"${xmlEscape(value.value)}\""
            is FieldValue.Table ->
                "\n         " + value.values.joinToString(" ") { formatNumber(it, digits) } + " \n      "
        }
        return "        <constant$attrs>$text</constant>\n"
    }

    private fun formatNumber(value: Double, digits: Int): String {
        val safeDigits = digits.coerceIn(0, 6)
        val factor = pow10(safeDigits)
        val rounded = kotlin.math.round(value * factor) / factor
        return if (safeDigits == 0) {
            "${rounded.toLong()}.0"
        } else {
            val scaled = kotlin.math.round(rounded * factor).toLong()
            val negative = scaled < 0
            val digitsStr = kotlin.math.abs(scaled).toString().padStart(safeDigits + 1, '0')
            val intPart = digitsStr.dropLast(safeDigits)
            val fracPart = digitsStr.takeLast(safeDigits)
            (if (negative) "-" else "") + intPart + "." + fracPart
        }
    }

    private fun pow10(digits: Int): Double {
        var result = 1.0
        repeat(digits) { result *= 10.0 }
        return result
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    fun decode(xml: String): MsqDocument {
        val signature = attr("signature", xml) ?: ""
        val firmwareInfo = attr("firmwareInfo", xml)

        val pages = mutableListOf<MsqPage>()
        val openPageRegex = Regex("<page\\s+([^>]*?)/?>", RegexOption.DOT_MATCHES_ALL)
        var searchFrom = 0
        while (true) {
            val openMatch = openPageRegex.find(xml, searchFrom) ?: break
            val attrsRaw = openMatch.groupValues[1]
            val number = attr("number", attrsRaw)?.toIntOrNull()
            val size = attr("size", attrsRaw)?.toIntOrNull()
            val selfClosing = openMatch.value.trimEnd().endsWith("/>")

            if (number == null) {
                searchFrom = openMatch.range.last + 1
                continue
            }

            if (selfClosing) {
                pages += MsqPage(number, size, emptyList())
                searchFrom = openMatch.range.last + 1
            } else {
                val closeIndex = xml.indexOf("</page>", openMatch.range.last)
                if (closeIndex < 0) break
                val body = xml.substring(openMatch.range.last + 1, closeIndex)
                pages += MsqPage(number, size, parseConstants(body))
                searchFrom = closeIndex + "</page>".length
            }
        }

        return MsqDocument(signature, firmwareInfo, pages)
    }

    private fun parseConstants(body: String): List<MsqConstant> {
        val regex = Regex("<constant([^>]*)>(.*?)</constant>", RegexOption.DOT_MATCHES_ALL)
        return regex.findAll(body).map { match ->
            val attrsRaw = match.groupValues[1]
            MsqConstant(
                name = attr("name", attrsRaw) ?: "",
                units = attr("units", attrsRaw),
                digits = attr("digits", attrsRaw)?.toIntOrNull(),
                rawText = match.groupValues[2].trim(),
            )
        }.toList()
    }

    private fun attr(name: String, source: String): String? =
        Regex("$name=\"([^\"]*)\"").find(source)?.groupValues?.get(1)
}
