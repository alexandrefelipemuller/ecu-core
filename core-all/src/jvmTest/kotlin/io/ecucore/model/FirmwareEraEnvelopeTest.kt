package io.ecucore.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Corte confirmado nos .ini oficiais em `speeduino_ini_analysis/speeduino/`:
 * `messageEnvelopeFormat = msEnvelope_1.0` aparece a partir de 202201. Antes disso não há
 * envelope; e só a 201609 usa `ochGetCommand = "A"`.
 */
class FirmwareEraEnvelopeTest {

    @Test
    fun `eras from 2022 onwards use the modern envelope`() {
        assertTrue(FirmwareEra.MODERN_2022.usesModernEnvelope())
        assertTrue(FirmwareEra.MODERN_2023.usesModernEnvelope())
        assertTrue(FirmwareEra.MODERN_2024.usesModernEnvelope())
        assertTrue(FirmwareEra.MODERN_2025.usesModernEnvelope())
    }

    @Test
    fun `legacy and 2020 eras do not use the envelope`() {
        assertFalse(FirmwareEra.LEGACY.usesModernEnvelope())
        assertFalse(FirmwareEra.MODERN_2020.usesModernEnvelope())
    }

    @Test
    fun `firmware versions map to the expected envelope decision`() {
        val expected = mapOf(
            "speeduino 201609" to false,
            "speeduino 202008" to false,
            "speeduino 202012" to false,
            "speeduino 202201" to true,
            "speeduino 202207" to true,
            "speeduino 202310" to true,
            "speeduino 202402" to true,
            "speeduino 202501" to true,
        )
        expected.forEach { (signature, usesEnvelope) ->
            val era = SpeeduinoTableDefinitions.detectFirmwareEra(signature)
            assertEquals(usesEnvelope, era.usesModernEnvelope(), "$signature (era=$era)")
        }
    }
}
