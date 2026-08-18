package io.ecucore.definition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MsqRoundTripTest {

    private val ini = """
        [MegaTune]
        signature = "speeduino 202501"

        [Constants]
        nPages = 2
        pageSize = 16, 32
        page = 0
        aseTaperTime = scalar, U08, 0, "S", 0.1, 0.0, 0.0, 25.5, 1
        aeMode = bits, U08, 1, [0:1], "TPS", "MAP", "INVALID", "INVALID"
        page = 1
        veTable = array, U08, 0, [2x2], "%", 1.0, 0.0, 0.0, 255.0, 0
    """.trimIndent()

    @Test
    fun decodesEncodesAndRoundTripsThroughMsq() {
        val definition = IniParser.parse("speeduino.ini", ini)

        val page0 = ByteArray(16)
        page0[0] = 100 // aseTaperTime raw -> 10.0 S
        page0[1] = 0b10 // aeMode bits [0:1] = 2 -> "INVALID"... use 1 -> MAP
        page0[1] = 1

        val page1 = ByteArray(32)
        page1[0] = 10
        page1[1] = 20
        page1[2] = 30
        page1[3] = 40

        val pageBytes = mapOf(0 to page0, 1 to page1)
        val decoded = PageFieldCodec.decodeAllPages(definition, pageBytes)

        val aseTaper = decoded.first { it.first == 0 }.second.first { it.definition.name == "aseTaperTime" }
        assertEquals(FieldValue.Numeric(10.0), aseTaper.value)

        val aeMode = decoded.first { it.first == 0 }.second.first { it.definition.name == "aeMode" }
        assertEquals(FieldValue.Label("MAP"), aeMode.value)

        val veTable = decoded.first { it.first == 1 }.second.first { it.definition.name == "veTable" }
        assertEquals(FieldValue.Table(2, 2, listOf(10.0, 20.0, 30.0, 40.0)), veTable.value)

        val xml = MsqCodec.encode(definition.signature, "test", decoded)
        assertTrue(xml.contains("name=\"aseTaperTime\""))
        assertTrue(xml.contains("name=\"aeMode\""))
        assertTrue(xml.contains("\"MAP\""))
        assertTrue(xml.contains("name=\"veTable\""))

        // Mutate target bytes to confirm re-import overwrites them from the MSQ text.
        val reimportPage0 = ByteArray(16)
        val reimportPage1 = ByteArray(32)
        val target = mapOf(0 to reimportPage0, 1 to reimportPage1)

        val doc = MsqCodec.decode(xml)
        assertEquals("speeduino 202501", doc.signature)
        PageFieldCodec.applyMsqDocument(definition, doc, target)

        assertEquals(page0.toList(), reimportPage0.toList())
        assertEquals(page1.toList(), reimportPage1.toList())
    }
}
