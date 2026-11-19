package io.ecucore.protocol

import io.ecucore.model.EcuFamily
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Burn Speeduino no modo moderno: 'b' + página (o 'B' sem payload é rejeitado pelo firmware). */
class EcuProtocolSpeeduinoBurnTest {

    @Test
    fun `speeduino burn sends b with each distinct page id and never a bare B`() = runBlocking {
        val c = ScriptedConnection().apply { onSend = { listOf(codeFrame(0x04)) } }
        newProtocol(c, env = true, family = EcuFamily.SPEEDUINO).burnSpeeduinoPages(listOf(2, 5, 2))

        assertEquals(listOf('b', 'b'), c.sent.map { cmdOf(it) })
        assertContentEquals(bytes('b'.code, 0, 2), c.sent[0].copyOfRange(2, 5))
        assertContentEquals(bytes('b'.code, 0, 5), c.sent[1].copyOfRange(2, 5))
    }

    @Test
    fun `speeduino burn with no pending pages sends nothing`() = runBlocking {
        val c = ScriptedConnection().apply { onSend = { listOf(codeFrame(0x04)) } }
        newProtocol(c, env = true, family = EcuFamily.SPEEDUINO).burnSpeeduinoPages(emptyList())
        assertEquals(0, c.sent.size)
    }
}
