package io.ecucore.model

/**
 * Antifurto da MegaSpeed MS2-X: trava a partida gravando o limite do desafogador (`floodClear`,
 * tabela 4, offset 588, S16 x 0,1 %) com valor negativo.
 *
 * Na partida a ECU corta a injeção quando `tps > floodClear` (desafogar). Como o TPS nunca fica
 * abaixo de 0 %, um limite negativo corta o combustível em toda partida, sem efeito com o motor já
 * funcionando (o corte só vale no modo de partida). O .ini e o TunerStudio só aceitam 0..100 %,
 * então um valor negativo é a assinatura da trava, e ele guarda o valor original:
 * `travado = -(original + 1)`, de forma que dá para destravar sem nada salvo no celular.
 */
object MegaSpeedAntiTheft {
    const val FIELD = "floodClear"
    const val TABLE_ID = 0x04
    const val OFFSET = 588
    const val WIDTH = 2

    /** Nomes de operação passados ao `writeGuard` do client ao travar e destravar. */
    const val OP_LOCK = "megaSpeedAntiTheftLock"
    const val OP_UNLOCK = "megaSpeedAntiTheftUnlock"

    /** 100,0 % em bruto: maior valor que o .ini aceita para o `floodClear`. */
    const val MAX_ORIGINAL_RAW = 1000

    fun isLocked(raw: Int): Boolean = raw < 0

    fun encodeLocked(originalRaw: Int): Int {
        require(originalRaw in 0..MAX_ORIGINAL_RAW) { "floodClear fora de 0..100 % (bruto $originalRaw)" }
        return -(originalRaw + 1)
    }

    /** Valor original guardado na trava. Fora da faixa (não veio do app) vira 100 %, que desliga o desafogador. */
    fun originalFrom(lockedRaw: Int): Int {
        require(isLocked(lockedRaw)) { "floodClear $lockedRaw não está travado" }
        return (-lockedRaw - 1).coerceAtMost(MAX_ORIGINAL_RAW)
    }

    fun decode(bytes: ByteArray, offset: Int = 0): Int =
        (((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)).toShort().toInt()

    fun encode(raw: Int): ByteArray = byteArrayOf(((raw shr 8) and 0xFF).toByte(), (raw and 0xFF).toByte())

    /**
     * Cópia da página com a trava trocada pelo valor original, para backup e restauração: um backup
     * feito com a ECU travada não pode travar a ECU de novo ao ser restaurado. Outras tabelas e
     * páginas sem trava voltam sem mudança.
     */
    fun sanitizePage(tableId: Int, page: ByteArray): ByteArray {
        if (tableId != TABLE_ID || page.size < OFFSET + WIDTH) return page
        val raw = decode(page, OFFSET)
        if (!isLocked(raw)) return page
        return page.copyOf().also { encode(originalFrom(raw)).copyInto(it, OFFSET) }
    }
}
