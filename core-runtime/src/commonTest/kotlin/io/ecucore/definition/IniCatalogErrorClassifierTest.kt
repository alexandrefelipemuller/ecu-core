package io.ecucore.definition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IniCatalogErrorClassifierTest {

    private class SocketTimeoutException(message: String? = null) : Exception(message)
    private class UnknownHostException(message: String? = null) : Exception(message)
    private class ConnectException(message: String? = null) : Exception(message)

    private fun classify(e: Throwable) = IniCatalogErrorClassifier.classify(e)

    @Test
    fun `timeouts by type or message`() {
        assertEquals(IniCatalogErrorCategory.TIMEOUT, classify(SocketTimeoutException()))
        assertEquals(IniCatalogErrorCategory.TIMEOUT, classify(Exception("Request TIMEOUT")))
        assertEquals(IniCatalogErrorCategory.TIMEOUT, classify(Exception("read timed out")))
    }

    @Test
    fun `dns failures`() {
        assertEquals(IniCatalogErrorCategory.DNS, classify(UnknownHostException()))
        assertEquals(IniCatalogErrorCategory.DNS, classify(Exception("Unknown host: example.org")))
        assertEquals(IniCatalogErrorCategory.DNS, classify(Exception("DNS lookup failed")))
    }

    @Test
    fun `hash mismatches`() {
        for (m in listOf("sha256 mismatch", "bad hash", "Checksum error")) {
            assertEquals(IniCatalogErrorCategory.HASH_INVALID, classify(Exception(m)), m)
        }
    }

    @Test
    fun `not found`() {
        assertEquals(IniCatalogErrorCategory.NOT_FOUND, classify(Exception("HTTP 404")))
        assertEquals(IniCatalogErrorCategory.NOT_FOUND, classify(Exception("Definition not found")))
    }

    @Test
    fun `connection failures`() {
        assertEquals(IniCatalogErrorCategory.CONNECTION_FAILED, classify(ConnectException()))
        assertEquals(IniCatalogErrorCategory.CONNECTION_FAILED, classify(Exception("Connection reset")))
        assertEquals(IniCatalogErrorCategory.CONNECTION_FAILED, classify(Exception("refused")))
    }

    @Test
    fun `anything else is unknown`() {
        assertEquals(IniCatalogErrorCategory.UNKNOWN, classify(Exception("boom")))
        assertEquals(IniCatalogErrorCategory.UNKNOWN, classify(Exception()))
    }

    @Test
    fun `earlier categories win over later ones`() {
        assertEquals(IniCatalogErrorCategory.TIMEOUT, classify(Exception("connection timeout 404")))
        assertEquals(IniCatalogErrorCategory.HASH_INVALID, classify(Exception("hash not found")))
    }

    @Test
    fun `already active definition needs both signature and id to match`() {
        assertTrue(isAlreadyActiveDefinition("speeduino 202501", "id1", "speeduino 202501", "id1"))
        assertTrue(isAlreadyActiveDefinition("s", null, "s", null))
        assertFalse(isAlreadyActiveDefinition("s", "id1", "s", "id2"))
        assertFalse(isAlreadyActiveDefinition("s1", "id", "s2", "id"))
        assertFalse(isAlreadyActiveDefinition(null, "id", "s", "id"))
        assertFalse(isAlreadyActiveDefinition("s", "id", null, "id"))
    }
}
