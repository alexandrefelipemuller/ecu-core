package io.ecucore.model

/**
 * Antifurto da MegaSpeed MS2-X: trava a partida com o limitador de giro fixo em 0 RPM.
 *
 * Com `RevLimNormal2` = 0 e o corte de combustível ligado, a ECU corta a injeção sempre que
 * `RPM > limite` (corte "duro"), na partida e com o motor funcionando, já que a ECU só calcula
 * rotação com sincronismo. Para o corte valer qualquer que seja a configuração do usuário, a trava
 * também força em `RevLimOption`:
 * - `RevLimOption_fuelcut` = ligado: sem ele o limitador não corta combustível;
 * - `RevLimOption_fuelprog` = desligado: o corte progressivo usa outra conta e deixa de cortar;
 * - `RevLimCLTbased` = desligado: com ele o limite vem da curva por temperatura, que ignora o 0.
 * O destravamento devolve os três bits e o limite originais.
 *
 * A assinatura e os originais ficam nos 9 bytes livres da tabela 4 (offsets 721..729, entre
 * `can_poll_id3` e `hw_latency` no .ini): [MARKER_OFFSET] = assinatura com os 3 bits originais,
 * [REV_LIMIT_ORIGINAL_OFFSET] = `RevLimNormal2` original (S16). Travada = assinatura presente: assim
 * uma trava gravada pela metade continua travada e o destravamento devolve tudo.
 */
object MegaSpeedAntiTheft {
    const val TABLE_ID = 0x04

    /** Campo do .ini que a trava altera ou usa. */
    data class Field(val name: String, val offset: Int, val width: Int, val bitLow: Int? = null, val bitCount: Int = 0, val signed: Boolean = false) {
        val isBits get() = bitLow != null
        private val mask get() = (1 shl bitCount) - 1

        fun read(byte: Int): Int = if (bitLow == null) byte and 0xFF else (byte shr bitLow) and mask

        fun write(byte: Int, value: Int): Int {
            if (bitLow == null) return value and 0xFF
            return ((byte and (mask shl bitLow).inv()) or ((value and mask) shl bitLow)) and 0xFF
        }
    }

    val REV_LIMIT = Field("RevLimNormal2", offset = 656, width = 2, signed = true)
    val REV_LIMIT_CLT_BASED = Field("RevLimCLTbased", offset = 636, width = 1, bitLow = 3, bitCount = 1)
    val REV_LIMIT_FUEL_PROG = Field("RevLimOption_fuelprog", offset = 636, width = 1, bitLow = 4, bitCount = 1)
    val REV_LIMIT_FUEL_CUT = Field("RevLimOption_fuelcut", offset = 636, width = 1, bitLow = 5, bitCount = 1)

    /** Byte de `RevLimOption`, que guarda os três bits acima. */
    const val OPTIONS_OFFSET = 636
    private const val OPTIONS_BITS_LOW = 3
    private const val OPTIONS_BITS_MASK = 0b111

    /** Bytes livres da tabela 4: assinatura+modos, depois o limite original (S16 BE). */
    const val MARKER_OFFSET = 721
    const val REV_LIMIT_ORIGINAL_OFFSET = 722
    const val STASH_LENGTH = 3

    /** Vizinhos do espaço livre no .ini, conferidos antes de usar os bytes: `can_poll_id3` e `hw_latency`. */
    val STASH_NEIGHBORS = listOf(
        Field("can_poll_id3", offset = 720, width = 1),
        Field("hw_latency", offset = 730, width = 2),
    )

    /** Campos conferidos no .ini ativo antes de ler ou gravar. */
    val ALL_FIELDS = listOf(REV_LIMIT, REV_LIMIT_CLT_BASED, REV_LIMIT_FUEL_PROG, REV_LIMIT_FUEL_CUT)

    /** Assinatura nos 5 bits altos do [MARKER_OFFSET] (0xC0..0xC7); os 3 baixos guardam os bits originais. */
    const val MARKER = 0xC0
    private const val MARKER_MASK = 0xF8

