package io.ecucore.definition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IniCatalogMatcherTest {
    @Test
    fun exactWildcardMatchStillWins() {
        val entries = listOf(
            entry(id = "speeduino_202501", pattern = "speeduino 202501", priority = 100),
            entry(id = "speeduino_202402", pattern = "speeduino 202402", priority = 100),
            entry(id = "ms3_0592", pattern = "MS3 Format 0592.12*", priority = 100),
        )

        val match = IniCatalogMatcher.findBestMatchingEntry(
            signature = "MS3 Format 0592.12 release",
            entries = entries,
        )

        assertEquals("ms3_0592", match?.id)
    }

    @Test
    fun speeduinoFallsBackToNearestOlderDefinitionWhenExactIniIsMissing() {
        val entries = listOf(
            entry(id = "speeduino_202402", pattern = "speeduino 202402", priority = 100),
            entry(id = "speeduino_202501", pattern = "speeduino 202501", priority = 100),
            entry(id = "speeduino_202310", pattern = "speeduino 202310", priority = 100),
        )

        val match = IniCatalogMatcher.findBestMatchingEntry(
            signature = "speeduino 202503",
            entries = entries,
        )

        assertEquals("speeduino_202501", match?.id)
    }

    @Test
    fun speeduinoDottedVersionUsesNearestDefinition() {
        val entries = listOf(
            entry(id = "speeduino_202310", pattern = "speeduino 202310", priority = 100),
            entry(id = "speeduino_202402", pattern = "speeduino 202402", priority = 100),
        )

        val match = IniCatalogMatcher.findBestMatchingEntry(
            signature = "Speeduino 2024.01",
            entries = entries,
        )

        assertEquals("speeduino_202310", match?.id)
    }

    @Test
    fun speeduinoLocalizedSuffixUsesNumericFirmwareVersion() {
        val entries = listOf(
            entry(id = "speeduino_202402", pattern = "speeduino 202402", priority = 100),
            entry(id = "speeduino_202501_br1", pattern = "speeduino 202501BR1", priority = 100),
        )

        val match = IniCatalogMatcher.findBestMatchingEntry(
            signature = "speeduino 202501BR2",
            entries = entries,
        )

        assertEquals("speeduino_202501_br1", match?.id)
    }

    @Test
    fun speeduinoLocalizedSuffixFallsBackToBestMatchWhenSpecificPatternIsMissing() {
        val entries = listOf(
            entry(id = "speeduino_202402", pattern = "speeduino 202402", priority = 100),
            entry(id = "speeduino_202501", pattern = "speeduino 202501", priority = 100),
        )

        val match = IniCatalogMatcher.findBestMatchingEntry(
            signature = "speeduino 202501BR1",
            entries = entries,
        )

        assertEquals("speeduino_202501", match?.id)
    }

    @Test
    fun nonSpeeduinoDoesNotUseVersionFallback() {
        val entries = listOf(
            entry(id = "speeduino_202501", pattern = "speeduino 202501", priority = 100),
        )

        val match = IniCatalogMatcher.findBestMatchingEntry(
            signature = "unknown firmware 202503",
            entries = entries,
        )

        assertNull(match)
    }

    @Test
    fun ms2SuffixFallsBackToSameCommsCode() {
        val entries = listOf(
            entry(id = "megasquirt2-342h2", family = "mega", board = "ms2", pattern = "MS2Extra comms342h2", priority = 100),
            entry(id = "microsquirt-342hu", family = "mega", board = "microsquirt", pattern = "MS2Extra comms342hU", priority = 100),
        )

        val match = IniCatalogMatcher.findBestMatchingEntry(
            signature = "MS2Extra comms342h2_BR1",
            entries = entries,
        )

        assertEquals("megasquirt2-342h2", match?.id)
    }

    @Test
    fun ms3NewGenericVersionFallsBackToNearestGenericMs3() {
        val entries = listOf(
            entry(id = "maincontroller-0566.05", family = "mega", board = "ms3", pattern = "MS3 Format 0566.05*", priority = 100),
            entry(id = "megasquirt3-0592.12", family = "mega", board = "ms3", pattern = "MS3 Format 0592.12*", priority = 100),
            entry(id = "ms3pro-0601.16p", family = "mega", board = "ms3pro", pattern = "MS3 Format 0601.16P*", priority = 100),
        )

        val match = IniCatalogMatcher.findBestMatchingEntry(
            signature = "MS3 Format 0592.13_BR1",
            entries = entries,
        )

        assertEquals("megasquirt3-0592.12", match?.id)
    }

    @Test
    fun ms3ProSuffixKeepsBoardFamily() {
        val entries = listOf(
            entry(id = "megasquirt3-0592.12", family = "mega", board = "ms3", pattern = "MS3 Format 0592.12*", priority = 100),
            entry(id = "ms3pro-0601.16p", family = "mega", board = "ms3pro", pattern = "MS3 Format 0601.16P*", priority = 100),
            entry(id = "ms3pro-evo-0601.16e", family = "mega", board = "ms3pro-evo", pattern = "MS3 Format 0601.16E*", priority = 100),
        )

        val match = IniCatalogMatcher.findBestMatchingEntry(
            signature = "MS3 Format 0601.17P custom",
            entries = entries,
        )

        assertEquals("ms3pro-0601.16p", match?.id)
    }

    @Test
    fun rusefiBoardVariantFallsBackToSameBoardIni() {
        val entries = listOf(
            entry(id = "rusefi-proteus_f7", family = "rusefi", board = "proteus_f7", pattern = "rusEFI master.*proteus_f7*", priority = 100),
            entry(id = "rusefi-proteus_h7", family = "rusefi", board = "proteus_h7", pattern = "rusEFI master.*proteus_h7*", priority = 100),
        )

        val match = IniCatalogMatcher.findBestMatchingEntry(
            signature = "rusEFI master.2026.04.15.proteus-f7_BR1.123456",
            entries = entries,
        )

        assertEquals("rusefi-proteus_f7", match?.id)
    }


    private fun entry(
        id: String,
        pattern: String,
        priority: Int,
        family: String = "speeduino",
        board: String = "generic",
    ) = IniCatalogEntry(
        id = id,
        family = family,
        board = board,
        signaturePattern = pattern,
        version = id,
        url = "https://example.invalid/$id.ini",
        sha256 = "0",
        priority = priority,
    )


    @Test
    fun parsesManifestWithEscapesAndDefaults() {
        val json = """{"definitions":[{"id":"a","family":"speeduino","signature_pattern":"speeduino 202501","url":"https://x/a.ini","sha256":"abc","priority":7,"bundled":true,"min_app_version":3}]}"""
        val entry = IniCatalogMatcher.parseCatalog(json).single()
        assertEquals("a", entry.id)
        assertEquals(7, entry.priority)
        assertEquals("generic", entry.board)
        assertEquals("unknown", entry.version)
        assertEquals(3, entry.minAppVersion)
        assertEquals(emptyList(), IniCatalogMatcher.parseCatalog("{}"))
    }

    @Test
    fun wildcardSuffixIsOptionalWhenSignatureHasNoTrailingSpace() {
        assertEquals(true, IniCatalogMatcher.matchesSignature("MS/Extra format hr_10", "MS/Extra format hr_10 **********"))
        assertEquals(false, IniCatalogMatcher.matchesSignature("MS/Extra format hr_11", "MS/Extra format hr_10 **********"))
    }
}
