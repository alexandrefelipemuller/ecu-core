package io.ecucore.model

/**
 * Antifurto (trava de partida por senha), comum às famílias: cada uma trava pelo desafogador
 * ([MegaSpeedAntiTheft], [SpeeduinoAntiTheft]).
 */
object EcuAntiTheft {
    /** Nomes de operação passados ao `writeGuard` do client ao travar e destravar. */
    const val OP_LOCK = "antiTheftLock"
    const val OP_UNLOCK = "antiTheftUnlock"

    val SUPPORTED_FAMILIES: Set<EcuFamily> = setOf(EcuFamily.MEGASPEED, EcuFamily.SPEEDUINO)

    /** Páginas de backup/restauração com a trava trocada pelo valor original. */
    fun sanitizePages(family: EcuFamily, pages: Map<Int, ByteArray>): Map<Int, ByteArray> = when (family) {
        EcuFamily.MEGASPEED -> pages.mapValues { (id, page) -> MegaSpeedAntiTheft.sanitizePage(id, page) }
        EcuFamily.SPEEDUINO -> SpeeduinoAntiTheft.sanitizePages(pages)
        else -> pages
    }
}
