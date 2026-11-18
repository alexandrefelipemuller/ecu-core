package io.ecucore.protocol

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** Assinatura ASCII legacy entregue em vários segmentos (ponte serial-TCP lenta, simulador). */
class EcuProtocolLegacyAsciiCoalesceTest {

    @Test
    fun `signature split across segments is joined before parsing`() = runBlocking {
        val c = ScriptedConnection(modern = false, modernFallback = false, prefersLegacy = true).apply {
            silenceWhenEmpty = true
            onSend = { "Speeduino 202402".map { byteArrayOf(it.code.toByte()) } }
        }
        assertEquals("speeduino 202402", newProtocol(c).getFirmwareInfo()) // assinatura normalizada
    }

    @Test
    fun `single segment signature is unchanged`() = runBlocking {
        val c = ScriptedConnection(modern = false, modernFallback = false, prefersLegacy = true).apply {
            silenceWhenEmpty = true
            onSend = { listOf(ascii("speeduino 202402")) }
        }
        assertEquals("speeduino 202402", newProtocol(c).getFirmwareInfo())
    }
}
