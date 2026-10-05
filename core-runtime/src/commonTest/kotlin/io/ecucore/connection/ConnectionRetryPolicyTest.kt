package io.ecucore.connection

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionRetryPolicyTest {

    @Test
    fun `first success returns without retrying`() = runTest {
        val failures = mutableListOf<Int>()
        val value = ConnectionRetryPolicy(maxAttempts = 3, delayMs = 500).connect(onAttemptFailed = { a, _ -> failures += a }) { "ok" }
        assertEquals("ok", value)
        assertTrue(failures.isEmpty())
        assertEquals(0, currentTime)
    }

    @Test
    fun `retries with the configured delay until it succeeds`() = runTest {
        var calls = 0
        val failures = mutableListOf<Pair<Int, String?>>()
        val value = ConnectionRetryPolicy(maxAttempts = 3, delayMs = 500).connect(onAttemptFailed = { a, e -> failures += a to e.message }) {
            calls++
            if (calls < 3) throw IllegalStateException("falha $calls")
            calls
        }
        assertEquals(3, value)
        assertEquals(listOf<Pair<Int, String?>>(1 to "falha 1", 2 to "falha 2"), failures)
        assertEquals(1000, currentTime)
    }

    @Test
    fun `throws the last error when attempts are exhausted`() = runTest {
        var calls = 0
        val failures = mutableListOf<Int>()
        val error = assertFailsWith<IllegalStateException> {
            ConnectionRetryPolicy(maxAttempts = 3, delayMs = 100).connect<Nothing>(onAttemptFailed = { a, _ -> failures += a }) {
                calls++
                throw IllegalStateException("falha $calls")
            }
        }
        assertEquals("falha 3", error.message)
        assertEquals(3, calls)
        assertEquals(listOf(1, 2), failures) // a última falha não notifica nem espera
        assertEquals(200, currentTime)
    }

    @Test
    fun `a policy without attempts fails with a descriptive error`() = runTest {
        val error = assertFailsWith<IllegalStateException> { ConnectionRetryPolicy(maxAttempts = 0).connect<String> { "nunca" } }
        assertTrue(error.message!!.contains("no recorded error"))
    }

    @Test
    fun `default policy retries three times`() = runTest {
        var calls = 0
        assertFailsWith<RuntimeException> { ConnectionRetryPolicy().connect<Nothing> { calls++; throw RuntimeException("x") } }
        assertEquals(3, calls)
        assertEquals(2000, currentTime)
    }

    @Test
    fun `auto reconnect only after an unexpected drop`() = runTest {
        val c = AutoReconnectCoordinator(reconnectDelayMs = 750)
        assertTrue(c.shouldReconnect(wasConnected = true, isConnectedNow = false, manualDisconnect = false))
        assertFalse(c.shouldReconnect(wasConnected = true, isConnectedNow = false, manualDisconnect = true))
        assertFalse(c.shouldReconnect(wasConnected = true, isConnectedNow = true, manualDisconnect = false))
        assertFalse(c.shouldReconnect(wasConnected = false, isConnectedNow = false, manualDisconnect = false))
        c.awaitReconnectDelay()
        assertEquals(750, currentTime)
        AutoReconnectCoordinator().awaitReconnectDelay()
        assertEquals(2750, currentTime)
    }
}
