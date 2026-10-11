package io.ecucore.definition

import kotlin.math.round
import kotlin.math.roundToLong

sealed class FieldValue {
    data class Numeric(val value: Double) : FieldValue()
    data class Label(val value: String) : FieldValue()
    data class Table(val rows: Int, val columns: Int, val values: List<Double>) : FieldValue()
}

data class DecodedField(
    val definition: IniFieldDefinition,
    val value: FieldValue,
)

/**
 * Decodes/encodes named ECU constants (from an [IniDefinition]) against the raw bytes of a
 * single config page - the same page bytes already produced by ConfigManager.downloadPages.
 * Used to bridge the generic .ini field catalog with a full-tune MSQ export/import.
 */
object PageFieldCodec {

    fun decodePage(definition: IniDefinition, page: Int, bytes: ByteArray): List<DecodedField> =
        decodePage(definition, page, bytes, ExpressionContext(definition, mapOf(page to bytes)))

    private fun decodePage(
        definition: IniDefinition,
        page: Int,
        bytes: ByteArray,
        context: ExpressionContext,
    ): List<DecodedField> {
        return definition.fields
            .filter { it.page == page && it.offset != null }
            .mapNotNull { field -> decodeField(field, bytes, context) }
    }

    /** Decodes every page present in [pageBytes] (keyed by page number) into named field values. */
    fun decodeAllPages(definition: IniDefinition, pageBytes: Map<Int, ByteArray>): List<Pair<Int, List<DecodedField>>> =
        ExpressionContext(definition, pageBytes).let { context ->
            pageBytes.keys.sorted().map { page -> page to decodePage(definition, page, pageBytes.getValue(page), context) }
        }

    /**
     * Applies every `<constant>` from a parsed [MsqDocument] onto the matching page bytes in
     * [pageBytes], mutating the byte arrays in place. Constants whose page isn't present in
     * [pageBytes], or whose name doesn't match a field of [definition] on that page, are skipped.
     */
    fun applyMsqDocument(definition: IniDefinition, doc: MsqDocument, pageBytes: Map<Int, ByteArray>) {
        val context = ExpressionContext(definition, pageBytes)
        // Duas fases: campos de escala fixa primeiro (inclui `algorithm` & cia.), depois os de
        // escala dinâmica `{expr}`, que dependem do valor já gravado dos primeiros.
        for (dynamicPhase in listOf(false, true)) {
            doc.pages.forEach { msqPage ->
                val bytes = pageBytes[msqPage.number] ?: return@forEach
                msqPage.constants.forEach { constant ->
                    val field = pickField(definition, msqPage.number, constant) ?: return@forEach
                    val isDynamic = field.scaleExpr != null || field.translateExpr != null
                    if (isDynamic != dynamicPhase) return@forEach
                    encodeField(field, parseMsqValue(field, constant.rawText), bytes, context)
                }
            }
        }
    }

    /**
     * Um mesmo nome pode ter variantes condicionais no .ini (`#if CELSIUS`, `#if pressure_bar`):
     * escolhe a que casa com as unidades gravadas no MSQ; sem informação, a primeira.
     */
    private fun pickField(definition: IniDefinition, page: Int, constant: MsqConstant): IniFieldDefinition? {
        val candidates = definition.fields.filter { it.name == constant.name && it.page == page }
        if (candidates.size <= 1) return candidates.firstOrNull()
        val units = constant.units?.trim()
        return candidates.firstOrNull { units != null && it.units?.trim().equals(units, ignoreCase = true) }
            ?: candidates.first()
    }

    fun decodeField(field: IniFieldDefinition, bytes: ByteArray, context: ExpressionContext? = null): DecodedField? {
        val offset = field.offset ?: return null
        return when (field.kind) {
            IniFieldKind.SCALAR -> {
                val raw = readRaw(bytes, offset, field.dataType) ?: return null
                DecodedField(field, FieldValue.Numeric(applyScale(raw, field, context)))
            }

            IniFieldKind.ARRAY -> {
                val shape = field.shape ?: return null
                val size = typeSize(field.dataType)
                val values = (0 until shape.totalSize).map { index ->
                    val raw = readRaw(bytes, offset + index * size, field.dataType) ?: return null
                    applyScale(raw, field, context)
                }
                DecodedField(field, FieldValue.Table(shape.rows, shape.columns, values))
            }

            IniFieldKind.BITS -> {
                val raw = readRaw(bytes, offset, field.dataType)?.toInt() ?: return null
                val lo = field.bitLow ?: 0
                val hi = field.bitHigh ?: lo
                val mask = (1 shl (hi - lo + 1)) - 1
                val bitsValue = (raw shr lo) and mask
                val labels = field.enumLabels
                if (labels != null && bitsValue < labels.size) {
                    DecodedField(field, FieldValue.Label(labels[bitsValue]))
                } else {
                    DecodedField(field, FieldValue.Numeric(bitsValue.toDouble()))
                }
            }

            IniFieldKind.STRING, IniFieldKind.UNKNOWN -> null
        }
    }

