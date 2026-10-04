package io.ecucore.testing

import io.ecucore.connection.ISpeeduinoConnection

/**
 * Conexão scriptada para testes de protocolo/cliente.
 *
 * - [sent] guarda cada `send()` na ordem.
 * - [enqueue] empilha respostas; cada `receive()` consome o próximo pedaço.
 *   Sem resposta enfileirada, `receive()` lança [FakeConnectionTimeout] (simula timeout).
 * - [failNextReceive] injeta uma falha pontual (CRC ruim, desconexão, etc.).
 */
class FakeSpeeduinoConnection(
    private val modern: Boolean = true,
    private val tag: String? = null,
) : ISpeeduinoConnection {

    class FakeConnectionTimeout : RuntimeException("fake receive timeout")

    val sent = mutableListOf<ByteArray>()
    private val inbox = ArrayDeque<ByteArray>()
    private var pendingFailure: Throwable? = null
    private var connected = false
    private var stateCallback: ((Boolean) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null

    fun enqueue(vararg chunks: ByteArray) = chunks.forEach { inbox.addLast(it) }

    fun enqueueAll(chunks: List<ByteArray>) = chunks.forEach { inbox.addLast(it) }

    fun failNextReceive(error: Throwable) {
        pendingFailure = error
    }

    fun pendingChunks(): Int = inbox.size

    fun emitError(message: String) = errorCallback?.invoke(message)

    override suspend fun connect() {
        connected = true
        stateCallback?.invoke(true)
    }

    override fun disconnect() {
        if (!connected) return
        connected = false
        stateCallback?.invoke(false)
    }

    override fun send(data: ByteArray) {
        check(connected) { "send() em conexão fechada" }
        sent += data.copyOf()
    }

    override fun receive(size: Int): ByteArray {
        pendingFailure?.let { pendingFailure = null; throw it }
        return inbox.removeFirstOrNull() ?: throw FakeConnectionTimeout()
    }

    override fun isConnected(): Boolean = connected

    override fun getConnectionInfo(): String = "fake"

    override fun getConnectionProfileTag(): String? = tag

    override fun supportsModernProtocol(): Boolean = modern

    override fun setOnConnectionStateChanged(callback: (Boolean) -> Unit) {
        stateCallback = callback
    }

    override fun setOnError(callback: (String) -> Unit) {
        errorCallback = callback
    }

    override fun clearInputBuffer() = inbox.clear()
}
