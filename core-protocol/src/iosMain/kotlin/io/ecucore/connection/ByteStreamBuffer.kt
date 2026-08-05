package io.ecucore.connection

import io.ecucore.shared.MonotonicClock
import platform.Foundation.NSLock
import platform.posix.usleep

/**
 * Transport-agnostic byte queue that bridges an asynchronous byte source to
 * [ISpeeduinoConnection]'s synchronous send/receive contract.
 *
 * TCP delivers bytes via a background socket-reading loop; BLE delivers them one GATT
 * notification at a time via a peripheral delegate callback. Both are just "some background
 * thread occasionally has more bytes" — this buffer is the single place that turns that into the
 * blocking, exact-size (or best-effort) reads [SpeeduinoProtocol] expects, so [BleConnection] only
 * needs to call [append] from its notification callback and reuse [read] as-is instead of
 * re-implementing the polling/timeout contract.
 *
 * Uses [NSLock] rather than `kotlinx.coroutines.Mutex` + `runBlocking`: [append]/[read]/[clear]
 * are plain (non-suspend) functions per [ISpeeduinoConnection], and `disconnect()` — which calls
 * [clear] — is invoked synchronously from UI code on the main thread (e.g. a "Disconnect" button
 * tap). `runBlocking` on Kotlin/Native spins up a new event loop bound to the calling thread;
 * nesting that inside iOS's main run loop is what caused an EXC_BAD_ACCESS crash on disconnect.
 * NSLock is a plain OS mutex with no event-loop involvement, so it's safe from any thread.
 */
class ByteStreamBuffer {
    private val lock = NSLock()
    private val buffer = ArrayDeque<Byte>()

    private var abortRequested = false

    fun append(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        lock.lock()
        try {
            for (b in bytes) buffer.addLast(b)
        } finally {
            lock.unlock()
        }
    }

    fun clear() {
        lock.lock()
        try {
            buffer.clear()
        } finally {
            lock.unlock()
        }
    }

    fun availableCount(): Int {
        lock.lock()
        try {
            return buffer.size
        } finally {
            lock.unlock()
        }
    }

    /** Requests that the read currently in progress (or the very next one) return early. */
    fun abort() {
        abortRequested = true
    }

    /**
     * Reads bytes matching [ISpeeduinoConnection.receive]'s contract:
     * - `length > 0`: blocks (polling) until exactly [length] bytes are available or [timeoutMs]
     *   elapses, returning whatever was accumulated so far on timeout (possibly short).
     * - `length <= 0`: returns whatever is immediately available, waiting briefly for at least
     *   one byte if the buffer is currently empty.
     */
    fun read(length: Int, timeoutMs: Long, pollIntervalMs: Long = 5L): ByteArray {
        abortRequested = false
        val pollIntervalUs = (pollIntervalMs * 1000L).toUInt()

        if (length <= 0) {
            val deadline = MonotonicClock.nowMillis() + timeoutMs
            while (true) {
                val chunk = drainAvailable()
                if (chunk.isNotEmpty() || abortRequested || MonotonicClock.nowMillis() >= deadline) {
                    return chunk
                }
                usleep(pollIntervalUs)
            }
        }

        val result = ByteArray(length)
        var filled = 0
        val deadline = MonotonicClock.nowMillis() + timeoutMs
        while (filled < length) {
            val need = length - filled
            val chunk = takeUpTo(need)
            if (chunk.isNotEmpty()) {
                chunk.copyInto(result, filled)
                filled += chunk.size
            }
            if (filled >= length || abortRequested) break
            if (MonotonicClock.nowMillis() >= deadline) break
            usleep(pollIntervalUs)
        }
        return if (filled == length) result else result.copyOf(filled)
    }

    private fun takeUpTo(maxBytes: Int): ByteArray {
        lock.lock()
        try {
            val take = minOf(maxBytes, buffer.size)
            return ByteArray(take) { buffer.removeFirst() }
        } finally {
            lock.unlock()
        }
    }

    private fun drainAvailable(): ByteArray {
        lock.lock()
        try {
            val take = buffer.size
            return ByteArray(take) { buffer.removeFirst() }
        } finally {
            lock.unlock()
        }
    }
}
