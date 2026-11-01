package io.ecucore.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Ms2TableDefinitionsDecimalTest {

    private fun s16(vararg v: Int): ByteArray {
        val out = ByteArray(v.size * 2)
        v.forEachIndexed { i, x ->
            out[i * 2] = ((x shr 8) and 0xFF).toByte()
            out[i * 2 + 1] = (x and 0xFF).toByte()
        }
        return out
    }

    private fun ignitionBytes(first: Int): ByteArray {
        val raw = IntArray(144) { 100 + it }
        raw[0] = first
        return s16(*raw)
    }

    private val rpmAxis = s16(*IntArray(12) { 500 + it * 500 })
    private val loadAxis = s16(*IntArray(12) { if (it == 7) 955 else 300 + it * 65 })

    @Test
    fun ignition_roundtrip_preserva_decimos_de_grau_byte_a_byte() {
        val bytes = ignitionBytes(132)
        val table = Ms2TableDefinitions.parseIgnitionTable(bytes, rpmAxis, loadAxis)
        assertEquals(132, table.values[0][0])
        assertEquals(0.1, table.valueScale)
        assertEquals(13.2, table.degreesAt(0, 0), 1e-9)

        val serialized = Ms2TableDefinitions.serializeIgnitionTable(table)
        assertContentEquals(bytes, serialized.valuesData)
    }

    @Test
    fun eixo_de_carga_preserva_95_5_kpa() {
        val table = Ms2TableDefinitions.parseIgnitionTable(ignitionBytes(100), rpmAxis, loadAxis)
        assertEquals(955, table.loadBins[7])
        assertEquals(0.1, table.loadScale)
        assertEquals(95.5, table.loadBins[7] * table.loadScale, 1e-9)
        assertContentEquals(loadAxis, Ms2TableDefinitions.serializeIgnitionTable(table).loadAxisData)
    }

    @Test
    fun ve_e_afr_preservam_eixo_de_carga() {
        val rpm16 = s16(*IntArray(16) { 500 + it * 500 })
        val load16 = s16(*IntArray(16) { if (it == 7) 955 else 300 + it * 65 })
        val ve = Ms2TableDefinitions.parseVeTable(ByteArray(256) { 80 }, rpm16, load16)
        assertEquals(955, ve.loadBins[7])
        assertContentEquals(load16, Ms2TableDefinitions.serializeVeTable(ve).loadAxisData)
        val afr = Ms2TableDefinitions.parseAfrTable(ByteArray(144) { 147.toByte() }, rpmAxis, loadAxis)
        assertContentEquals(loadAxis, Ms2TableDefinitions.serializeAfrTable(afr).loadAxisData)
    }

    @Test
    fun tabela_em_graus_inteiros_escala_1_ainda_grava_decimos() {
        val rpm = List(12) { 500 + it * 500 }
        val load = List(12) { 30 + it * 7 }
        val t = IgnitionTable(rpm, load, List(12) { List(12) { 25 } })
        val s = Ms2TableDefinitions.serializeIgnitionTable(t)
        // 25 graus -> 250 decimos = 0x00FA
        assertEquals(0x00, s.valuesData[0].toInt())
        assertEquals(0xFA, s.valuesData[1].toInt() and 0xFF)
        // 30 kPa -> 300 = 0x012C
        assertEquals(0x01, s.loadAxisData[0].toInt())
        assertEquals(0x2C, s.loadAxisData[1].toInt())
    }

    @Test
    fun setValue_respeita_escala() {
        val table = Ms2TableDefinitions.parseIgnitionTable(ignitionBytes(100), rpmAxis, loadAxis)
        assertEquals(132, table.setValue(0, 0, 132).getValue(0, 0))
        assertEquals(700, table.setValue(0, 0, 9999).getValue(0, 0))
        assertEquals(-400, table.setValue(0, 0, -9999).getValue(0, 0))
    }

    @Test
    fun validador_considera_escala_no_limite_de_avanco() {
        val ok = IgnitionTable(
            List(12) { 500 + it * 500 }, List(12) { 300 + it * 50 },
            List(12) { List(12) { 132 } }, loadScale = 0.1, valueScale = 0.1,
        )
        val validator = TableValidator(Ms2TableDefinitions.IGNITION_TABLE_1.metadata)
        assertTrue(validator.validate(ok).isValid)
        assertTrue(validator.validate(ok).warnings.none { it.contains("high ignition advance") })
        // 55.0 graus = 550 decimos deve bloquear
        val danger = ok.copy(values = List(12) { List(12) { 550 } })
        assertFalse(validator.validate(danger).isValid)
    }
}
