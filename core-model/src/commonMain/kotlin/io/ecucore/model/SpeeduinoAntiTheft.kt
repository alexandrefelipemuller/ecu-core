package io.ecucore.model

/**
 * Antifurto do Speeduino: trava a partida com o limitador de giro fixo em 0 RPM.
 *
 * Com `hardRevLim` = 0 o limite vira 0 RPM, e o laço principal corta faísca e injeção sempre que
 * `RPM > limite` (speeduino.ino, "Hard cut check"), na partida e com o motor funcionando, já que a
 * ECU só calcula rotação com sincronismo. Para o corte valer qualquer que seja a configuração do
 * usuário, a trava também força:
 * - `engineProtectType` = Both: com "Off" o limitador não age;
 * - `hardRevMode` = Fixed: com "Off" ou "Coolant based" o `hardRevLim` é ignorado;
 * - `hardCutType` = Full: no Rolling, o limite 0 somado ao delta negativo estoura em `uint16_t` na
 *   Mega2560 (int de 16 bits) e o corte nunca entra; até o 202305 o Rolling só corta faísca em rodízio;
 * - `engineProtectMaxRPM` = 0: até o 202305 o limitador só corta acima dessa rotação.
 * Conferido nos fontes 202207, 202305, 202310, 202402 e 202501. O antigo corte pelo desafogador
 * (`tpsflood`) só valia enquanto a ECU se via em partida: uma combustão com sobra de combustível
 * passava do `crankRPM` e o motor pegava.
 *
 * A assinatura e os originais ficam nos bytes livres da página 9 presentes do 202207 ao 202501, que o
 * firmware não usa: `unused10_110` = [MARKER] com os três modos originais nos bits baixos ([Original.packModes]),
 * `unused10_111` = `hardRevLim` original e `unused10_68` = `engineProtectMaxRPM` original.
 * Travada = assinatura presente: assim uma trava gravada pela metade continua travada e o
 * destravamento devolve tudo.
 */
object SpeeduinoAntiTheft {
    /** Campo do .ini que a trava altera ou usa: página, offset e, se for bits, o bit inicial e a largura. */
    data class Field(val name: String, val page: Int, val offset: Int, val bitLow: Int? = null, val bitCount: Int = 0) {
        val isBits get() = bitLow != null
        private val mask get() = (1 shl bitCount) - 1

        fun read(byte: Int): Int = if (bitLow == null) byte and 0xFF else (byte shr bitLow) and mask

        fun write(byte: Int, value: Int): Int {
            if (bitLow == null) return value and 0xFF
            return ((byte and (mask shl bitLow).inv()) or ((value and mask) shl bitLow)) and 0xFF
        }
    }

    val REV_LIMIT = Field("hardRevLim", page = 4, offset = 22)
    val PROTECT_MIN_RPM = Field("engineProtectMaxRPM", page = 4, offset = 120)
    val PROTECT_TYPE = Field("engineProtectType", page = 6, offset = 0, bitLow = 6, bitCount = 2)
    val REV_MODE = Field("hardRevMode", page = 9, offset = 166, bitLow = 0, bitCount = 2)
    val CUT_TYPE = Field("hardCutType", page = 1, offset = 26, bitLow = 3, bitCount = 1)

    val MARKER_FIELD = Field("unused10_110", page = 9, offset = 110)
    val REV_LIMIT_ORIGINAL_FIELD = Field("unused10_111", page = 9, offset = 111)
    val PROTECT_MIN_RPM_ORIGINAL_FIELD = Field("unused10_68", page = 9, offset = 68)

    /** Campos conferidos no .ini ativo antes de ler ou gravar. */
    val ALL_FIELDS = listOf(
        REV_LIMIT, PROTECT_MIN_RPM, PROTECT_TYPE, REV_MODE, CUT_TYPE,
        MARKER_FIELD, REV_LIMIT_ORIGINAL_FIELD, PROTECT_MIN_RPM_ORIGINAL_FIELD,
    )

    /**
     * Assinatura nos 3 bits altos do `unused10_110` (0xC0..0xDF). Não bate com 0x00 nem 0xFF (byte
     * livre nunca gravado) nem com 0xA7, a marca da trava antiga pelo desafogador.
     */
    const val MARKER = 0xC0
    private const val MARKER_MASK = 0xE0