    fun encodeField(field: IniFieldDefinition, value: FieldValue, bytes: ByteArray, context: ExpressionContext? = null) {
        val offset = field.offset ?: return
        when (field.kind) {
            IniFieldKind.SCALAR -> {
                val numeric = (value as? FieldValue.Numeric)?.value ?: return
                writeRaw(bytes, offset, field.dataType, unapplyScale(numeric, field, context))
            }

            IniFieldKind.ARRAY -> {
                val table = value as? FieldValue.Table ?: return
                val size = typeSize(field.dataType)
                table.values.forEachIndexed { index, entry ->
                    writeRaw(bytes, offset + index * size, field.dataType, unapplyScale(entry, field, context))
                }
            }

            IniFieldKind.BITS -> {
                val lo = field.bitLow ?: 0
                val hi = field.bitHigh ?: lo
                val mask = (1 shl (hi - lo + 1)) - 1
                val bitsValue = when (value) {
                    // Rótulo fora da lista (p.ex. "63" num campo de pino): vale o número bruto.
                    is FieldValue.Label -> field.enumLabels?.indexOf(value.value)?.takeIf { it >= 0 }
                        ?: value.value.trim().toIntOrNull() ?: return
                    is FieldValue.Numeric -> value.value.roundToLong().toInt()
                    is FieldValue.Table -> return
                }
                val current = readRaw(bytes, offset, field.dataType)?.toInt() ?: 0
                val cleared = current and (mask shl lo).inv()
                val updated = cleared or ((bitsValue and mask) shl lo)
                writeRaw(bytes, offset, field.dataType, updated.toDouble())
            }

            IniFieldKind.STRING, IniFieldKind.UNKNOWN -> Unit
        }
    }

    /** Parses a value coming from an MSQ `<constant>` element back into a [FieldValue]. */
    fun parseMsqValue(field: IniFieldDefinition, rawText: String): FieldValue {
        val trimmed = rawText.trim()
        return when {
            field.kind == IniFieldKind.BITS || field.kind == IniFieldKind.STRING ->
                FieldValue.Label(trimmed.removePrefix("\"").removeSuffix("\""))

            field.shape != null -> {
                val values = trimmed.split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
                FieldValue.Table(field.shape.rows, field.shape.columns, values)
            }

            else -> FieldValue.Numeric(trimmed.toDoubleOrNull() ?: 0.0)
        }
    }

    private fun typeSize(dataType: String): Int = when (dataType.trim().uppercase()) {
        "U08", "S08" -> 1
        "U16", "S16" -> 2
        "U32", "S32", "F32" -> 4
        else -> 1
    }

    private fun readRaw(bytes: ByteArray, offset: Int, dataType: String): Long? {
        val size = typeSize(dataType)
        if (offset < 0 || offset + size > bytes.size) return null
        return when (dataType.trim().uppercase()) {
            "U08" -> bytes[offset].toLong() and 0xFF
            "S08" -> bytes[offset].toLong()
            "U16" -> (bytes[offset].toLong() and 0xFF) or ((bytes[offset + 1].toLong() and 0xFF) shl 8)
            "S16" -> (((bytes[offset].toInt() and 0xFF) or (bytes[offset + 1].toInt() shl 8))).toShort().toLong()
            "U32" -> (0 until 4).fold(0L) { acc, i -> acc or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i)) }
            "S32" -> {
                var acc = 0L
                for (i in 0 until 4) acc = acc or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
                acc.toInt().toLong()
            }
            else -> bytes[offset].toLong() and 0xFF
        }
    }

    private fun writeRaw(bytes: ByteArray, offset: Int, dataType: String, value: Double) {
        val size = typeSize(dataType)
        if (offset < 0 || offset + size > bytes.size) return
        val intValue = value.roundToLong()
        when (dataType.trim().uppercase()) {
            "U08", "S08" -> bytes[offset] = (intValue and 0xFF).toByte()
            "U16", "S16" -> {
                bytes[offset] = (intValue and 0xFF).toByte()
                bytes[offset + 1] = ((intValue shr 8) and 0xFF).toByte()
            }
            "U32", "S32" -> for (i in 0 until 4) bytes[offset + i] = ((intValue shr (8 * i)) and 0xFF).toByte()
            else -> bytes[offset] = (intValue and 0xFF).toByte()
        }
    }

    private fun applyScale(raw: Long, field: IniFieldDefinition, context: ExpressionContext?): Double {
        val scale = context?.evaluate(field.scaleExpr) ?: field.scale ?: 1.0
        val translate = context?.evaluate(field.translateExpr) ?: field.translate ?: 0.0
        return round((raw + translate) * scale * 1000.0) / 1000.0
    }

    private fun unapplyScale(value: Double, field: IniFieldDefinition, context: ExpressionContext?): Double {
        val scale = context?.evaluate(field.scaleExpr) ?: field.scale ?: 1.0
        val translate = context?.evaluate(field.translateExpr) ?: field.translate ?: 0.0
        if (scale == 0.0) return 0.0
        return (value / scale) - translate
    }
}

/**
 * Resolve identificadores de expressões de escala (`algorithm`, `stoich`, `fuelLoadRes`…)
 * contra os bytes de página atuais e as variáveis de expressão do .ini.
 */
class ExpressionContext(private val definition: IniDefinition, private val pageBytes: Map<Int, ByteArray>) {
    fun evaluate(expression: String?, depth: Int = 0): Double? {
        if (expression == null || depth > 6) return null
        return IniExpression(expression) { name -> resolve(name, depth) }.evaluate()
    }

    private fun resolve(name: String, depth: Int): Double? {
        definition.expressionVariables[name]?.let { return evaluate(it, depth + 1) }
        val field = definition.fields.firstOrNull { it.name == name && it.page != null && pageBytes.containsKey(it.page) }
            ?: return null
        val bytes = pageBytes.getValue(field.page!!)
        val decoded = PageFieldCodec.decodeField(field, bytes, this) ?: return null
        return when (val value = decoded.value) {
            is FieldValue.Numeric -> value.value
            // Em campos de enumeração a expressão compara pelo ÍNDICE do rótulo (algorithm == 0).
            is FieldValue.Label -> field.enumLabels?.indexOf(value.value)?.takeIf { it >= 0 }?.toDouble()
            is FieldValue.Table -> null
        }
    }
}
