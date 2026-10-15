package io.ecucore.model

import io.ecucore.definition.IniParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MegaSpeedTriggerLabelsTest {

    // Linha real de spk_mode0 do MegaSpeedMS2-X.ini da CostaMegaSpeed (trecho relevante).
    private val ini = """
        [MegaTune]
        signature = "MS2Extra MegaSpeed "

        [Constants]
        page = 1
        spk_mode0 = bits, U08, 989, [0:5], "EDIS", "EDIS Multispk", "Distribuidor", "Leitura de Retorno Baixo", "Roda Fonica", "420A/Neon", "36-2+2", "36-2-2-2", "Subaru 6/7", "Miata 99-00", "6g72", "IAW Weber", "CAS 4/1", "4G63 e Derivados", "Twin trigger", "Chrysler 2.2/2.5", "Renix 44-2-2", "Suzuki Swift", "Suzuki Vitara 2.0", "Daihatsu 3cyl", "Daihatsu 4cyl", "VTR1000", "Rover#1", "Rover#2", "Rover#3", "GM 7X", "Log crank", "Log crank&cam", "QR25DE", "Honda RC51", "INVALID", "Fuel Only", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID","LS1", "YZF1000", "INVALID", "INVALID","INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID","INVALID", "INVALID", "HD 32-2", "Miata 36-2","INVALID", "INVALID", "Ski doo PTEC", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID"
        spk_config_trig2 = bits, U08, 988, [2:3], "INVALID", "Uma Roda Fonica com Dente Faltante", "Duas Rodas de Leitura", "Duas Rodas com Dente Faltante"
    """.trimIndent()

    @Test
    fun `spark modes seguem exatamente o enum spk_mode0 do ini`() {
        val field = IniParser.parse("megaspeed.ini", ini).fields.firstOrNull { it.name == "spk_mode0" }
        assertNotNull(field)
        assertEquals(field.enumLabels, MegaSpeedTriggerLabels.SPARK_MODES)
    }

    @Test
    fun `arranjos de roda seguem o enum spk_config_trig2 do ini`() {
        val field = IniParser.parse("megaspeed.ini", ini).fields.first { it.name == "spk_config_trig2" }
        assertEquals(field.enumLabels, MegaSpeedTriggerLabels.WHEEL_ARRANGEMENTS)
    }

    @Test
    fun `indices que o MS2 generico desalinhava`() {
        assertEquals("Fuel Only", MegaSpeedTriggerLabels.sparkModeLabel(31))
        assertEquals("LS1", MegaSpeedTriggerLabels.sparkModeLabel(40))
        assertEquals("HD 32-2", MegaSpeedTriggerLabels.sparkModeLabel(54))
        assertEquals("Miata 36-2", MegaSpeedTriggerLabels.sparkModeLabel(55))
        assertEquals("Modo 30", MegaSpeedTriggerLabels.sparkModeLabel(30))
        assertEquals("Modo 99", MegaSpeedTriggerLabels.sparkModeLabel(99))
    }

    @Test
    fun `modos selecionaveis nao incluem INVALID`() {
        val selectable = MegaSpeedTriggerLabels.selectableSparkModes()
        assertEquals(false, selectable.any { it.second == MegaSpeedTriggerLabels.INVALID })
        assertEquals(31 to "Fuel Only", selectable.first { it.second == "Fuel Only" })
    }
}