    const val LOCKED_REV_LIMIT = 0
    const val LOCKED_PROTECT_MIN_RPM = 0
    const val LOCKED_PROTECT_TYPE = 3 // Both
    const val LOCKED_REV_MODE = 1 // Fixed
    const val LOCKED_CUT_TYPE = 0 // Full

    /** Valores do usuário trocados pela trava. */
    data class Original(
        val revLimit: Int,
        val protectMinRpm: Int,
        val protectType: Int,
        val revMode: Int,
        val cutType: Int,
    ) {
        init {
            require(revLimit in 1..0xFF) { "hardRevLim original inválido ($revLimit)" }
            require(protectMinRpm in 0..0xFF) { "engineProtectMaxRPM original inválido ($protectMinRpm)" }
            require(protectType in 0..3 && revMode in 0..3 && cutType in 0..1) { "modos originais inválidos" }
        }

        /** Bits 0-1 `hardRevMode`, 2-3 `engineProtectType`, 4 `hardCutType`. */
        fun packModes(): Int = revMode or (protectType shl 2) or (cutType shl 4)

        /** `unused10_110`: assinatura com os modos originais. */
        fun markerByte(): Int = MARKER or packModes()

        /** Valor guardado no celular: `hardRevLim`, `engineProtectMaxRPM` e [packModes], um byte cada. */
        fun toRaw(): Int = revLimit or (protectMinRpm shl 8) or (packModes() shl 16)

        companion object {
            private fun fromModes(revLimit: Int, protectMinRpm: Int, modes: Int) = Original(
                revLimit = revLimit,
                protectMinRpm = protectMinRpm,
                protectType = (modes shr 2) and 0x3,
                revMode = modes and 0x3,
                cutType = (modes shr 4) and 0x1,
            )

            fun fromStored(markerByte: Int, revLimit: Int, protectMinRpm: Int): Original =
                fromModes(revLimit, protectMinRpm, markerByte and MARKER_MASK.inv() and 0xFF)

            fun fromRaw(raw: Int): Original {
                require(raw in 0..0x1FFFFF) { "valor original inválido ($raw)" }
                return fromModes(raw and 0xFF, (raw shr 8) and 0xFF, raw shr 16)
            }
        }
    }

    fun isLocked(markerRaw: Int): Boolean = (markerRaw and MARKER_MASK) == MARKER

    private fun ByteArray.u8(offset: Int) = this[offset].toInt() and 0xFF

    /**
     * Páginas com os campos da trava de volta aos originais e a assinatura apagada, para backup e
     * restauração. Precisa das quatro páginas; sem elas, sem trava ou com original inválido, devolve
     * o mapa sem mudança.
     */
    fun sanitizePages(pages: Map<Int, ByteArray>): Map<Int, ByteArray> {
        if (ALL_FIELDS.any { f -> (pages[f.page]?.size ?: 0) <= f.offset }) return pages
        val stash = pages.getValue(MARKER_FIELD.page)
        if (!isLocked(stash.u8(MARKER_FIELD.offset))) return pages
        val original = runCatching {
            Original.fromStored(
                markerByte = stash.u8(MARKER_FIELD.offset),
                revLimit = stash.u8(REV_LIMIT_ORIGINAL_FIELD.offset),
                protectMinRpm = stash.u8(PROTECT_MIN_RPM_ORIGINAL_FIELD.offset),
            )
        }.getOrNull() ?: return pages

        val clean = ALL_FIELDS.map { it.page }.distinct().associateWith { pages.getValue(it).copyOf() }
        fun restore(f: Field, value: Int) {
            val page = clean.getValue(f.page)
            page[f.offset] = f.write(page.u8(f.offset), value).toByte()
        }
        restore(REV_LIMIT, original.revLimit)
        restore(PROTECT_MIN_RPM, original.protectMinRpm)
        restore(PROTECT_TYPE, original.protectType)
        restore(REV_MODE, original.revMode)
        restore(CUT_TYPE, original.cutType)
        for (f in listOf(MARKER_FIELD, REV_LIMIT_ORIGINAL_FIELD, PROTECT_MIN_RPM_ORIGINAL_FIELD)) restore(f, 0)
        return pages + clean
    }
}
