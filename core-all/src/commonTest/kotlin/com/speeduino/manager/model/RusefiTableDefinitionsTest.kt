package com.speeduino.manager.model

import kotlin.test.Test
import kotlin.test.assertEquals

class RusefiTableDefinitionsTest {

    @Test
    fun `serialize ve table normalizes descending load axis before burn`() {
        val layout = RusefiTableDefinitions.TableLayout(
            metadata = TableMetadata(
                name = "Test rusEFI VE",
                page = 0,
                offset = 0,
                totalSize = 12,
                valuesShape = 2 to 3,
                valuesOffset = 0,
                rpmBinsOffset = 12,
                loadBinsOffset = 18,
                valueType = DataType.U16,
                valueRange = 0.0..999.0,
                rpmRange = 0..18000,
                loadRange = 0.0..1000.0,
                units = "%",
                scale = 1.0,
                translate = 0.0,
            ),
            valueByteOrder = EcuByteOrder.LITTLE_ENDIAN,
            rpmAxis = RusefiTableDefinitions.AxisLayout(
                tableId = 0,
                offset = 12,
                count = 3,
                dataType = DataType.U16,
            ),
            loadAxis = RusefiTableDefinitions.AxisLayout(
                tableId = 0,
                offset = 18,
                count = 2,
                dataType = DataType.U16,
            ),
        )

        val table = VeTable(
            rpmBins = listOf(1000, 2000, 3000),
            loadBins = listOf(280, 240),
            values = listOf(
                listOf(10, 11, 12),
                listOf(20, 21, 22),
            ),
        )

        val serialized = RusefiTableDefinitions.serializeVeTableWithLayout(
            layout = layout,
            table = table,
            signedValues = false,
            valueScale = 1,
        )

        assertEquals(listOf(1000, 2000, 3000), serialized.rpmAxisData.toU16List())
        assertEquals(listOf(240, 280), serialized.loadAxisData.toU16List())
        assertEquals(
            listOf(20, 21, 22, 10, 11, 12),
            serialized.valuesData.toU16List(),
        )
    }

    private fun ByteArray.toU16List(): List<Int> {
        val result = ArrayList<Int>(size / 2)
        var index = 0
        while (index < size) {
            val low = getOrNull(index)?.toInt()?.and(0xFF) ?: 0
            val high = getOrNull(index + 1)?.toInt()?.and(0xFF) ?: 0
            result += low or (high shl 8)
            index += 2
        }
        return result
    }
}
