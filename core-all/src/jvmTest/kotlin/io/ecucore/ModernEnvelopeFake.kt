package io.ecucore

/**
 * Utilitários para os fakes de conexão modelarem o envelope modern
 * (`[length 2B BE] + payload + [CRC32 4B BE]`), usado por firmware Speeduino 202201+.
 *
 * Desde que a escolha do enquadramento passou a vir da era do firmware (e não do transporte),
 * um fake que só responde a comando legacy não representa mais uma ECU moderna.
 */
internal object ModernEnvelopeFake {

    /** Detecta se [data] é um frame modern e devolve o byte de comando (payload[0]). */
    fun commandOf(data: ByteArray): Byte? {
        if (!isModernFrame(data)) return data.firstOrNull()
        return data.getOrNull(2)
    }

    fun isModernFrame(data: ByteArray): Boolean {
        if (data.size < 7) return false
        val declared = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        return declared == data.size - 6
    }

    /**
     * Monta a resposta em três pedaços, na mesma ordem em que `readModernResponse` lê:
     * comprimento (2 bytes), payload (`SERIAL_RC_OK` + dados) e CRC.
     *
     * O CRC vai zerado de propósito: o protocolo trata `0` como "sem CRC" e pula a validação.
     */
    fun framedResponse(data: ByteArray): List<ByteArray> {
        val body = byteArrayOf(0x00) + data
        val lengthBytes = byteArrayOf(
            ((body.size shr 8) and 0xFF).toByte(),
            (body.size and 0xFF).toByte(),
        )
        return listOf(lengthBytes, body, ByteArray(4))
    }
}
