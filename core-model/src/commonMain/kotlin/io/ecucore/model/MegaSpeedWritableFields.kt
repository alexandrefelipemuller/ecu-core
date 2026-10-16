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
    )
    private val sensors = listOf(
        MegaSpeedWritableField("map0", "MAP: valor em 0 V", "kPa", -100.0, 1000.0, 1),
        MegaSpeedWritableField("mapmax", "MAP: valor em 5 V", "kPa", -100.0, 1000.0, 1),
        MegaSpeedWritableField("baro0", "Baro: valor em 0 V", "kPa", -100.0, 1000.0, 1),
        MegaSpeedWritableField("batt0", "Bateria: valor mínimo", "V", 0.0, 50.0, 1),
        MegaSpeedWritableField("battmax", "Bateria: valor máximo", "V", 0.0, 60.0, 1),
    )

    fun forView(view: MegaSpeedConfigView): List<MegaSpeedWritableField> = when (view) {
        MegaSpeedConfigView.PROTECTION -> protection
        MegaSpeedConfigView.SENSORS -> sensors
        else -> emptyList()
    }

    fun byName(name: String): MegaSpeedWritableField? = (protection + sensors).firstOrNull { it.name == name }
}
