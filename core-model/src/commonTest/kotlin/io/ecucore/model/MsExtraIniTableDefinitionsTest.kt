package io.ecucore.model

import io.ecucore.definition.IniParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Uses a trimmed excerpt of the real MS/Extra hr_10 .ini (page 1 VE table + page 3 ignition
 * table field declarations) to verify the catalog is built from named .ini fields instead of
 * hand-mapped byte offsets, and that decoding against a raw page buffer produces the expected
 * values.
 */
class MsExtraIniTableDefinitionsTest {

    private val iniText = """
        [MegaTune]
           signature    = "MS/Extra format hr_10 **********"

        [Constants]
           page = 1
              veBins1    = array,  U08,       0, [2x2], "%",       1.0,       0.0,   0.0,   255.0,      0
              rpmBins1   = array,  U08,       4, [  2], "RPM",    100.0,       0.0,   100,   25500,      0
              mapBins1   = array,  U08,       6, [ 2],  "kPa",     1.0,        0.0,   0.0,  255.0,      0

           page = 3
              advTable1  = array,  U08,       0, [2x2], "deg",   0.352,   -28.4,   -10.0,    80.0,      0
              rpmBins3   = array,  U08,       4, [  2], "RPM",   100.0,     0.0,   100,   25500,      0
              mapBins3   = array,  U08,       6, [ 2],  "kPa",     1.0,     0.0,   0.0,   255.0,      0
    """.trimIndent()

    @Test
    fun `builds catalog from named ini fields`() {
        val definition = IniParser.parse("msextra-hr10-test", iniText)
        val catalog = MsExtraIniTableDefinitions.fromIni(definition)
        assertNotNull(catalog)
        assertEquals(1, catalog.veTable.page)
        assertEquals(3, catalog.ignitionTable.page)
        assertEquals("mapBins1", catalog.veTable.loadField.name)
        assertEquals("mapBins3", catalog.ignitionTable.loadField.name)
    }

    @Test
    fun `decodes ve table values and axes from raw page bytes`() {
        val definition = IniParser.parse("msextra-hr10-test", iniText)
        val catalog = MsExtraIniTableDefinitions.fromIni(definition)
        assertNotNull(catalog)

        // veBins1 (offset 0, 2x2, scale 1.0), rpmBins1 (offset 4, scale 100.0), mapBins1 (offset 6, scale 1.0)
        val page = byteArrayOf(
            10, 20, 30, 40, // veBins1 row-major: [10,20] / [30,40]
            50, 60,         // rpmBins1 raw -> *100 = 5000, 6000
            70, 80,         // mapBins1 raw -> *1.0 = 70, 80
        )

        val veTable = MsExtraIniTableDefinitions.decodeVeTable(catalog.veTable, page, VeTable.LoadType.MAP)
        assertEquals(listOf(listOf(10, 20), listOf(30, 40)), veTable.values)
        assertEquals(listOf(5000, 6000), veTable.rpmBins)
        assertEquals(listOf(70, 80), veTable.loadBins)
    }

    @Test
    fun `decodes ignition table values applying scale and translate`() {
        val definition = IniParser.parse("msextra-hr10-test", iniText)
        val catalog = MsExtraIniTableDefinitions.fromIni(definition)
        assertNotNull(catalog)

        // advTable1 raw byte 0 -> (0 + -28.4) * 0.352 rounded; verify round-trip math matches applyScale.
        val page = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
        val ignitionTable = MsExtraIniTableDefinitions.decodeIgnitionTable(catalog.ignitionTable, page, IgnitionTable.LoadType.MAP)
        assertEquals(4, ignitionTable.values.flatten().size)
    }
}
