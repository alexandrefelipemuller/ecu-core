package io.ecucore.ecu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FirmwareHandshakeDomainTest {

    @Test
    fun `normalizes noisy speeduino signatures`() {
        val normalized = FirmwareHandshakeDomain.normalizeSignature(" speeduino   2024.02 \u0000 ???")
        assertEquals("speeduino 202402", normalized)
    }

    @Test
    fun `resolves consensus from repeated valid samples`() {
        val consensus = FirmwareHandshakeDomain.resolveConsensus(
            listOf(
                "speeduino 202402",
                "Speeduino 2024.02",
                "noise",
            ),
        )

        assertEquals("speeduino 202402", consensus.signature)
        assertEquals(2, consensus.consensusHits)
    }

    @Test
    fun `extracts speeduino signature from secondary serial noise`() {
        assertEquals(
            "speeduino 202501",
            FirmwareHandshakeDomain.normalizeSignature("speeduino 202501BR? €?º"),
        )
        assertEquals(
            "speeduino 202501",
            FirmwareHandshakeDomain.normalizeSignature("eeduino202501_BR1G"),
        )
    }

    @Test
    fun `normalizes near valid speeduino names`() {
        assertEquals(
            "speeduino 202501",
            FirmwareHandshakeDomain.normalizeSignature("speduino 202501"),
        )
        assertEquals(
            "speeduino 202501",
            FirmwareHandshakeDomain.normalizeSignature("speeDuino 2025.01"),
        )
    }

    @Test
    fun `normalizes ms extra hr signatures`() {
        assertEquals(
            "MS/Extra format hr_10",
            FirmwareHandshakeDomain.normalizeSignature("MS/Extra format hr_10 **********"),
        )
        assertEquals(
            "MS/Extra format hr_11d",
            FirmwareHandshakeDomain.normalizeSignature("MS/Extra format hr_11d  ********"),
        )
    }

    @Test
    fun `normalizes ms1 extra signatures`() {
        assertEquals(
            "MS1/Extra format 029y3",
            FirmwareHandshakeDomain.normalizeSignature("MS1/Extra format 029y3 *********"),
        )
    }

    @Test
    fun `resolves consensus from noisy secondary serial samples`() {
        val consensus = FirmwareHandshakeDomain.resolveConsensus(
            listOf(
                "speeduino202501_BR1?",
                "eeduino202501_BR1G",
                "garbage",
            ),
        )

        assertEquals("speeduino 202501", consensus.signature)
        assertEquals(2, consensus.consensusHits)
    }

    @Test
    fun `rejects invalid consensus`() {
        assertFailsWith<Exception> {
            FirmwareHandshakeDomain.validateConsensus(
                consensus = FirmwareConsensus(signature = null, consensusHits = 0),
                samples = listOf("??", "garbage"),
            )
        }
    }

    @Test
    fun `accepts rusEFI with one readable sample and unknown noise`() {
        FirmwareHandshakeDomain.validateConsensus(
            consensus = FirmwareConsensus(
                signature = "rusEFI master.2026.05.08.f407-discovery.1054795294",
                consensusHits = 1,
            ),
            samples = listOf(
                "rusEFI master.2026.05.08.f407-discovery.1054795294",
                "Unknown",
                "Unknown",
                "Unknown",
            ),
        )
    }

    @Test
    fun `still rejects non rusEFI with one sample and unknown noise`() {
        assertFailsWith<Exception> {
            FirmwareHandshakeDomain.validateConsensus(
                consensus = FirmwareConsensus(
                    signature = "speeduino 202501",
                    consensusHits = 1,
                ),
                samples = listOf(
                    "speeduino 202501",
                    "Unknown",
                    "Unknown",
                ),
            )
        }
    }
}
