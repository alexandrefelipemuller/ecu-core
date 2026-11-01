package io.ecucore

import io.ecucore.model.CutMethod
import io.ecucore.model.EngineProtectionConfig
import io.ecucore.model.ProtectionCut
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regressão do non-fatal "Engine Protection: page=6 pass=2 mismatches=1 rc=0x84" (offset 166):
 * o firmware tem a página 6 com 128 bytes e rejeita (RANGE_ERR) qualquer gravação além disso.
 */
class EcuClientEngineProtectionPagesTest {

    // pageSize do speeduino_202501.ini (páginas 1..10)
    private val pageSizes = mapOf(1 to 128, 2 to 288, 3 to 288, 4 to 128, 5 to 288, 6 to 128, 7 to 240, 8 to 384, 9 to 192, 10 to 192)

    private suspend fun connect(): Pair<EcuClient, ClientFakeEcu> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025)
        ecu.pageSizeLimits.putAll(pageSizes)
        // Marcadores nos offsets que o mapper antigo corrompia na página 6 (fanPin = 120, byte 26)
        ecu.load(6, 26, byteArrayOf(0x55))
        ecu.load(6, 120, byteArrayOf(0x55))
        val client = EcuClient(ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        client.connect()
        return client to ecu
    }

    @Test
    fun enablingCoolantProtectionIsWrittenToPage9WithoutOverflowingPage6() = runBlocking<Unit> {
        val (client, ecu) = connect()
        val current = client.readEngineProtectionConfig()
        assertTrue(current.extendedPagesLoaded)

        client.writeEngineProtectionConfig(
            current.copy(coolantProtectionEnabled = true, revLimiterEnabled = true, hardRevLimitEnabled = false),
            burn = true,
        )

        // nada foi gravado além do fim real de cada página
        ecu.writes.forEach { w ->
            val limit = pageSizes.getValue(w.id)
            assertTrue(w.offset + w.data.size <= limit, "página ${w.id}: gravou ${w.offset}+${w.data.size} > $limit")
        }
        assertEquals(2, ecu.mem(9)[166].toInt() and 0b11)
        // página 6: campos de outras páginas intactos
        assertEquals(0x55.toByte(), ecu.mem(6)[26])
        assertEquals(0x55.toByte(), ecu.mem(6)[120])
        val readBack = client.readEngineProtectionConfig()
        assertTrue(readBack.coolantProtectionEnabled)
    }

    @Test
    fun maxRpmAndOilProtectionLandOnPages4And10() = runBlocking<Unit> {
        val (client, ecu) = connect()
        val current = client.readEngineProtectionConfig()

        client.writeEngineProtectionConfig(
            current.copy(
                protectionCut = ProtectionCut.SPARK_ONLY,
                engineProtectEnabled = true,
                cutMethod = CutMethod.FULL,
                engineProtectionRpmMin = 2500,
                oilPressureProtectionEnabled = true,
                boostLimitEnabled = true,
            ),
            burn = true,
        )

        assertEquals(25, ecu.mem(4)[120].toInt() and 0xFF)
        assertEquals(0b110, ecu.mem(10)[135].toInt() and 0b110)
        assertEquals(1, (ecu.mem(6)[0].toInt() shr 6) and 0b11)
        assertEquals(0x55.toByte(), ecu.mem(6)[120])
    }

    @Test
    fun configWithoutExtendedPagesOnlyWritesPage6() = runBlocking<Unit> {
        val (client, ecu) = connect()
        val onlyPage6 = EngineProtectionConfig(
            protectionCut = ProtectionCut.FUEL_ONLY,
            cutMethod = CutMethod.FULL,
            engineProtectionRpmMin = 3000,
            engineProtectEnabled = true,
            revLimiterEnabled = false,
            boostLimitEnabled = false,
            oilPressureProtectionEnabled = false,
            afrProtectionEnabled = false,
            coolantProtectionEnabled = false,
        )
        assertFalse(onlyPage6.extendedPagesLoaded)

        client.writeEngineProtectionConfig(onlyPage6, burn = true)

        assertEquals(setOf(6), ecu.writes.map { it.id }.toSet())
    }
}
