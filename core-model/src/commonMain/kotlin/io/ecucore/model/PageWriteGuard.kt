package io.ecucore.model

/** A gravação mudaria bytes que a tela/escritor não possui; nada foi enviado à ECU. */
class UnownedBytesWriteException(message: String) : IllegalStateException(message)

/**
 * Última linha de defesa contra gravar no byte errado: cada escritor declara os offsets que possui e a guarda recusa a
 * gravação se a página nova diferir da lida em qualquer outro byte, ANTES de enviar qualquer coisa à ECU.
 *
 * Origem: a marcha lenta gravava as páginas 4 e 7 (o .ini diz 6), a bateria do injetor caía em campos da página 1 e a
 * tabela de dwell mandava a página 12 inteira montada do zero (zerando WMI e VVT2). Os testes de contrato pegam isso no CI;
 * esta guarda pega em campo, inclusive com firmwares e .ini que o CI nunca viu.
 */
object PageWriteGuard {
    /** Offsets absolutos (da página) que diferem entre [before] e [after] e não estão em [owned]. */
    fun unownedChanges(before: ByteArray, after: ByteArray, owned: Set<Int>, baseOffset: Int = 0): List<Int> {
        require(before.size == after.size) { "tamanhos diferentes: antes=${before.size} depois=${after.size}" }
        return before.indices.filter { before[it] != after[it] && (baseOffset + it) !in owned }.map { baseOffset + it }
    }

    /**
     * @param before bytes lidos da ECU (a região que será gravada, a partir de [baseOffset])
     * @param after bytes que serão gravados
     */
    fun requireOnlyOwned(label: String, page: Int, before: ByteArray, after: ByteArray, owned: Set<Int>, baseOffset: Int = 0) {
        if (before.size != after.size) {
            throw UnownedBytesWriteException("$label (página $page): tamanho gravado ${after.size} difere do lido ${before.size}")
        }
        val bad = unownedChanges(before, after, owned, baseOffset)
        if (bad.isNotEmpty()) {
            throw UnownedBytesWriteException(
                "$label (página $page): gravação alteraria ${bad.size} byte(s) que não pertencem a esta tela: " +
                    bad.take(12).joinToString(", ") + if (bad.size > 12) ", ..." else ""
            )
        }
    }
}
