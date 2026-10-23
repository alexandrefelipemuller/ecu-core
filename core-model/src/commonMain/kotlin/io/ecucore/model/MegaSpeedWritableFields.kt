package io.ecucore.model

/**
 * Campo de configuração da MegaSpeed MS2-X que o app pode gravar. Só entram campos cujo formato de
 * escrita foi confirmado em captura do TunerStudio (`w 00 <tabela> <offset> <len> <dados>` +
 * `b 00 <tabela>`), e com limites conservadores além dos do .ini.
 */
data class MegaSpeedWritableField(
    val name: String,
    val label: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val decimals: Int,
)

object MegaSpeedWritableFields {
    private val protection = listOf(
        MegaSpeedWritableField("RevLimNormal2", "Limite de rotação", "RPM", 1000.0, 15000.0, 0),
        MegaSpeedWritableField("RevLimNormal2_hyst", "Rotação de corte subtraindo", "RPM", 100.0, 1000.0, 0),
        MegaSpeedWritableField("RevLimCLTbased", "Limite por temperatura (0=Desligado, 1=Ligado)", "", 0.0, 1.0, 0),
        MegaSpeedWritableField("TpsBypassCLTRevlim", "TPS para ignorar a proteção por temperatura", "%", 0.0, 100.0, 1),
        MegaSpeedWritableField("RevLimOption_retard", "Método de retardo (0=Desligado, 1=Progressivo, 2=Ângulo fixo)", "", 0.0, 2.0, 0),
        MegaSpeedWritableField("RevLimMaxRtd", "Retardo máximo (progressivo)", "°", 0.0, 25.5, 1),
        MegaSpeedWritableField("RevLimRtdAng", "Retardo fixo", "°", -10.0, 25.5, 1),
        MegaSpeedWritableField("RevLimOption_spkcut", "Corte de ignição (0=Desligado, 1=Ligado)", "", 0.0, 1.0, 0),
        MegaSpeedWritableField("RevLimOption_fuelcut", "Corte de combustível (0=Desligado, 1=Ligado)", "", 0.0, 1.0, 0),
        MegaSpeedWritableField("RevLimOption_fuelprog", "Corte de combustível progressivo (0=Desligado, 1=Ligado)", "", 0.0, 1.0, 0),
    )
    private val sensors = listOf(
        MegaSpeedWritableField("map0", "MAP: valor em 0 V", "kPa", -100.0, 1000.0, 1),
        MegaSpeedWritableField("mapmax", "MAP: valor em 5 V", "kPa", -100.0, 1000.0, 1),
        MegaSpeedWritableField("baro0", "Baro: valor em 0 V", "kPa", -100.0, 1000.0, 1),
        MegaSpeedWritableField("baromax", "Baro: valor em 5 V", "kPa", -100.0, 1000.0, 1),
        MegaSpeedWritableField("batt0", "Bateria: valor mínimo", "V", 0.0, 50.0, 1),
        MegaSpeedWritableField("battmax", "Bateria: valor máximo", "V", 0.0, 60.0, 1),
        // Limite conservador (tensão do sensor): o .ini aceita -300..3276,7 V.
        MegaSpeedWritableField("oil0", "Pressão de óleo: tensão em 0,0 bar", "V", 0.0, 5.0, 1),
        MegaSpeedWritableField("oilmax", "Pressão de óleo: tensão em 10,0 bar", "V", 0.0, 5.0, 1),
        MegaSpeedWritableField("fuel0", "Pressão de combustível: tensão em 0,0 bar", "V", 0.0, 5.0, 1),
        MegaSpeedWritableField("fuelmax", "Pressão de combustível: tensão em 10,0 bar", "V", 0.0, 5.0, 1),
    )

    fun forView(view: MegaSpeedConfigView): List<MegaSpeedWritableField> = when (view) {
        MegaSpeedConfigView.PROTECTION -> protection
        MegaSpeedConfigView.SENSORS -> sensors
        else -> emptyList()
    }

    fun byName(name: String): MegaSpeedWritableField? = (protection + sensors).firstOrNull { it.name == name }
}
