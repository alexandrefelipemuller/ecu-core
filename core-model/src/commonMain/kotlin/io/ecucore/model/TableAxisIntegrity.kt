package io.ecucore.model

/**
 * Checagem de integridade dos eixos (RPM/carga) de uma tabela recém-lida da ECU.
 *
 * Firmware Speeduino 202501+ rejeita eixos que não sejam estritamente crescentes (erro 0x84),
 * então uma tabela *lida* com eixo "indo e voltando" nunca é um estado válido da ECU: é lixo do
 * link (bytes soltos do live stream, resposta deslocada) decodificado como se fosse tabela.
 * Sem esta checagem a tela mostra o mapa quebrado sem nenhum alerta - e a próxima edição de
 * célula regravaria a página inteira corrompida na ECU.
 */
object TableAxisIntegrity {

    /** Descrições dos problemas encontrados; lista vazia = eixos íntegros. */
    fun problems(rpmBins: List<Int>, loadBins: List<Int>): List<String> = buildList {
        nonIncreasingIndices(rpmBins).takeIf { it.isNotEmpty() }?.let {
            add("RPM bins não crescentes nos índices $it: ${rpmBins.joinToString(",")}")
        }
        nonIncreasingIndices(loadBins).takeIf { it.isNotEmpty() }?.let {
            add("Load bins não crescentes nos índices $it: ${loadBins.joinToString(",")}")
        }
    }

    fun isCorrupted(rpmBins: List<Int>, loadBins: List<Int>): Boolean =
        nonIncreasingIndices(rpmBins).isNotEmpty() || nonIncreasingIndices(loadBins).isNotEmpty()

    fun problems(table: VeTable) = problems(table.rpmBins, table.loadBins)
    fun problems(table: IgnitionTable) = problems(table.rpmBins, table.loadBins)
    fun problems(table: AfrTable) = problems(table.rpmBins, table.loadBins)

    private fun nonIncreasingIndices(bins: List<Int>): List<Int> =
        (1 until bins.size).filter { bins[it] <= bins[it - 1] }
}
