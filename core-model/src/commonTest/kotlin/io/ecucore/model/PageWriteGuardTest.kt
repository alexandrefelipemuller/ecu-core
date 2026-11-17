package io.ecucore.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [PageWriteGuard] e a propriedade que a sustenta: cada escritor só altera os offsets que declara como seus.
 * A propriedade usa valores lidos de uma página aleatória aplicados sobre OUTRA página aleatória, então qualquer
 * campo que escreva fora do declarado aparece como byte alterado.
 */
class PageWriteGuardTest {

    // ---- a guarda em si --------------------------------------------------------------------------------------------

    @Test fun acceptsChangesOnlyInOwnedOffsets() {
        val before = ByteArray(16)
        val after = before.copyOf().also { it[3] = 9; it[7] = 1 }
        PageWriteGuard.requireOnlyOwned("t", 6, before, after, setOf(3, 7))
        assertEquals(emptyList(), PageWriteGuard.unownedChanges(before, after, setOf(3, 7)))
    }

    @Test fun refusesAChangeOutsideTheOwnedOffsets_andNamesTheOffset() {
        val before = ByteArray(16)
        val after = before.copyOf().also { it[3] = 9; it[10] = 1 }
        val e = assertFailsWith<UnownedBytesWriteException> {
            PageWriteGuard.requireOnlyOwned("Idle", 4, before, after, setOf(3))
        }
        assertTrue("10" in e.message.orEmpty() && "Idle" in e.message.orEmpty(), e.message)
    }

    @Test fun offsetsAreAbsoluteWhenOnlyARegionIsWritten() {
        // região de 1 byte gravada a partir do offset 0 da página 9 (serial secundária)
        PageWriteGuard.requireOnlyOwned("s", 9, byteArrayOf(1), byteArrayOf(2), setOf(0), baseOffset = 0)
        assertFailsWith<UnownedBytesWriteException> {
            PageWriteGuard.requireOnlyOwned("s", 9, byteArrayOf(1), byteArrayOf(2), setOf(0), baseOffset = 5)
        }
    }

    @Test fun refusesAWriteWithADifferentSize() {
        assertFailsWith<UnownedBytesWriteException> {
            PageWriteGuard.requireOnlyOwned("t", 1, ByteArray(128), ByteArray(64), (0..127).toSet())
        }
    }

    // ---- propriedade: cada escritor só toca nos seus offsets -------------------------------------------------------

    private val seeds = (1..60).map { Random(it * 7919) }
    private fun random(size: Int, rnd: Random) = ByteArray(size).also { rnd.nextBytes(it) }

    private fun assertOwned(label: String, base: ByteArray, written: ByteArray, owned: Set<Int>) {
        assertEquals(base.size, written.size, "$label: tamanho")
        val bad = PageWriteGuard.unownedChanges(base, written, owned)
        assertTrue(bad.isEmpty(), "$label alterou bytes que não declara: $bad")
    }

    @Test fun idleControlOnlyTouchesItsOffsets() {
        val layout = IdleControlLayout.DEFAULT
        for (rnd in seeds) {
            val a = random(128, rnd); val b = random(128, rnd)
            assertOwned("Marcha lenta", a, IdleControlSettings.fromPage(b, layout).applyToPage(a, layout), layout.ownedOffsets)
            val noRun = layout.copy(pwmRun = null)
            assertOwned("Marcha lenta 202008", a, IdleControlSettings.fromPage(b, noRun).applyToPage(a, noRun), noRun.ownedOffsets)
        }
    }

    @Test fun batteryCorrectionOnlyTouchesBrvBinsAndInjBatRates() {
        val layout = BatteryCorrectionLayout.DEFAULT
        for (rnd in seeds) {
            val a = random(128, rnd); val b = random(128, rnd)
            val ec = EngineConstants.fromPage1(random(128, rnd)).withBatteryCorrection(b, layout)
            assertOwned("Bateria", a, ec.applyBatteryCorrectionToPage(a, layout), layout.ownedOffsets)
        }
    }

    @Test fun engineConstantsPage1OnlyTouchesItsOffsets() {
        for (rnd in seeds) {
            val a = random(128, rnd); val b = random(128, rnd)
            assertOwned("Constantes do motor", a, EngineConstants.fromPage1(b).applyToPage1(a), EngineConstants.PAGE1_OWNED_OFFSETS)
        }
    }

