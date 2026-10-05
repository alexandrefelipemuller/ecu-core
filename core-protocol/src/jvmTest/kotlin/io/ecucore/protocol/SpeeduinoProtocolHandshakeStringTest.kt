package io.ecucore.protocol

import io.ecucore.connection.ConnectionTrace
import io.ecucore.connection.ConnectionTraceSink
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Consultas de assinatura/produto ('Q'/'S'/'V'), legacy e modern, nas duas ordens de tentativa. */
class SpeeduinoProtocolHandshakeStringTest {

    private val sig = "speeduino 202310"

    /** Modern responde ao envelope com [modern]; legacy cru responde com [legacy]. */
    private fun conn(
        modern: ByteArray? = null,
        legacy: ByteArray? = null,
        legacyFirst: Boolean = false,
        info: String = "fake",
    ) = ScriptedConnection(
        modern = !legacyFirst,
        modernFallback = legacyFirst,
        prefersLegacy = legacyFirst,
        info = info,
    ).apply {
        onSend = { p -> (if (isFrame(p)) modern else legacy)?.let { listOf(it) } }
    }

    // ---- getFirmwareInfo: modern primeiro ----------------------------------------------------

    @Test
    fun `firmware info from modern envelope`() = runBlocking {
        val c = conn(modern = okFrame(ascii(sig)))
        assertEquals(sig, newProtocol(c).getFirmwareInfo())
        assertEquals('Q', cmdOf(c.sent.single()))
    }

    @Test
    fun `firmware candidate is normalized from direct or printable text`() = runBlocking {
        val direct = conn(modern = okFrame(ascii("MS1/Extra format 11d")))
        assertEquals("MS1/Extra format 11d", newProtocol(direct).getFirmwareInfo())
        val printable = conn(modern = okFrame(bytes(0) + ascii("MS1/Extra format 11d")))
        assertEquals("MS1/Extra format 11d", newProtocol(printable).getFirmwareInfo())
    }

    @Test
    fun `modern reply that is legacy ascii retries legacy and prefers it afterwards`() = runBlocking {
        val c = conn(modern = ascii(sig), legacy = ascii(sig))
        // 'sp...' vira comprimento 0x7370 > 2048 e é ASCII imprimível => ECU está em legacy
        c.onSend = { p -> listOf(ascii(sig)) }
        assertEquals(sig, newProtocol(c).getFirmwareInfo())
        assertTrue(c.clearCalls > 0)
        assertEquals(2, c.sent.size) // envelope + 'Q' cru
        assertEquals('Q', cmdOf(c.sent.last()))
    }

    @Test
    fun `modern failure then legacy success`() = runBlocking {
        val c = conn(modern = bytes(0xFF, 0xFF), legacy = ascii(sig))
        assertEquals(sig, newProtocol(c).getFirmwareInfo())
    }

    @Test
    fun `modern rejection and empty legacy yield Unknown`() = runBlocking {
        val c = conn(modern = codeFrame(0x83), legacy = null)
        assertEquals("Unknown", newProtocol(c).getFirmwareInfo())
        // modern + legacy para 'Q' e 'S'
        assertEquals(4, c.sent.size)
    }

    @Test
    fun `modern text that is not a signature is ignored`() = runBlocking {
        val c = conn(modern = okFrame(ascii("hello world")), legacy = ascii(sig))
        assertEquals(sig, newProtocol(c).getFirmwareInfo())
    }

    // ---- getFirmwareInfo: legacy primeiro ---------------------------------------------------

    @Test
    fun `legacy-first transport answers from legacy`() = runBlocking {
        val c = conn(legacy = ascii(sig), legacyFirst = true)
        assertEquals(sig, newProtocol(c).getFirmwareInfo())
        assertEquals(1, c.sent.size)
    }

    @Test
    fun `legacy-first transport falls back to modern when legacy fails`() = runBlocking {
        val c = conn(modern = okFrame(ascii(sig)), legacyFirst = true)
        c.receiveErrors += Exception("legacy down")
        assertEquals(sig, newProtocol(c).getFirmwareInfo())
        assertEquals(2, c.sent.size)
    }

    @Test
    fun `legacy-first transport with nothing working yields Unknown`() = runBlocking {
        val c = conn(legacyFirst = true)
        assertEquals("Unknown", newProtocol(c).getFirmwareInfo())
    }

    @Test
    fun `legacy-first modern rejection is ignored`() = runBlocking {
        val c = conn(modern = codeFrame(0x84), legacyFirst = true)
        assertEquals("Unknown", newProtocol(c).getFirmwareInfo())
    }

