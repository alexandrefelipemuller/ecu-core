package io.ecucore.model

import io.ecucore.definition.IniParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

class MegaSpeedIniTableDefinitionsTest {

    private val ini = """
            [MegaTune]
            queryCommand = "Q"
            signature = "MS2Extra MegaSpeed "

            [Constants]
            endianness = big
            nPages = 7
            pageSize = 1024,1024,1024,1024,1024,1024,1024
            pageIdentifier = "${'$'}tsCanId\x04","${'$'}tsCanId\x05","${'$'}tsCanId\x0a","${'$'}tsCanId\x08","${'$'}tsCanId\x09","${'$'}tsCanId\x0b","${'$'}tsCanId\x0c"
            ochBlockSize = 219

            page = 1
            afrTable1 = array, U08, 48, [12x12], "AFR", 0.1, 0, 1, 25, 1
            arpm_table1 = array, U16, 374, [12], "RPM", 1, 0, 0, 15000, 0
            amap_table1 = array, S16, 422, [12], "kPa", 0.1, 0, 0, 700, 1

            page = 3
            advanceTable1 = array, S16, 0, [12x12], "grau", 0.1, 0, -10, 90, 1
            srpm_table1 = array, U16, 576, [12], "RPM", 1, 0, 0, 15000, 0
            smap_table1 = array, S16, 624, [12], "kPa", 0.1, 0, 0, 700, 1

            page = 5
            veTable1 = array, U08, 0, [16x16], "%", 1, 0, 0, 255, 0
            ve1RpmBins = array, U16, 768, [16], "RPM", 1, 0, 0, 15000, 0
            ve1LoadBins = array, S16, 864, [16], "kPa", 0.1, 0, 0, 700, 1
        """.trimIndent()

    private val catalog = assertNotNull(MegaSpeedIniTableDefinitions.fromIni(IniParser.parse("megaspeed.ini", ini)))

    private fun s16(vararg v: Int): ByteArray {
        val out = ByteArray(v.size * 2)
        v.forEachIndexed { i, x -> out[i * 2] = (x shr 8).toByte(); out[i * 2 + 1] = x.toByte() }
        return out
    }

    @Test
    fun buildsCatalogFromMegaSpeedIni() {

        assertNotNull(catalog)
        assertEquals(219, catalog.tableDefinitions.ochBlockSize)
        assertEquals(0x09, catalog.veTable.metadata.page)
        assertEquals(0x0A, catalog.ignitionTable.metadata.page)
        assertEquals(0x04, catalog.afrTable.metadata.page)
        assertEquals(16 to 16, catalog.veTable.metadata.valuesShape)
        assertEquals(12 to 12, catalog.ignitionTable.metadata.valuesShape)
        assertEquals(12 to 12, catalog.afrTable.metadata.valuesShape)
    }

    @Test
    fun ignitionPreservaDecimosDeGrauEDeKpaNaIdaEVolta() {
        val valuesBytes = s16(*IntArray(144) { if (it == 0) 132 else 100 + it })   // 13,2° na célula [0,0]
        val rpmBytes = s16(*IntArray(12) { 500 + it * 500 })
        val loadBytes = s16(*IntArray(12) { if (it == 7) 955 else 300 + it * 65 })  // 95,5 kPa
        val layout = catalog.ignitionTable

        val table = MegaSpeedIniTableDefinitions.parseIgnitionTable(layout, valuesBytes, rpmBytes, loadBytes)
        assertEquals(132, table.values[0][0])
        assertEquals(0.1, table.valueScale)
        assertEquals(13.2, table.degreesAt(0, 0), 1e-9)
        assertEquals(955, table.loadBins[7])
        assertEquals(0.1, table.loadScale)

        val serialized = MegaSpeedIniTableDefinitions.serializeIgnitionTable(layout, table)
        assertContentEquals(valuesBytes, serialized.valuesData)
        assertContentEquals(rpmBytes, serialized.rpmAxisData)
        assertContentEquals(loadBytes, serialized.loadAxisData)

        // Editar para 13,7° grava 137 (bruto), sem arredondar o resto da tabela.
        val edited = table.setValue(0, 0, 137)
        val raw = MegaSpeedIniTableDefinitions.serializeIgnitionTable(layout, edited).valuesData
        assertEquals(137, ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF))
        assertContentEquals(valuesBytes.copyOfRange(2, valuesBytes.size), raw.copyOfRange(2, raw.size))
    }

    @Test
    fun veEAfrPreservamEixoDeCargaEmDecimos() {
        val loadBytes16 = s16(*IntArray(16) { if (it == 3) 455 else 200 + it * 50 })
        val rpm16 = s16(*IntArray(16) { 500 + it * 500 })
        val ve = MegaSpeedIniTableDefinitions.parseVeTable(catalog.veTable, ByteArray(256) { 50 }, rpm16, loadBytes16)
        assertEquals(455, ve.loadBins[3])
        assertContentEquals(loadBytes16, MegaSpeedIniTableDefinitions.serializeVeTable(catalog.veTable, ve).loadAxisData)

        val loadBytes12 = s16(*IntArray(12) { if (it == 2) 1015 else 300 + it * 65 })
        val rpm12 = s16(*IntArray(12) { 500 + it * 500 })
        val afr = MegaSpeedIniTableDefinitions.parseAfrTable(catalog.afrTable, ByteArray(144) { 147.toByte() }, rpm12, loadBytes12)
        assertEquals(1015, afr.loadBins[2])
        assertContentEquals(loadBytes12, MegaSpeedIniTableDefinitions.serializeAfrTable(catalog.afrTable, afr).loadAxisData)
    }
}
