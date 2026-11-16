package io.ecucore.model

import io.ecucore.definition.IniDefinition

/**
 * Onde cada campo da marcha lenta do Speeduino mora (página e offsets), resolvido a partir do .ini do firmware.
 *
 * O .ini é a fonte da verdade: ler/gravar numa página escrita à mão já alterou DFCO e tabelas de boost/VVT
 * (marcha lenta usava as páginas 4 e 7; o .ini diz 6). Posições de bits dentro do byte (`iacAlgorithm` [0:2],
 * `iacStepTime` [3:5], `iacChannels` bit 6, `iacPWMdir` bit 7, `iacPWMrun` bit 1) seguem fixas e são guardadas
 * por `SpeeduinoPageContractTest`.
 *
 * [DEFAULT] é o layout do 202008+ e só serve de reserva quando nenhum .ini está carregado.
 */
data class IdleControlLayout(
    val page: Int,
    val pageLength: Int,
    val algorithm: Int,
    val fastTemp: Int,
    val stepHome: Int,
    val stepHyster: Int,
    /** `null` quando o firmware não tem `iacPWMrun` (202008). */
    val pwmRun: Int?,
    val frequency: Int,
    val clValues: Int,
    val bins: Int,
) {
    companion object {
        const val CURVE_POINTS = 10

        val DEFAULT = IdleControlLayout(
            page = 6, pageLength = 128, algorithm = 116, fastTemp = 117, stepHome = 118, stepHyster = 119,
            pwmRun = 60, frequency = 47, clValues = 64, bins = 94,
        )

        /**
         * Resolve o layout pelo .ini. Devolve `null` se o .ini não for de Speeduino moderno
         * (falta algum campo obrigatório ou os campos estão em páginas diferentes).
         */
        fun fromIni(definition: IniDefinition): IdleControlLayout? {
            fun field(name: String) = definition.fields.firstOrNull { it.name == name && it.offset != null && it.page != null }
            val algorithm = field("iacAlgorithm") ?: return null
            val required = listOf("iacFastTemp", "iacStepHome", "iacStepHyster", "idleFreq", "iacCLValues", "iacBins")
                .map { field(it) ?: return null }
            val page = algorithm.page!!
            if (required.any { it.page != page }) return null
            val pwmRun = field("iacPWMrun")?.takeIf { it.page == page }
            // `page = N` do .ini é 1-based; pageDefinitions.index é 0-based.
            val size = definition.pageDefinitions.firstOrNull { it.index == page - 1 }?.size ?: DEFAULT.pageLength
            val (fastTemp, stepHome, stepHyster, frequency, clValues, bins) = required.map { it.offset!! }
            return IdleControlLayout(
                page = page, pageLength = size, algorithm = algorithm.offset!!, fastTemp = fastTemp,
                stepHome = stepHome, stepHyster = stepHyster, pwmRun = pwmRun?.offset,
                frequency = frequency, clValues = clValues, bins = bins,
            )
        }

        private operator fun <T> List<T>.component6(): T = this[5]
    }
}