    @Test fun triggerOnlyTouchesItsOffsets() {
        for (rnd in seeds) {
            val a = random(128, rnd); val b = random(128, rnd)
            assertOwned("Trigger", a, TriggerSettings.fromPageData(b).toPageData(a), TriggerSettings.PAGE4_OWNED_OFFSETS)
        }
    }

    @Test fun engineProtectionOnlyTouchesItsOffsetsOnEveryPage() {
        for (rnd in seeds) for (hasPage9 in listOf(true, false)) {
            val era = FirmwareEra.MODERN_2025
            val base = EngineProtectionMapper.fromPage(random(128, rnd), era)
            val cfg = EngineProtectionMapper.withExtendedPages(
                base, era, random(128, rnd), random(128, rnd), random(192, rnd), random(192, rnd), hasPage9,
            )
            val p6 = random(128, rnd); val p1 = random(128, rnd); val p4 = random(128, rnd)
            val p9 = random(192, rnd); val p10 = random(192, rnd)
            assertOwned("Proteção p6", p6, EngineProtectionMapper.applyToPage(p6, cfg), EngineProtectionMapper.PAGE6_OWNED_OFFSETS)
            assertOwned("Proteção p1", p1, EngineProtectionMapper.applyToPage1(p1, cfg), EngineProtectionMapper.PAGE1_OWNED_OFFSETS)
            assertOwned("Proteção p4", p4, EngineProtectionMapper.applyToPage4(p4, cfg), EngineProtectionMapper.PAGE4_OWNED_OFFSETS)
            assertOwned("Proteção p9", p9, EngineProtectionMapper.applyToPage9(p9, cfg, era, hasPage9), EngineProtectionMapper.PAGE9_OWNED_OFFSETS)
            assertOwned("Proteção p10", p10, EngineProtectionMapper.applyToPage10(p10, cfg), EngineProtectionMapper.PAGE10_OWNED_OFFSETS)
        }
    }

    @Test fun closedLoopOnlyTouchesItsOffsets() {
        for (rnd in seeds) {
            val era = FirmwareEra.MODERN_2025
            val cfg = ClosedLoopCorrectionMapper.withMapWindow(
                ClosedLoopCorrectionMapper.fromPage(random(128, rnd), era), random(192, rnd),
            )
            val p6 = random(128, rnd); val p9 = random(192, rnd)
            assertOwned("Malha fechada p6", p6, ClosedLoopCorrectionMapper.applyToPage(p6, cfg, era), ClosedLoopCorrectionMapper.PAGE6_OWNED_OFFSETS)
            assertOwned("Malha fechada p9", p9, ClosedLoopCorrectionMapper.applyMapWindowToPage9(p9, cfg), ClosedLoopCorrectionMapper.PAGE9_OWNED_OFFSETS)
        }
    }

    @Test fun secondarySerialOnlyTouchesItsByte() {
        for (rnd in seeds) {
            val original = rnd.nextInt(256)
            val cfg = SecondarySerialConfig.fromByte(rnd.nextInt(256))
            val updated = cfg.applyToByte(original)
            // um único byte é gravado, em OFFSET; a guarda confere esse byte contra o declarado
            PageWriteGuard.requireOnlyOwned(
                "Serial secundária", SecondarySerialConfig.PAGE_NUMBER, byteArrayOf(original.toByte()), byteArrayOf(updated.toByte()),
                SecondarySerialConfig.OWNED_OFFSETS, SecondarySerialConfig.OFFSET,
            )
        }
    }

    @Test fun dwellTableKeepsWmiAndVvt2_andOnlyTouchesItsOffsets() {
        for (rnd in seeds) {
            val a = random(192, rnd)
            val table = DwellTable.createDefault()
            val written = table.applyToPage(a)
            assertOwned("Dwell", a, written, DwellTable.PAGE12_OWNED_OFFSETS)
            // WMI (0..79) e VVT2 (80..159) intactos
            assertContentEquals(a.copyOfRange(0, 160), written.copyOfRange(0, 160))
        }
    }
}
