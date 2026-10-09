package io.ecucore

/**
 * A tabela foi gravada mas o read-back da RAM da ECU não confere com o que foi enviado, mesmo
 * após regravar. O burn NÃO foi executado: a EEPROM continua com a tabela anterior.
 *
 * [rolledBack] indica se a RAM foi devolvida ao valor anterior e confirmada por read-back. Se
 * `false`, a RAM pode estar divergente até o usuário regravar ou desligar a ECU.
 */
class PageWriteVerificationException(
    val pageId: Int,
    val label: String,
    val mismatchedOffsets: List<Int>,
    cause: Throwable? = null,
    val rolledBack: Boolean = false,
) : Exception(
    "$label: gravação não confirmada na página $pageId - ${mismatchedOffsets.size} bytes divergentes " +
        "no read-back (offsets ${mismatchedOffsets.take(8).joinToString()})" +
        (cause?.message?.let { "; último erro: $it" } ?: "") +
        ". Burn não executado" +
        if (rolledBack) ": valor anterior restaurado na ECU." else ". A RAM da ECU pode estar divergente; regrave ou reinicie a ECU.",
    cause,
)