    // ---- getFirmwareInfoLegacyStrict ---------------------------------------------------------

    @Test
    fun `strict legacy firmware info`() {
        fun strict(reply: ByteArray?): String = runBlocking {
            val c = ScriptedConnection(modern = false).apply { onSend = { reply?.let { r -> listOf(r) } } }
            newProtocol(c).getFirmwareInfoLegacyStrict()
        }
        assertEquals(sig, strict(ascii(sig)))
        assertTrue(assertFailsWith<Exception> { strict(null) }.message!!.contains("empty"))
        assertTrue(assertFailsWith<Exception> { strict(ascii("    ")) }.message!!.contains("blank"))
    }

    // ---- parseLegacyStringResponse / parseModernFrameString ------------------------------------

    private fun strictOf(reply: ByteArray): String = runBlocking {
        val c = ScriptedConnection(modern = false).apply { onSend = { listOf(reply) } }
        newProtocol(c).getFirmwareInfoLegacyStrict()
    }

    @Test
    fun `legacy text selection prefers known firmware then direct then printable then raw`() {
        assertEquals(sig, strictOf(ascii("xx $sig yy")))
        assertEquals("ABCDEFGHIJ", strictOf(ascii("ABCDEFGHIJ")))
        assertEquals("Hello!", strictOf(bytes(0) + ascii("Hello!")))
        assertEquals("ab", strictOf(ascii("ab")))
    }

    @Test
    fun `legacy reply wrapped in a modern frame is unwrapped`() {
        assertEquals(sig, strictOf(okFrame(ascii(sig))))
        // texto terminado em NUL
        assertEquals(sig, strictOf(okFrame(ascii(sig) + bytes(0) + ascii("lixo"))))
        // sem prefixo OK
        assertEquals("ABCDEFG", strictOf(frame(ascii("ABCDEFG"))))
        // CRC zero = sem CRC; CRC errado só gera log
        assertEquals(sig, strictOf(frame(bytes(0) + ascii(sig), crc = bytes(0, 0, 0, 0))))
        assertEquals(sig, strictOf(frame(bytes(0) + ascii(sig), crc = bytes(1, 2, 3, 4))))
        // payload de 1 byte que não é código de erro
        assertEquals("A", strictOf(frame(bytes(0x41))))
    }

    @Test
    fun `malformed modern frames fall back to text heuristics`() {
        // frame truncado: ainda dá pra ler o texto imprimível
        val truncated = okFrame(ascii(sig)).let { it.copyOf(it.size - 2) }
        assertEquals(sig, strictOf(truncated))
        // comprimento zero e comprimento absurdo
        assertFailsWith<Exception> { strictOf(ByteArray(7)) }
        assertFailsWith<Exception> { strictOf(bytes(0x09, 0x00, 0, 0, 0, 0, 0)) }
    }

    @Test
    fun `modern error code in a legacy reply marks legacy handshake unsupported`() = runBlocking {
        for (code in listOf(0x80, 0x82, 0x83, 0x84, 0x85)) {
            val c = ScriptedConnection(modern = false).apply { onSend = { listOf(codeFrame(code)) } }
            val p = newProtocol(c)
            assertFailsWith<Exception> { p.getFirmwareInfoLegacyStrict() }
            // depois disso candidatos legacy nem vão ao fio
            assertEquals(emptyList(), p.getFirmwareInfoLegacyCandidates())
            assertEquals(1, c.sent.size, "code=$code")
        }
    }

    // ---- getFirmwareInfoLegacyCandidates -----------------------------------------------------

    @Test
    fun `legacy candidates collect successes and survive failures`() = runBlocking {
        val c = ScriptedConnection(modern = false).apply {
            onSend = { listOf(ascii(sig)) }
            receiveErrors += Exception("boom") // 'Q' falha
        }
        assertEquals(listOf(sig), newProtocol(c).getFirmwareInfoLegacyCandidates())

        val empty = ScriptedConnection(modern = false)
        assertEquals(emptyList(), newProtocol(empty).getFirmwareInfoLegacyCandidates())

        val both = ScriptedConnection(modern = false).apply { onSend = { listOf(ascii(sig)) } }
        assertEquals(listOf(sig, sig), newProtocol(both).getFirmwareInfoLegacyCandidates())
    }

    @Test
    fun `legacy candidate falls back to known firmware text`() = runBlocking {
        val c = ScriptedConnection(modern = false).apply { onSend = { listOf(ascii("MS2Extra MegaSpeed")) } }
        assertEquals(listOf("MS2Extra MegaSpeed", "MS2Extra MegaSpeed"), newProtocol(c).getFirmwareInfoLegacyCandidates())
    }

