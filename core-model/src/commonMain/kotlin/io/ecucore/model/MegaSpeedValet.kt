package io.ecucore.model

/**
 * Modo valet da MegaSpeed (MS2-X): trava o corte de giro num RPM baixo, com corte de ignição e retardo
 * progressivo, e devolve a calibração original ao desativar. É o equivalente, em campos do `.ini`, do
 * [asValetMode] do Speeduino (que usa páginas fixas).
 *
 * Os campos são os do diálogo `revlimiter2` (ver [MegaSpeedProtectionConfig]); o app lê [FIELDS] antes de
 * ativar, guarda o resultado como backup e regrava esses mesmos valores para desativar.
 */
object MegaSpeedValet {
    const val RPM_DEFAULT = VALET_MODE_RPM_DEFAULT
    const val RPM_MIN = VALET_MODE_RPM_MIN
    const val RPM_MAX = VALET_MODE_RPM_MAX

    const val FIELD_LIMIT = "RevLimNormal2"
    const val FIELD_SPARK_CUT = "RevLimOption_spkcut"
    const val FIELD_RETARD_MODE = "RevLimOption_retard"
    const val FIELD_RETARD_MAX = "RevLimMaxRtd"

    /** Campos que o valet altera (e que precisam de backup). */
    val FIELDS: List<String> = listOf(FIELD_LIMIT, FIELD_SPARK_CUT, FIELD_RETARD_MODE, FIELD_RETARD_MAX)

    private const val RETARD_PROGRESSIVE = 1.0
    private const val RETARD_DEG = 15.0 // mesmo retardo do valet do Speeduino

    /** Valores a gravar para o valet em [rpm] (limitado a [RPM_MIN]..[RPM_MAX]). */
    fun valuesFor(rpm: Int): Map<String, Double> = linkedMapOf(
        FIELD_LIMIT to rpm.coerceIn(RPM_MIN, RPM_MAX).toDouble(),
        FIELD_SPARK_CUT to 1.0,
        FIELD_RETARD_MODE to RETARD_PROGRESSIVE,
        FIELD_RETARD_MAX to RETARD_DEG,
    )

    /** O valet está ativo quando o limite lido cabe na faixa do valet (nenhuma calibração de rua fica tão baixo). */
    fun isActive(current: Map<String, Double>): Boolean {
        val limit = current[FIELD_LIMIT] ?: return false
        return limit >= RPM_MIN && limit <= RPM_MAX && current[FIELD_SPARK_CUT] == 1.0
    }

    /** O backup só serve se tiver todos os campos e um limite fora da faixa do valet. */
    fun isValidBackup(backup: Map<String, Double>?): Boolean =
        backup != null && FIELDS.all { it in backup } && !isActive(backup)
}
