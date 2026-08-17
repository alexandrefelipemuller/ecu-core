package io.ecucore.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Obd2DomainTest {
    @Test
    fun classifiesBrandHintsFromVinAndCalibration() {
        assertEquals(VehicleBrandHint.PSA, classifyVehicleBrandHint("VF7ABC1234567890", null))
        assertEquals(VehicleBrandHint.RENAULT, classifyVehicleBrandHint(null, "Renault Sirius"))
        assertEquals(VehicleBrandHint.VAG, classifyVehicleBrandHint("WVWZZZ1JZXW000001", null))
        assertEquals(VehicleBrandHint.UNKNOWN, classifyVehicleBrandHint("1HGCM82633A000000", "Generic"))
    }

    @Test
    fun resolvesProfileKeyUsingVinFirst() {
        assertEquals(
            "vin:VF7ABC1234567890|cal:PSA-ECU",
            resolveObd2ProfileKey(
                connectionInfo = "ignored",
                vin = "VF7ABC1234567890",
                calibrationId = "PSA-ECU",
            )
        )
        assertEquals(
            "cal:CAL123|conn:elm327",
            resolveObd2ProfileKey(
                connectionInfo = "elm327",
                vin = null,
                calibrationId = "CAL123",
            )
        )
    }

    @Test
    fun convertsOptimizationFeaturesToMaskAndBack() {
        val features = setOf(
            Obd2OptimizationFeature.FIXED_PROTOCOL,
            Obd2OptimizationFeature.FAST_TIMEOUT,
            Obd2OptimizationFeature.FAST_POLL_INTERVAL,
        )

        val mask = features.toMask()
        assertTrue(mask and (1 shl Obd2OptimizationFeature.FIXED_PROTOCOL.bitIndex) != 0)
        assertTrue(mask and (1 shl Obd2OptimizationFeature.FAST_TIMEOUT.bitIndex) != 0)
        assertTrue(mask and (1 shl Obd2OptimizationFeature.FAST_POLL_INTERVAL.bitIndex) != 0)
        assertEquals(features, featuresFromMask(mask))
    }

    @Test
    fun computesOptimizationProgressFraction() {
        val status = Obd2OptimizationStatus(
            appliedMask = 0,
            testedCount = 2,
            totalCount = 4,
            currentFeature = null,
            message = "Testing",
            testing = true,
        )

        assertEquals(0.5f, status.progressFraction)
        assertFalse(status.fallbackOccurred)
    }

    @Test
    fun decodesDtcBytesToStandardCodeFormat() {
        assertEquals("P0133", decodeDtc(0x01, 0x33))
        assertEquals("P0301", decodeDtc(0x03, 0x01))
        assertEquals("C0300", decodeDtc(0x43, 0x00))
        assertEquals("U0100", decodeDtc(0xC1, 0x00))
    }

    @Test
    fun parsesMode03ResponseSkippingEmptySlots() {
        val codes = parseDtcResponse("43 01 33 00 00", DtcStatus.ACTIVE)
        assertEquals(
            listOf(DtcCode(code = "P0133", description = DtcDescriptions.lookup("P0133"), status = DtcStatus.ACTIVE)),
            codes,
        )
    }

    @Test
    fun parsesMode03ResponseWithNoDtcs() {
        assertTrue(parseDtcResponse("43 00 00", DtcStatus.ACTIVE).isEmpty())
        assertTrue(parseDtcResponse("NO DATA", DtcStatus.ACTIVE).isEmpty())
    }

    @Test
    fun parsesMode07PendingResponse() {
        val codes = parseDtcResponse("47 03 01", DtcStatus.PENDING)
        assertEquals(
            listOf(DtcCode(code = "P0301", description = DtcDescriptions.lookup("P0301"), status = DtcStatus.PENDING)),
            codes,
        )
    }

    @Test
    fun recognizesClearDtcAcknowledgement() {
        assertTrue(isDtcClearAcknowledged("44"))
        assertTrue(isDtcClearAcknowledged("44\r\r"))
        assertFalse(isDtcClearAcknowledged("7F 04 12"))
        assertFalse(isDtcClearAcknowledged("NO DATA"))
    }
}
