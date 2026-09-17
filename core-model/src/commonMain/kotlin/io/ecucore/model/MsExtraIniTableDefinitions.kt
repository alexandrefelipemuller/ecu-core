package io.ecucore.model

import io.ecucore.definition.FieldValue
import io.ecucore.definition.IniDefinition
import io.ecucore.definition.IniFieldDefinition
import io.ecucore.definition.PageFieldCodec
import kotlin.math.roundToInt

/**
 * Legacy MS1/Extra (hr_10/hr_11d) firmware stores VE/Ignition tables and their axes as plain
 * named fields inside a single small legacy page (189 bytes) - unlike MS2/MS3/MegaSpeed, which
 * expose each table/axis as its own CRC32 page. So instead of a hand-mapped byte layout, this
 * locates the real field names from the connected ECU's .ini (e.g. veBins1/rpmBins1/mapBins1)
 * and decodes them with [PageFieldCodec] against the already-fetched whole-page bytes.
 *
 * The load axis name varies with the firmware's configured sensing mode (Speed Density vs
 * Alpha-N vs Air Flow Meter) - mapBins/tpsBins/afmBins respectively - and the shared .ini parser
 * doesn't evaluate `#if`/`#elif` preprocessor blocks, so all variants end up defined; the first
 * match found (by candidate priority) is used.
 */
object MsExtraIniTableDefinitions {

    data class TableLayout(
        val page: Int,
        val valueField: IniFieldDefinition,
        val rpmField: IniFieldDefinition,
        val loadField: IniFieldDefinition,
    )

    data class Catalog(
        val veTable: TableLayout,
        val ignitionTable: TableLayout,
    )

    private val loadAxisCandidates = listOf("mapBins", "tpsBins", "afmBins")

    fun fromIni(definition: IniDefinition): Catalog? {
        val normalizedSignature = definition.signature.trim()
        val isMsExtra = normalizedSignature.startsWith("MS/Extra format hr_1", ignoreCase = true) ||
            normalizedSignature.startsWith("MS1/Extra", ignoreCase = true)
        if (!isMsExtra) return null

        val veTable = buildLayout(
            definition = definition,
            valueFieldName = "veBins1",
            rpmFieldName = "rpmBins1",
            loadFieldSuffix = "1",
        ) ?: return null
        val ignitionTable = buildLayout(
            definition = definition,
            valueFieldName = "advTable1",
            rpmFieldName = "rpmBins3",
            loadFieldSuffix = "3",
        ) ?: return null

        return Catalog(veTable = veTable, ignitionTable = ignitionTable)
    }

    private fun buildLayout(
        definition: IniDefinition,
        valueFieldName: String,
        rpmFieldName: String,
        loadFieldSuffix: String,
    ): TableLayout? {
        val valueField = findField(definition, valueFieldName) ?: return null
        val rpmField = findField(definition, rpmFieldName) ?: return null
        val loadField = loadAxisCandidates
            .asSequence()
            .mapNotNull { prefix -> findField(definition, "$prefix$loadFieldSuffix") }
            .firstOrNull() ?: return null
        val page = valueField.page ?: return null
        return TableLayout(page = page, valueField = valueField, rpmField = rpmField, loadField = loadField)
    }

    private fun findField(definition: IniDefinition, name: String): IniFieldDefinition? =
        definition.fields.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun decodeVeTable(layout: TableLayout, pageBytes: ByteArray, loadType: VeTable.LoadType): VeTable = VeTable(
        rpmBins = decodeAxis(layout.rpmField, pageBytes),
        loadBins = decodeAxis(layout.loadField, pageBytes),
        values = decodeTable(layout.valueField, pageBytes),
        loadType = loadType,
    )

    fun decodeIgnitionTable(layout: TableLayout, pageBytes: ByteArray, loadType: IgnitionTable.LoadType): IgnitionTable = IgnitionTable(
        rpmBins = decodeAxis(layout.rpmField, pageBytes),
        loadBins = decodeAxis(layout.loadField, pageBytes),
        values = decodeTable(layout.valueField, pageBytes),
        loadType = loadType,
    )

    private fun decodeTable(field: IniFieldDefinition, pageBytes: ByteArray): List<List<Int>> {
        val shape = field.shape ?: return emptyList()
        val flat = (PageFieldCodec.decodeField(field, pageBytes)?.value as? FieldValue.Table)?.values ?: return emptyList()
        return List(shape.rows) { row ->
            List(shape.columns) { col -> flat.getOrNull(row * shape.columns + col)?.roundToInt() ?: 0 }
        }
    }

    private fun decodeAxis(field: IniFieldDefinition, pageBytes: ByteArray): List<Int> {
        return when (val value = PageFieldCodec.decodeField(field, pageBytes)?.value) {
            is FieldValue.Table -> value.values.map { it.roundToInt() }
            is FieldValue.Numeric -> listOf(value.value.roundToInt())
            else -> emptyList()
        }
    }
}
