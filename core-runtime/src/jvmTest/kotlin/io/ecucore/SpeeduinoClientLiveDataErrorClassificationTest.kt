package io.ecucore

import io.ecucore.connection.ISpeeduinoConnection
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Uma resposta legacy truncada é o caso comum num link Bluetooth ruidoso e deve ser tratada
 * como timeout recuperável (limpar buffer e repetir o comando legacy). Antes disso ela escapava
 * da classificação e escalava para o fallback modern, que injeta bytes de CRC no fio.
 */
class SpeeduinoClientLiveDataErrorClassificationTest {

    private val client = SpeeduinoClient(
        connection = StubConnection(),
        onDataReceived = {},
        onConnectionStateChanged = {},
        onError = {},
    )

    @Test
    fun `incomplete live data response is recoverable`() {
        assertTrue(
            client.isRecoverableLiveDataTimeout(
                Exception("Resposta de live data incompleta: 63/127 bytes")
            )
        )
    }

    @Test
    fun `partial and empty timeouts stay recoverable`() {
        assertTrue(client.isRecoverableLiveDataTimeout(Exception("Timeout: no data received")))
        assertTrue(
            client.isRecoverableLiveDataTimeout(Exception("Timeout: expected 127 bytes, received 0"))
        )
    }

    @Test
    fun `SERIAL_RC_TIMEOUT response code 0x80 on live data read is recoverable`() {
        // 0x80 significa que a ECU não montou a resposta a tempo (ocupada), não um erro de
        // protocolo. Os caminhos de write/burn já toleram 0x80; o read de live data deve seguir
        // o mesmo padrão em vez de escalar como falha não-fatal (ver crashes/usb_get_status/001-002).
        assertTrue(
            client.isRecoverableLiveDataTimeout(
                Exception("Erro ao ler live data modern: response code = 0x80")
            )
        )
    }

    @Test
    fun `unrelated errors are not recoverable`() {
        assertFalse(client.isRecoverableLiveDataTimeout(Exception("Broken pipe")))
        assertFalse(client.isRecoverableLiveDataTimeout(Exception("Não conectado")))
    }

    private class StubConnection : ISpeeduinoConnection {
        override suspend fun connect() = Unit
        override fun disconnect() = Unit
        override fun send(data: ByteArray) = Unit
        override fun receive(size: Int): ByteArray = ByteArray(0)
        override fun isConnected(): Boolean = false
        override fun getConnectionInfo(): String = "stub"
        override fun setOnConnectionStateChanged(callback: (Boolean) -> Unit) = Unit
        override fun setOnError(callback: (String) -> Unit) = Unit
    }
}
