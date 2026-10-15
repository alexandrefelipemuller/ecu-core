package io.ecucore.model

/**
 * Rótulos de enum da MegaSpeed MS2-X (CostaMegaSpeed) extraídos do `.ini` oficial
 * (`MegaSpeedMS2-X.ini`, campo `spk_mode0`, página 0x04 offset 989 bits [0:5]).
 *
 * A posição na lista É o valor bruto gravado na ECU. Não usar a lista do MS2Extra genérico:
 * ela tem só 36 itens e desalinha modos como "Fuel Only" (31), "LS1" (40) e "HD 32-2" (54).
 */
object MegaSpeedTriggerLabels {
    const val INVALID: String = "INVALID"

    val SPARK_MODES: List<String> = listOf(
        "EDIS",
        "EDIS Multispk",
        "Distribuidor",
        "Leitura de Retorno Baixo",
        "Roda Fonica",
        "420A/Neon",
        "36-2+2",
        "36-2-2-2",
        "Subaru 6/7",
        "Miata 99-00",
        "6g72",
        "IAW Weber",
        "CAS 4/1",
        "4G63 e Derivados",
        "Twin trigger",
        "Chrysler 2.2/2.5",
        "Renix 44-2-2",
        "Suzuki Swift",
        "Suzuki Vitara 2.0",
        "Daihatsu 3cyl",
        "Daihatsu 4cyl",
        "VTR1000",
        "Rover#1",
        "Rover#2",
        "Rover#3",
        "GM 7X",
        "Log crank",
        "Log crank&cam",
        "QR25DE",
        "Honda RC51",
        "INVALID",
        "Fuel Only",
        "INVALID",
        "INVALID",
        "INVALID",
        "INVALID",
        "INVALID",
        "INVALID",
        "INVALID",
        "INVALID",
        "LS1",
        "YZF1000",
        "INVALID",
        "INVALID",
        "INVALID",
        "INVALID",
        "INVALID",
        "INVALID",
        "INVALID",
        "INVALID",
        "INVALID",
        "INVALID",
        "INVALID",
        "INVALID",
        "HD 32-2",
        "Miata 36-2",
        "INVALID",
        "INVALID",
        "Ski doo PTEC",
        "INVALID",
        "INVALID",
        "INVALID",
        "INVALID",
        "INVALID",
    )

    val WHEEL_ARRANGEMENTS: List<String> = listOf(
        INVALID,
        "Uma Roda Fonica com Dente Faltante",
        "Duas Rodas de Leitura",
        "Duas Rodas com Dente Faltante",
    )

    fun sparkModeLabel(raw: Int): String =
        SPARK_MODES.getOrNull(raw)?.takeIf { it != INVALID } ?: "Modo $raw"

    /** Índices válidos (exclui INVALID) para popular dropdowns de edição. */
    fun selectableSparkModes(): List<Pair<Int, String>> =
        SPARK_MODES.withIndex().filter { it.value != INVALID }.map { it.index to it.value }
}
