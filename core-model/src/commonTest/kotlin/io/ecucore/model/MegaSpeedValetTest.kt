package io.ecucore.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MegaSpeedValetTest {
    @Test
    fun `valuesFor limita o rpm a faixa do valet e liga corte de ignicao com retardo`() {
        assertEquals(1000.0, MegaSpeedValet.valuesFor(200)[MegaSpeedValet.FIELD_LIMIT])
        assertEquals(5000.0, MegaSpeedValet.valuesFor(9000)[MegaSpeedValet.FIELD_LIMIT])
        val v = MegaSpeedValet.valuesFor(3500)
        assertEquals(3500.0, v[MegaSpeedValet.FIELD_LIMIT])
        assertEquals(1.0, v[MegaSpeedValet.FIELD_SPARK_CUT])
        assertEquals(1.0, v[MegaSpeedValet.FIELD_RETARD_MODE])
        assertEquals(MegaSpeedValet.FIELDS.toSet(), v.keys)
    }

    @Test
    fun `isActive reconhece o valet gravado e nao a calibracao de rua`() {
        assertTrue(MegaSpeedValet.isActive(MegaSpeedValet.valuesFor(1000)))
        assertTrue(MegaSpeedValet.isActive(MegaSpeedValet.valuesFor(5000)))
        val street = mapOf(MegaSpeedValet.FIELD_LIMIT to 7200.0, MegaSpeedValet.FIELD_SPARK_CUT to 1.0)
        assertFalse(MegaSpeedValet.isActive(street))
        assertFalse(MegaSpeedValet.isActive(emptyMap()))
    }

    @Test
    fun `backup so vale completo e fora da faixa do valet`() {
        val street = mapOf(
            MegaSpeedValet.FIELD_LIMIT to 7200.0, MegaSpeedValet.FIELD_SPARK_CUT to 0.0,
            MegaSpeedValet.FIELD_RETARD_MODE to 0.0, MegaSpeedValet.FIELD_RETARD_MAX to 0.0,
        )
        assertTrue(MegaSpeedValet.isValidBackup(street))
        assertFalse(MegaSpeedValet.isValidBackup(null))
        assertFalse(MegaSpeedValet.isValidBackup(street - MegaSpeedValet.FIELD_RETARD_MAX))
        assertFalse(MegaSpeedValet.isValidBackup(MegaSpeedValet.valuesFor(4000)))
    }
}
