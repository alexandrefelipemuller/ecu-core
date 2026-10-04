package io.ecucore.testing

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeSpeeduinoConnectionTest {

    @Test
    fun recordsSentAndReplaysQueuedChunksInOrder() = runTest {
        val c = FakeSpeeduinoConnection()
        c.connect()
        c.enqueue(byteArrayOf(1), byteArrayOf(2, 3))
        c.send(byteArrayOf(9))
        assertContentEquals(byteArrayOf(9), c.sent.single())
        assertContentEquals(byteArrayOf(1), c.receive(0))
        assertContentEquals(byteArrayOf(2, 3), c.receive(0))
        assertFailsWith<FakeSpeeduinoConnection.FakeConnectionTimeout> { c.receive(0) }
    }

    @Test
    fun injectedFailureFiresOnceThenRecovers() = runTest {
        val c = FakeSpeeduinoConnection()
        c.connect()
        c.enqueue(byteArrayOf(7))
        c.failNextReceive(IllegalStateException("boom"))
        assertFailsWith<IllegalStateException> { c.receive(0) }
        assertContentEquals(byteArrayOf(7), c.receive(0))
    }

    @Test
    fun notifiesConnectionStateAndRejectsSendWhenClosed() = runTest {
        val c = FakeSpeeduinoConnection()
        val states = mutableListOf<Boolean>()
        c.setOnConnectionStateChanged { states += it }
        assertFailsWith<IllegalStateException> { c.send(byteArrayOf(1)) }
        c.connect()
        c.disconnect()
        assertEquals(listOf(true, false), states)
        assertFalse(c.isConnected())
        c.clearInputBuffer()
        assertTrue(c.pendingChunks() == 0)
    }
}
