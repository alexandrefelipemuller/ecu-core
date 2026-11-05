package io.ecucore

/**
 * O read-back depois de gravar uma tabela não bateu com o que foi enviado (ou a escrita deu
 * erro). Emitido em TODO passe com problema - inclusive quando a regravação seguinte corrige
 * ([recovered] = true), que é justamente o caso que antes passava em silêncio: a ECU escreveu
 * bytes que o app não mandou (lixo de um frame rejeitado por 0x82 virando 'W' legacy).
 *
 * @param mismatchedOffsets offsets relativos ao início da região gravada
 * @param expected/actual bytes (0..255) nesses offsets, na mesma ordem (limitado aos primeiros 16)
 * @param readBackSize bytes devolvidos pelo read-back (-1 = não aplicável / leitura falhou). Diferente
 *   do tamanho gravado indica resposta truncada ou com bytes extras.
 * @param shiftHint deslocamento k em que `readBack[i + k] == expected[i]` para quase todos os bytes
 *   (ver [PageWriteDiagnostics.detectShift]). Não-nulo = o conteúdo está certo, mas a resposta chegou
 *   desalinhada (lixo no buffer antes do quadro): falso positivo de verificação, não gravação errada.
 *   Nulo com muitos mismatches = o conteúdo realmente difere.
 * @param ioLockState quem tinha o canal da ECU na hora da verificação: `owned` (esta coroutine),
 *   `foreign_busy` (OUTRA operação, p.ex. live stream, usando o canal em paralelo - evidência de
 *   acesso concorrente) ou `free`.
 */
data class PageWriteAnomaly(
    val pageId: Int,
    val label: String,
    val pass: Int,
    val mismatchedOffsets: List<Int>,
    val expected: List<Int>,
    val actual: List<Int>,
    val writeErrorMessage: String?,
    val writeResponseCode: Int?,
    val recovered: Boolean,
    val readBackSize: Int = -1,
    val shiftHint: Int? = null,
    val ioLockState: String? = null,
)

/** Diagnósticos puros (sem I/O) sobre o resultado de um read-back. */
object PageWriteDiagnostics {
    private const val MAX_SHIFT = 8
    private const val MIN_OVERLAP = 8
    private const val MIN_MATCH_RATIO = 0.75

    /**
     * Procura um deslocamento k (≠ 0, |k| ≤ 8) tal que `actual[i + k] == expected[i]` na maior parte
     * da sobreposição e claramente melhor que o alinhamento original. Retorna `null` se não houver.
     */
    fun detectShift(expected: ByteArray, actual: ByteArray?): Int? {
        if (actual == null || expected.size < MIN_OVERLAP) return null
        val baseline = matchRatio(expected, actual, 0)
        var best: Int? = null
        var bestRatio = 0.0
        for (k in -MAX_SHIFT..MAX_SHIFT) {
            if (k == 0) continue
            val ratio = matchRatio(expected, actual, k)
            if (ratio > bestRatio) {
                bestRatio = ratio
                best = k
            }
        }
        return best.takeIf { bestRatio >= MIN_MATCH_RATIO && bestRatio > baseline }
    }

    private fun matchRatio(expected: ByteArray, actual: ByteArray, shift: Int): Double {
        var compared = 0
        var matched = 0
        for (i in expected.indices) {
            val j = i + shift
            if (j < 0 || j >= actual.size) continue
            compared++
            if (actual[j] == expected[i]) matched++
        }
        return if (compared < MIN_OVERLAP) 0.0 else matched.toDouble() / compared
    }
}
