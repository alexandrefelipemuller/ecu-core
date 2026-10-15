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
