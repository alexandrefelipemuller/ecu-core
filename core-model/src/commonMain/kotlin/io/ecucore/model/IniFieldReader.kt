package io.ecucore.model

import io.ecucore.definition.IniDefinition
import io.ecucore.definition.IniFieldDefinition
import io.ecucore.definition.IniFieldKind

/**
 * Leitor de campos nomeados do `[Constants]` de um .ini sobre bytes de páginas já lidas da ECU.
 *
 * `page = N` no .ini é 1-based e indexa `pageIdentifier`/`pageSize`; [pages] é indexado pelo ID
 * real da página na ECU (ex.: 0x04), o mesmo de `IniPageDefinition.resolvedId`.
 * Suporta bits e escalares U08 (suficiente para os seletores de pino/opção da MegaSpeed);
 * o que não for suportado retorna null em vez de adivinhar.
 */
class IniFieldReader(
    private val definition: IniDefinition,
    private val pages: Map<Int, ByteArray>,
) {
    private val byName: Map<String, IniFieldDefinition> =
        definition.fields.filter { it.section.equals("Constants", ignoreCase = true) }
            .associateBy { it.name }

    fun has(name: String): Boolean = byName.containsKey(name)

    /** Valor bruto de um campo `bits` ou escalar U08; para arrays use [element]. */
    fun raw(name: String): Int? {
        val field = byName[name] ?: return null
        if (field.kind == IniFieldKind.ARRAY) return null
        return rawAt(field, 0)
    }

    /** Elemento [index] de um array U08 (ex.: `psEnabled[3]`). */
    fun element(name: String, index: Int): Int? {
        val field = byName[name] ?: return null
        if (field.kind != IniFieldKind.ARRAY) return null
        val size = field.shape?.totalSize ?: return null
        if (index !in 0 until size) return null
        return rawAt(field, index)
    }

    fun flag(name: String): Boolean = (raw(name) ?: 0) != 0

    /** Rótulo do enum de um campo `bits`; null se o valor cai em "INVALID" ou fora da lista. */
    fun label(name: String): String? {
        val field = byName[name] ?: return null
        val value = raw(name) ?: return null
        val label = field.enumLabels?.getOrNull(value)?.trim() ?: return null
        return label.takeIf { it.isNotEmpty() && !it.equals("INVALID", ignoreCase = true) }
    }

    /**
     * Valor em unidade do usuário de um escalar: `(raw + translate) * scale` (convenção do .ini).
     * Suporta U08/S08/U16/S16 big-endian (MS2/MegaSpeed). Outros tipos retornam null.
     */
    fun scaled(name: String): Double? {
        val field = byName[name] ?: return null
        if (field.kind != IniFieldKind.SCALAR) return null
        val raw = signedRaw(field) ?: return null
        return (raw + (field.translate ?: 0.0)) * (field.scale ?: 1.0)
    }

    /** Texto para exibição: rótulo do enum para `bits`, valor escalado com unidade para escalares. */
    fun display(name: String): String? {
        val field = byName[name] ?: return null
        return when (field.kind) {
            IniFieldKind.BITS -> label(name)
            IniFieldKind.SCALAR -> {
                val value = scaled(name) ?: return null
                val digits = (field.digits ?: 0).coerceIn(0, 4)
                val number = formatFixed(value, digits)
                val units = field.units?.trim().orEmpty()
                if (units.isEmpty()) number else "$number $units"
            }
            else -> null
        }
    }

    private fun signedRaw(field: IniFieldDefinition): Int? {
        val pageIndex = (field.page ?: return null) - 1
        val pageId = definition.pageDefinitions.getOrNull(pageIndex)?.resolvedId ?: return null
        val data = pages[pageId] ?: return null
        val offset = field.offset ?: return null
        fun u8(i: Int) = data[i].toInt() and 0xFF
        return when (field.dataType.uppercase()) {
            "U08" -> if (offset in data.indices) u8(offset) else null
            "S08" -> if (offset in data.indices) data[offset].toInt() else null
            "U16", "S16" -> {
                if (offset + 1 !in data.indices) return null
                val v = (u8(offset) shl 8) or u8(offset + 1)
                if (field.dataType.equals("S16", ignoreCase = true) && v >= 0x8000) v - 0x10000 else v
            }
            else -> null
        }
    }

    private fun rawAt(field: IniFieldDefinition, index: Int): Int? {
        if (!field.dataType.equals("U08", ignoreCase = true)) return null
        val pageIndex = (field.page ?: return null) - 1
        val pageId = definition.pageDefinitions.getOrNull(pageIndex)?.resolvedId ?: return null
        val data = pages[pageId] ?: return null
        val offset = (field.offset ?: return null) + index
        if (offset !in data.indices) return null
        val byte = data[offset].toInt() and 0xFF
        if (field.kind != IniFieldKind.BITS) return byte
        val low = field.bitLow ?: return null
        val high = field.bitHigh ?: return null
        return (byte shr low) and ((1 shl (high - low + 1)) - 1)
    }
}
