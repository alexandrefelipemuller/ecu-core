package io.ecucore.connection

import io.ecucore.shared.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import platform.posix.AF_UNSPEC
import platform.posix.EAGAIN
import platform.posix.EINPROGRESS
import platform.posix.EINTR
import platform.posix.EWOULDBLOCK
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.IPPROTO_TCP
import platform.posix.O_NONBLOCK
import platform.posix.POLLOUT
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.TCP_NODELAY
import platform.posix.addrinfo
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getsockopt
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.socket

/**
 * TCP connection (Wi-Fi) to a Speeduino/MegaSquirt ECU or its network simulator, using raw POSIX
 * sockets — Kotlin/Native exposes BSD sockets out of the box on Apple targets, so no cinterop
 * .def file or extra framework is needed.
 *
 * A background coroutine continuously drains the socket into a transport-agnostic
 * [ByteStreamBuffer]; [receive] just polls that buffer. This split (async byte source -> shared
 * buffer -> synchronous receive) is deliberate: it is the same shape a future BLE connection needs
 * (GATT notifications arrive one characteristic-update callback at a time, not as a readable
 * stream), so BleConnection can reuse [ByteStreamBuffer] as-is instead of re-implementing the
 * polling/timeout contract that [SpeeduinoProtocol] relies on.
 */