    const val LOCKED_REV_LIMIT = 0
    const val LOCKED_CLT_BASED = 0
    const val LOCKED_FUEL_PROG = 0
    const val LOCKED_FUEL_CUT = 1

    /** Maior limite que o .ini aceita. */
    const val MAX_REV_LIMIT = 15000

    /** Valores do usuário trocados pela trava. */
    data class Original(val revLimit: Int, val cltBased: Int, val fuelProg: Int, val fuelCut: Int) {
        init {
            require(revLimit in 1..MAX_REV_LIMIT) { "RevLimNormal2 original inválido ($revLimit)" }
            require(cltBased in 0..1 && fuelProg in 0..1 && fuelCut in 0..1) { "modos originais inválidos" }
        }

        /** Bit 0 `RevLimCLTbased`, 1 `RevLimOption_fuelprog`, 2 `RevLimOption_fuelcut`. */
        fun packModes(): Int = cltBased or (fuelProg shl 1) or (fuelCut shl 2)

        /** Byte do [MARKER_OFFSET]: assinatura com os modos originais. */
        fun markerByte(): Int = MARKER or packModes()

        /** Valor guardado no celular: limite nos 16 bits baixos e [packModes] acima. */
        fun toRaw(): Int = revLimit or (packModes() shl 16)

        companion object {
            private fun fromModes(revLimit: Int, modes: Int) = Original(
                revLimit = revLimit,
                cltBased = modes and 1,
                fuelProg = (modes shr 1) and 1,
                fuelCut = (modes shr 2) and 1,
            )

            fun fromStored(markerByte: Int, revLimit: Int): Original =
                fromModes(revLimit, markerByte and MARKER_MASK.inv() and 0xFF)

            fun fromRaw(raw: Int): Original {
                require(raw in 0..0x7FFFF) { "valor original inválido ($raw)" }
                return fromModes(raw and 0xFFFF, raw shr 16)
            }
        }
    }

    fun isLocked(markerRaw: Int): Boolean = (markerRaw and MARKER_MASK) == MARKER

    /** Os três bits do `RevLimOption`, deslocados para a posição 0. */
    fun modesFrom(optionsByte: Int): Int = (optionsByte shr OPTIONS_BITS_LOW) and OPTIONS_BITS_MASK

    fun decode(bytes: ByteArray, offset: Int = 0): Int =
        (((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)).toShort().toInt()

    fun encode(raw: Int): ByteArray = byteArrayOf(((raw shr 8) and 0xFF).toByte(), (raw and 0xFF).toByte())

    /**
     * Cópia da página com a trava trocada pelos valores originais e a assinatura apagada, para
     * backup e restauração: um backup feito com a ECU travada não pode travar a ECU de novo ao ser
     * restaurado. Outras tabelas, páginas sem trava ou com original inválido voltam sem mudança.
     */
    fun sanitizePage(tableId: Int, page: ByteArray): ByteArray {
        if (tableId != TABLE_ID || page.size < REV_LIMIT.offset + REV_LIMIT.width ||
            page.size < REV_LIMIT_ORIGINAL_OFFSET + 2
        ) return page
        val marker = page[MARKER_OFFSET].toInt() and 0xFF
        if (!isLocked(marker)) return page
        val original = runCatching {
            Original.fromStored(marker, decode(page, REV_LIMIT_ORIGINAL_OFFSET) and 0xFFFF)
        }.getOrNull() ?: return page

        val clean = page.copyOf()
        encode(original.revLimit).copyInto(clean, REV_LIMIT.offset)
        var options = clean[OPTIONS_OFFSET].toInt() and 0xFF
        options = REV_LIMIT_CLT_BASED.write(options, original.cltBased)
        options = REV_LIMIT_FUEL_PROG.write(options, original.fuelProg)
        options = REV_LIMIT_FUEL_CUT.write(options, original.fuelCut)
        clean[OPTIONS_OFFSET] = options.toByte()
        for (i in MARKER_OFFSET until MARKER_OFFSET + STASH_LENGTH) clean[i] = 0
        return clean
    }
}
