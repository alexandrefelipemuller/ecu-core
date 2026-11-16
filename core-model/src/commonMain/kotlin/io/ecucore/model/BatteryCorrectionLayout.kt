package io.ecucore.model

import io.ecucore.definition.IniDefinition

/**
 * Onde moram `brvBins` (tensões da correção de bateria) e `injBatRates` (taxas de correção do tempo de injetor)
 * no Speeduino: página 6 do .ini, offsets 15 e 21, 6 pontos cada. Não estão na página 1 (lá o offset 15 é o
 * `pinLayout` e 16..23 são tacômetro e enriquecimento de aceleração): ler/gravar por lá corrompia esses campos.
 *
 * Resolvido pelo .ini do firmware; [DEFAULT] só serve sem .ini carregado.
 */
data class BatteryCorrectionLayout(
    val page: Int,
    val pageLength: Int,
    val binsOffset: Int,
    val ratesOffset: Int,
) {
    companion object {
        const val POINTS = 6
        val DEFAULT = BatteryCorrectionLayout(page = 6, pageLength = 128, binsOffset = 15, ratesOffset = 21)

        /** `null` se o .ini não define os dois campos na mesma página (ex.: 201609 tem outro layout). */
        fun fromIni(definition: IniDefinition): BatteryCorrectionLayout? {
            fun field(name: String) = definition.fields.firstOrNull { it.name == name && it.offset != null && it.page != null }
            val bins = field("brvBins") ?: return null
            val rates = field("injBatRates") ?: return null
            if (bins.page != rates.page) return null
            val page = bins.page!!
            // `page = N` do .ini é 1-based; pageDefinitions.index é 0-based.
            val size = definition.pageDefinitions.firstOrNull { it.index == page - 1 }?.size ?: DEFAULT.pageLength
            return BatteryCorrectionLayout(page, size, bins.offset!!, rates.offset!!)
        }
    }
}