    // ---- getProductString ---------------------------------------------------------------------

    @Test
    fun `product string from modern with variants of payload text`() = runBlocking {
        fun product(payload: ByteArray) = runBlocking {
            newProtocol(conn(modern = okFrame(payload), legacy = null)).getProductString()
        }
        assertEquals(sig, product(ascii(sig)))
        assertEquals("TestProduct", product(ascii("TestProduct")))
        assertEquals("Hello", product(bytes(0) + ascii("Hello")))
        assertEquals("ab", product(ascii("ab")))
        // só espaços: ignorado nas duas tentativas -> Unknown
        assertEquals("Unknown", product(bytes(0x20, 0x20)))
    }

    @Test
    fun `product string retries after a stale error frame`() = runBlocking {
        val c = ScriptedConnection()
        var n = 0
        c.onSend = { listOf(if (n++ == 0) codeFrame(0x82) else okFrame(ascii("Prod1234"))) }
        assertEquals("Prod1234", newProtocol(c).getProductString())
        assertEquals(2, c.sent.size)
    }

    @Test
    fun `product string modern replies without text fall through to legacy`() = runBlocking {
        for (modernReply in listOf(okFrame(), frame(bytes(0x01, 0x41)), ByteArray(0))) {
            val c = conn(modern = modernReply.takeIf { it.isNotEmpty() }, legacy = ascii("LegacyProd"))
            assertEquals("LegacyProd", newProtocol(c).getProductString())
        }
        // 'Unknown' legado não conta
        val unknown = conn(legacy = ascii("Unknown"))
        assertEquals("Unknown", newProtocol(unknown).getProductString())
    }

    @Test
    fun `product string skips modern when session forbids it`() = runBlocking {
        val c = conn(legacy = ascii("LegacyProd"))
        assertEquals("LegacyProd", newProtocol(c, legacyPreferred = true).getProductString())
        assertTrue(c.sent.all { !isFrame(it) })
    }

    @Test
    fun `product string on legacy-first transport`() = runBlocking {
        assertEquals("LegacyProd", newProtocol(conn(legacy = ascii("LegacyProd"), legacyFirst = true)).getProductString())
        val modernOnly = conn(modern = okFrame(ascii("ModernProd")), legacyFirst = true)
        assertEquals("ModernProd", newProtocol(modernOnly).getProductString())
        val none = conn(legacyFirst = true)
        none.receiveErrors += Exception("x")
        assertEquals("Unknown", newProtocol(none).getProductString())
        val blankLegacy = conn(modern = codeFrame(0x84), legacy = ascii("Unknown"), legacyFirst = true)
        assertEquals("Unknown", newProtocol(blankLegacy).getProductString())
    }

    @Test
    fun `legacy handshake is skipped once proven modern-only`() = runBlocking {
        val c = ScriptedConnection(modern = false).apply { onSend = { listOf(codeFrame(0x80)) } }
        val p = newProtocol(c)
        assertFailsWith<Exception> { p.getFirmwareInfoLegacyStrict() }
        c.sent.clear()
        assertEquals("Unknown", p.getProductString())
        assertTrue(c.sent.isEmpty())
        p.setSessionLegacyPreferred(false) // nova sessão reabilita
        p.getProductString()
        assertTrue(c.sent.isNotEmpty())
    }

    // ---- trace -----------------------------------------------------------------------------------

    @Test
    fun `legacy handshake is traced with inferred transport`() = runBlocking {
        val lines = mutableListOf<Pair<String, String>>()
        ConnectionTrace.sink = object : ConnectionTraceSink {
            override fun onTx(transport: String, data: ByteArray) = Unit
            override fun onRx(transport: String, data: ByteArray) = Unit
            override fun onInfo(transport: String, message: String) { lines += transport to message }
            override fun onError(transport: String, message: String, throwable: Throwable?) = Unit
        }
        ConnectionTrace.enabled = true
        try {
            for (info in listOf("bluetooth:aa", "tcp:1.2.3.4", "usb-serial", "other")) {
                val c = ScriptedConnection(modern = false, info = info).apply { onSend = { listOf(ascii(sig)) } }
                newProtocol(c).getFirmwareInfoLegacyCandidates()
            }
        } finally {
            ConnectionTrace.enabled = false
            ConnectionTrace.sink = null
        }
        assertEquals(listOf("bluetooth", "tcp", "usb", "protocol"), lines.map { it.first }.distinct())
        assertTrue(lines.first().second.contains("legacy_handshake cmd=Q"))
    }
}