@OptIn(ExperimentalForeignApi::class)
class NativeTcpConnection(
    private val host: String,
    private val port: Int,
    private val timeoutMs: Int
) : ISpeeduinoConnection {

    companion object {
        private const val TAG = "NativeTcpIOS"
        private const val READ_CHUNK_SIZE = 4096
        private const val POLL_INTERVAL_MS = 5L
    }

    private var socketFd: Int = -1
    private val inbound = ByteStreamBuffer()
    private var readerJob: Job? = null
    private var readerScope: CoroutineScope? = null

    private var connected = false
    private var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    override suspend fun connect() = withContext(Dispatchers.Default) {
        try {
            Logger.i(TAG, "Conectando a $host:$port (timeout: ${timeoutMs}ms)")
            val fd = resolveAndConnect(host, port, timeoutMs)
            configureSocket(fd)
            socketFd = fd
            connected = true

            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            readerScope = scope
            readerJob = scope.launch { readLoop(fd) }

            onConnectionStateChanged?.invoke(true)
            Logger.i(TAG, "Conectado com sucesso")
        } catch (e: Exception) {
            connected = false
            onConnectionStateChanged?.invoke(false)
            Logger.e(TAG, "Erro na conexão: ${e.message}")
            throw Exception("Falha na conexão TCP: ${e.message}")
        }
    }

    private fun resolveAndConnect(host: String, port: Int, timeoutMs: Int): Int = memScoped {
        val hints = alloc<addrinfo>()
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = SOCK_STREAM
        hints.ai_protocol = IPPROTO_TCP

        val resultPtr = alloc<kotlinx.cinterop.CPointerVar<addrinfo>>()
        val gaiError = getaddrinfo(host, port.toString(), hints.ptr, resultPtr.ptr)
        if (gaiError != 0 || resultPtr.value == null) {
            throw Exception("Falha ao resolver $host:$port (getaddrinfo=$gaiError)")
        }

        try {
            var node = resultPtr.value
            var lastError: Exception? = null
            while (node != null) {
                val info = node.pointed
                val fd = socket(info.ai_family, info.ai_socktype, info.ai_protocol)
                if (fd < 0) {
                    lastError = Exception("socket() falhou: errno=$errno")
                    node = info.ai_next
                    continue
                }

                try {
                    setNonBlocking(fd, true)
                    val connectResult = connect(fd, info.ai_addr, info.ai_addrlen)
                    if (connectResult == 0) {
                        setNonBlocking(fd, false)
                        return@memScoped fd
                    }

                    if (errno != EINPROGRESS) {
                        throw Exception("connect() falhou: errno=$errno")
                    }

                    waitForConnectCompletion(fd, timeoutMs)
                    setNonBlocking(fd, false)
                    return@memScoped fd
                } catch (e: Exception) {
                    close(fd)
                    lastError = e
                    node = info.ai_next
                }
            }
            throw lastError ?: Exception("Nenhum endereço resolvido para $host:$port")
        } finally {
            freeaddrinfo(resultPtr.value)
        }
    }

    private fun waitForConnectCompletion(fd: Int, timeoutMs: Int) = memScoped {
        val pfd = alloc<pollfd>()
        pfd.fd = fd
        pfd.events = POLLOUT.toShort()

        val pollResult = platform.posix.poll(pfd.ptr, 1u, timeoutMs)
        if (pollResult <= 0) {
            throw Exception("Timeout ao conectar (${timeoutMs}ms)")
        }

        val sockError = alloc<kotlinx.cinterop.IntVar>()
        val sockErrorLen = alloc<platform.posix.socklen_tVar>()
        sockErrorLen.value = kotlinx.cinterop.sizeOf<kotlinx.cinterop.IntVar>().convert()
        getsockopt(fd, SOL_SOCKET, SO_ERROR, sockError.ptr, sockErrorLen.ptr)
        if (sockError.value != 0) {
            throw Exception("connect() falhou após poll: errno=${sockError.value}")
        }
    }

    private fun setNonBlocking(fd: Int, nonBlocking: Boolean) {
        val flags = fcntl(fd, F_GETFL, 0)
        val newFlags = if (nonBlocking) flags or O_NONBLOCK else flags and O_NONBLOCK.inv()
        fcntl(fd, F_SETFL, newFlags)
    }

    private fun configureSocket(fd: Int) = memScoped {
        val one = alloc<kotlinx.cinterop.IntVar>()
        one.value = 1
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, one.ptr, kotlinx.cinterop.sizeOf<kotlinx.cinterop.IntVar>().convert())
        // SO_NOSIGPIPE avoids the process receiving SIGPIPE (which crashes iOS apps by default)
        // when writing to a socket the peer already closed.
        platform.posix.setsockopt(
            fd,
            SOL_SOCKET,
            platform.posix.SO_NOSIGPIPE,
            one.ptr,
            kotlinx.cinterop.sizeOf<kotlinx.cinterop.IntVar>().convert()
        )
        // Keep the socket non-blocking; the reader loop polls with recv()+EAGAIN instead of
        // relying on SO_RCVTIMEO, so it stays responsive to disconnect() without a stuck syscall.
        setNonBlocking(fd, true)
    }

    private suspend fun readLoop(fd: Int) {
        val chunk = ByteArray(READ_CHUNK_SIZE)
        while (kotlinx.coroutines.currentCoroutineContext().isActive && connected) {
            val n = chunk.usePinned { pinned ->
                recv(fd, pinned.addressOf(0), READ_CHUNK_SIZE.convert(), 0)
            }
            when {
                n > 0 -> inbound.append(chunk.copyOf(n.toInt()))
                n == 0L -> {
                    Logger.w(TAG, "Conexão encerrada pelo peer")
                    handleTransportFailure("Conexão encerrada pelo peer")
                    break
                }
                else -> {
                    val err = errno
                    when {
                        // EAGAIN and EWOULDBLOCK share the same value on Darwin.
                        err == EAGAIN || err == EWOULDBLOCK -> delay(POLL_INTERVAL_MS)
                        err == EINTR -> { /* retry immediately */ }
                        else -> {
                            Logger.e(TAG, "Erro de leitura: errno=$err")
                            handleTransportFailure("Erro de leitura: errno=$err")
                            break
                        }
                    }
                }
            }
        }
    }

    private fun handleTransportFailure(message: String) {
        if (!connected) return
        connected = false
        onError?.invoke(message)
        onConnectionStateChanged?.invoke(false)
    }

    override fun disconnect() {
        connected = false
        val job = readerJob
        readerScope = null
        readerJob = null
        // Wait for readLoop to actually stop before closing the fd: cancel() only requests
        // cancellation, and readLoop only checks isActive between recv() calls on its own
        // thread — closing the fd while it's still mid-recv() races and crashes.
        if (job != null) {
            runCatching {
                runBlocking { job.cancelAndJoin() }
            }
        }
        if (socketFd >= 0) {
            close(socketFd)
            socketFd = -1
        }
        inbound.clear()
        onConnectionStateChanged?.invoke(false)
    }

    override fun send(data: ByteArray) {
        if (!connected || socketFd < 0) throw Exception("Não conectado")
        ConnectionTrace.tx("tcp", data)

        var offset = 0
        while (offset < data.size) {
            val n = data.usePinned { pinned ->
                send(socketFd, pinned.addressOf(offset), (data.size - offset).convert(), 0)
            }
            if (n > 0) {
                offset += n.toInt()
                continue
            }
            val err = errno
            if (n.toInt() < 0 && (err == EAGAIN || err == EWOULDBLOCK)) {
                // Kernel send buffer full; briefly block the calling thread and retry.
                platform.posix.usleep(2_000u)
                continue
            }
            handleTransportFailure("Erro ao enviar dados: errno=$err")
            throw Exception("Erro ao enviar dados: errno=$err")
        }
    }

    override fun receive(size: Int): ByteArray {
        if (!connected) throw Exception("Não conectado")
        val result = inbound.read(length = size, timeoutMs = timeoutMs.toLong())
        ConnectionTrace.rx("tcp", result)
        if (size > 0 && result.size < size) {
            throw Exception("Timeout: expected $size bytes, received ${result.size}")
        }
        if (size <= 0 && result.isEmpty()) {
            throw Exception("Timeout: no data received")
        }
        return result
    }

    override fun isConnected(): Boolean = connected

    override fun getConnectionInfo(): String {
        return "TCP: $host:$port (${if (connected) "conectado" else "desconectado"})"
    }

    override fun setOnConnectionStateChanged(callback: (Boolean) -> Unit) {
        onConnectionStateChanged = callback
    }

    override fun setOnError(callback: (String) -> Unit) {
        onError = callback
    }

    override fun clearInputBuffer() {
        inbound.clear()
    }

    override fun abortPendingRead() {
        inbound.abort()
    }

    override fun close() {
        disconnect()
    }
}
