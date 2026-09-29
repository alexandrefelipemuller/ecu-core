package io.ecucore

/**
 * O read-back depois de gravar uma tabela não bateu com o que foi enviado (ou a escrita deu
 * erro). Emitido em TODO passe com problema - inclusive quando a regravação seguinte corrige
 * ([recovered] = true), que é justamente o caso que antes passava em silêncio: a ECU escreveu
 * bytes que o app não mandou (lixo de um frame rejeitado por 0x82 virando 'W' legacy).
 *
 * @param mismatchedOffsets offsets relativos ao início da região gravada
 * @param expected/actual bytes (0..255) nesses offsets, na mesma ordem (limitado aos primeiros 16)
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
)
