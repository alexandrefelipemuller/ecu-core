package io.ecucore.model

/**
 * Antifurto do Speeduino: trava a partida com `tpsflood` (página 4, offset 18, U08 x 0,5 %) em 0.
 * O firmware corta a injeção na partida quando `TPS >= floodClear` (corrections.cpp) e só dispara o
 * pulso de escorva quando `TPS < floodClear` (scheduler.cpp), então com 0 não entra combustível nem
 * na escorva. Conferido no 202501 e no 202402.
 *
 * O campo não tem sinal, então a assinatura e o valor original ficam em dois bytes livres da página 9
 * (`unused10_110` = [MARKER], `unused10_111` = original), que o firmware não usa. Travada = desafogador
 * em 0 com a marca; desafogador em 0 sem a marca é configuração do usuário, não trava do app.
 */
object SpeeduinoAntiTheft {
    const val FLOOD_FIELD = "tpsflood"
    const val FLOOD_PAGE = 4
    const val FLOOD_OFFSET = 18
    const val MARKER_FIELD = "unused10_110"
    const val ORIGINAL_FIELD = "unused10_111"
    const val MARKER_PAGE = 9
    const val MARKER_OFFSET = 110
    const val ORIGINAL_OFFSET = 111

    const val MARKER = 0xA7
    const val LOCKED_FLOOD = 0

    /** 100,0 % em bruto: usado quando a marca não traz um original válido. */
    const val DEFAULT_ORIGINAL_RAW = 200

    fun isLocked(floodRaw: Int, markerRaw: Int): Boolean = floodRaw == LOCKED_FLOOD && markerRaw == MARKER

    fun originalFrom(storedRaw: Int): Int = if (storedRaw in 1..0xFF) storedRaw else DEFAULT_ORIGINAL_RAW

    /**
     * Páginas com a trava trocada pelo valor original e a marca apagada, para backup e restauração.
     * Precisa das duas páginas; sem elas, ou sem trava, devolve o mapa sem mudança.
     */
    fun sanitizePages(pages: Map<Int, ByteArray>): Map<Int, ByteArray> {
        val flood = pages[FLOOD_PAGE] ?: return pages
        val marker = pages[MARKER_PAGE] ?: return pages
        if (flood.size <= FLOOD_OFFSET || marker.size <= ORIGINAL_OFFSET) return pages
        if (!isLocked(flood[FLOOD_OFFSET].toInt() and 0xFF, marker[MARKER_OFFSET].toInt() and 0xFF)) return pages
        val cleanFlood = flood.copyOf().also { it[FLOOD_OFFSET] = originalFrom(marker[ORIGINAL_OFFSET].toInt() and 0xFF).toByte() }
        val cleanMarker = marker.copyOf().also {
            it[MARKER_OFFSET] = 0
            it[ORIGINAL_OFFSET] = 0
        }
        return pages + mapOf(FLOOD_PAGE to cleanFlood, MARKER_PAGE to cleanMarker)
    }
}
