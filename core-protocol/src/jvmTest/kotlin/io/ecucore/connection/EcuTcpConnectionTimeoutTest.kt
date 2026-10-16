package io.ecucore.connection

import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Regressão: simulador lento caía a cada `A` porque o soTimeout curto da última fatia ficava no socket. */
class EcuTcpConnectionTimeoutTest {

    private fun withServer(block: (ServerSocket, EcuTcpConnection) -> Unit) {
        ServerSocket(0).use { server ->
            val conn = EcuTcpConnection("127.0.0.1", server.localPort, timeoutMs = 2000)
            try {
                runBlocking { conn.connect() }
                block(server, conn)
            } finally {
                conn.disconnect()
            }
        }
    }

    @Test
    fun `receive com tamanho nao herda o timeout curto de readAvailable`() = withServer { server, conn ->
        val peer = server.accept()
        conn.readAvailable(maxBytes = 16, timeoutMs = 50) // deixa soTimeout=50ms no socket
        Thread { Thread.sleep(400); peer.getOutputStream().write(ByteArray(8) { 1 }) }.start()

        assertEquals(8, conn.receive(8).size)
    }

    @Test
    fun `timeout sem nenhum byte usa a mensagem recuperavel`() = withServer { server, conn ->
        server.accept()
        val shortConn = conn // timeout do socket = 2000ms; nada é enviado
        val e = assertFailsWith<Exception> { shortConn.receive(8) }
        assertTrue(e.message!!.startsWith("Timeout: no data received"), e.message)
    }

    @Test
    fun `timeout parcial informa bytes recebidos`() = withServer { server, conn ->
        val peer = server.accept()
        peer.getOutputStream().apply { write(ByteArray(3)); flush() }
        val e = assertFailsWith<Exception> { conn.receive(8) }
        assertEquals("Timeout: expected 8 bytes, received 3", e.message)
    }
}
