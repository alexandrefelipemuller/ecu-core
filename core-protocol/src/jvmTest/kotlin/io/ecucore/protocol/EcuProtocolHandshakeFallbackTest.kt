package io.ecucore.protocol

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [EcuProtocol.getHandshakeFallbackReason] alimenta o evento de Analytics
 * `speeduino_handshake_fallback`: handshake que tentou o envelope moderno, falhou e só então
 * respondeu em legacy. Sem fallback = `null`.
 */
class EcuProtocolHandshakeFallbackTest {

    private val sig = "speeduino 202310"

    private fun conn(modern: ByteArray?, legacy: ByteArray?, legacyFirst: Boolean = false) =
        ScriptedConnection(modern = !legacyFirst, modernFallback = legacyFirst, prefersLegacy = legacyFirst).apply {
            onSend = { p -> (if (isFrame(p)) modern else legacy)?.let { listOf(it) } }
        }

    @Test
    fun `modern handshake that works reports no fallback`() = runBlocking {
        val p = newProtocol(conn(modern = okFrame(ascii(sig)), legacy = null))
        p.getFirmwareInfo()
        assertNull(p.getHandshakeFallbackReason())
    }

    @Test
    fun `envelope answered with raw ascii reports legacy_ascii_reply`() = runBlocking {
        val c = conn(modern = ascii(sig), legacy = ascii(sig))
        c.onSend = { listOf(ascii(sig)) }
        val p = newProtocol(c)
        p.getFirmwareInfo()
        assertEquals("legacy_ascii_reply", p.getHandshakeFallbackReason())
    }

    @Test
    fun `silent envelope then legacy answer reports modern_timeout`() = runBlocking {
        val c = conn(modern = null, legacy = ascii(sig))
        c.readAvailableErrors += Exception("Timeout: expected 2 bytes, received 0 (modern length cmd=0x51)")
        val p = newProtocol(c)
        p.getFirmwareInfo()
        assertEquals("modern_timeout", p.getHandshakeFallbackReason())
    }

    @Test
    fun `garbage envelope then legacy answer reports modern_error`() = runBlocking {
        val p = newProtocol(conn(modern = bytes(0xFF, 0xFF), legacy = ascii(sig)))
        p.getFirmwareInfo()
        assertEquals("modern_error", p.getHandshakeFallbackReason())
    }

    @Test
    fun `legacy-first transport has no modern attempt so no fallback`() = runBlocking {
        val p = newProtocol(conn(modern = null, legacy = ascii(sig), legacyFirst = true))
        p.getFirmwareInfo()
        assertNull(p.getHandshakeFallbackReason())
    }

    @Test
    fun `reset clears the reason`() = runBlocking {
        val p = newProtocol(conn(modern = bytes(0xFF, 0xFF), legacy = ascii(sig)))
        p.getFirmwareInfo()
        p.resetHandshakeFallbackReason()
        assertNull(p.getHandshakeFallbackReason())
    }
}
