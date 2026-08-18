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

    fun decodePage(definition: IniDefinition, page: Int, bytes: ByteArray): List<DecodedField> {
        return definition.fields
            .filter { it.page == page && it.offset != null }
            .mapNotNull { field -> decodeField(field, bytes) }
    }

    /** Decodes every page present in [pageBytes] (keyed by page number) into named field values. */
    fun decodeAllPages(definition: IniDefinition, pageBytes: Map<Int, ByteArray>): List<Pair<Int, List<DecodedField>>> =
        pageBytes.keys.sorted().map { page -> page to decodePage(definition, page, pageBytes.getValue(page)) }

    /**
     * Applies every `<constant>` from a parsed [MsqDocument] onto the matching page bytes in
     * [pageBytes], mutating the byte arrays in place. Constants whose page isn't present in
     * [pageBytes], or whose name doesn't match a field of [definition] on that page, are skipped.
     */
    fun applyMsqDocument(definition: IniDefinition, doc: MsqDocument, pageBytes: Map<Int, ByteArray>) {
        doc.pages.forEach { msqPage ->
            val bytes = pageBytes[msqPage.number] ?: return@forEach
            msqPage.constants.forEach { constant ->
                val field = definition.fields.firstOrNull { it.name == constant.name && it.page == msqPage.number }
                    ?: return@forEach
                encodeField(field, parseMsqValue(field, constant.rawText), bytes)
            }
        }
    }

    fun decodeField(field: IniFieldDefinition, bytes: ByteArray): DecodedField? {
        val offset = field.offset ?: return null
        return when (field.kind) {
            IniFieldKind.SCALAR -> {
                val raw = readRaw(bytes, offset, field.dataType) ?: return null
                DecodedField(field, FieldValue.Numeric(applyScale(raw, field)))
            }

            IniFieldKind.ARRAY -> {
                val shape = field.shape ?: return null
                val size = typeSize(field.dataType)
                val values = (0 until shape.totalSize).map { index ->
                    val raw = readRaw(bytes, offset + index * size, field.dataType) ?: return null
                    applyScale(raw, field)
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

    fun encodeField(field: IniFieldDefinition, value: FieldValue, bytes: ByteArray) {
        val offset = field.offset ?: return
        when (field.kind) {
            IniFieldKind.SCALAR -> {
                val numeric = (value as? FieldValue.Numeric)?.value ?: return
                writeRaw(bytes, offset, field.dataType, unapplyScale(numeric, field))
            }

            IniFieldKind.ARRAY -> {
                val table = value as? FieldValue.Table ?: return
                val size = typeSize(field.dataType)
                table.values.forEachIndexed { index, entry ->
                    writeRaw(bytes, offset + index * size, field.dataType, unapplyScale(entry, field))
                }
            }

            IniFieldKind.BITS -> {
                val lo = field.bitLow ?: 0
                val hi = field.bitHigh ?: lo
                val mask = (1 shl (hi - lo + 1)) - 1
                val bitsValue = when (value) {
                    is FieldValue.Label -> field.enumLabels?.indexOf(value.value)?.takeIf { it >= 0 } ?: return
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

    private fun applyScale(raw: Long, field: IniFieldDefinition): Double {
        val scale = field.scale ?: 1.0
        val translate = field.translate ?: 0.0
        return round((raw + translate) * scale * 1000.0) / 1000.0
    }

    private fun unapplyScale(value: Double, field: IniFieldDefinition): Double {
        val scale = field.scale ?: 1.0
        val translate = field.translate ?: 0.0
        if (scale == 0.0) return 0.0
        return (value / scale) - translate
    }
}
