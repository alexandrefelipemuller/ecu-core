package io.ecucore.model

import io.ecucore.definition.IniParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MegaSpeedProtectionSensorsConfigTest {

    // Linhas reais de [Constants] do MegaSpeedMS2-X.ini (CostaMegaSpeed).
    private val ini = """
[MegaTune]
signature = "MS2Extra MegaSpeed "

[Constants]
endianness = big
nPages = 7
pageSize = 1024,1024,1024,1024,1024,1024,1024
pageIdentifier = "${'$'}tsCanId\x04","${'$'}tsCanId\x05","${'$'}tsCanId\x0a","${'$'}tsCanId\x08","${'$'}tsCanId\x09","${'$'}tsCanId\x0b","${'$'}tsCanId\x0c"
ochBlockSize = 219

page = 1
      TpsBypassCLTRevlim = scalar, S16,   44,             "TPS %",    0.10000,   0.00000, 0,      120,         1
      RevLimNormal2_hyst= scalar,U16,    46,             "RPM",      1.00000,   0.00000,  100,  1000,       0
      map0            = scalar,  S16,    506,             "Kpa",      0.10000,   0.00000, -100.0, 3276.7,      1
      mapmax          = scalar,  S16,    508,             "Kpa",      0.10000,   0.00000, -100.0, 3276.7,      1
      batt0           = scalar,  S16,    522,             "V",        0.10000,   0.00000,    0.0, 3276.7,      1
      battmax         = scalar,  S16,    524,             "V",        0.10000,   0.00000,-3276.8, 3276.7,      1
      baro0           = scalar,  S16,    530,             "kPa",      0.10000,   0.00000,-3276.8, 3276.7,      1
      baromax         = scalar,  S16,    532,             "kPa",      0.10000,   0.00000,-3276.8, 3276.7,      1
      RevLimOption_retard = bits,U08,    636,      [0:1], "Desligado", "Retardo progressivo", "Angulo fixo", "INVALID"
      RevLimOption_spkcut = bits,U08,    636,      [2:2], "Desligado", "Ligado"
      RevLimCLTbased  = bits,    U08,    636,      [3:3], "Desligado", "Ligado"
      RevLimOption_fuelprog=bits,U08,    636,      [4:4], "Desligado", "Ligado"
      RevLimOption_fuelcut =bits,U08,    636,      [5:5], "Desligado", "Ligado"
      RevLimMaxRtd    = scalar,  U08,    637,             "grau",      0.10000,   0.00000,  0,      25.50,      1
      RevLimRtdAng    = scalar,  S16,    654,             "grau",      0.10000,   0.00000,-10,   25.5,      1
      RevLimNormal2 =   scalar,  S16,    656,             "RPM",      1.00000,   0.00000,  0.00,  15000,       0
page = 7
    oil_in              = bits, U08, 628, [0:1], "JS5", "JS4", "Desligado", "INVALID"
    oil0            = scalar,  S16,    629,             "V",      0.10000,   0.00000, -300.0, 3276.7,      1
    oilmax          = scalar,  S16,    631,             "V",      0.10000,   0.00000, -100.0, 3276.7,      1
    fuel_in              = bits, U08, 633, [0:1], "JS5", "JS4", "Desligado", "INVALID"
    fuel0            = scalar,  S16,    634,             "V",      0.10000,   0.00000, -300.0, 3276.7,      1
    fuelmax          = scalar,  S16,    636,             "V",      0.10000,   0.00000, -100.0, 3276.7,      1
    """.trimIndent()

    private val definition = IniParser.parse("megaspeed.ini", ini)

    private fun u16(d: ByteArray, off: Int, v: Int) { d[off] = (v shr 8).toByte(); d[off + 1] = v.toByte() }

    private fun IniConfigView.value(label: String, section: String? = null): String? =
        sections.filter { section == null || it.title == section }
            .flatMap { it.entries }.firstOrNull { it.label == label }?.value

    @Test
    fun `protecao mostra limite de giro e retardo progressivo`() {
        val page = ByteArray(1024)
        u16(page, 656, 6800)          // RevLimNormal2
        u16(page, 46, 200)            // hysteresis
        page[636] = 0b100101          // retard=1 (progressivo), spkcut=1, fuelcut=1 (bit 5)
        page[637] = 35                // RevLimMaxRtd 3.5 grau
        val v = MegaSpeedProtectionConfig.parse(definition, mapOf(0x04 to page))
        assertEquals("6800 RPM", v.value("Limite de rotação"))
        assertEquals("200 RPM", v.value("Rotação de corte subtraindo"))
        assertEquals("Retardo progressivo", v.value("Método de retardo"))
        assertEquals("3.5 grau", v.value("Valor de retardo"))
        assertEquals("Ligado", v.value("Ativar corte de ignição"))
        assertEquals(null, v.value("Valor de retardo fixo"))
        assertEquals(null, v.value("Valor em TPS para ignorar a proteção"))
    }

    @Test
    fun `protecao por temperatura revela o TPS de bypass`() {
        val page = ByteArray(1024)
        page[636] = 0b001000          // RevLimCLTbased
        u16(page, 44, 500)            // 50.0 %
        val v = MegaSpeedProtectionConfig.parse(definition, mapOf(0x04 to page))
        assertEquals("50.0 TPS %", v.value("Valor em TPS para ignorar a proteção"))
    }

    @Test
    fun `sensores mostram MAP baro bateria e pressoes`() {
        val p1 = ByteArray(1024)
        val p7 = ByteArray(1024)
        u16(p1, 506, 35); u16(p1, 508, 4165)    // 3.5 / 416.5 kPa
        u16(p1, 530, 35); u16(p1, 532, 4165)
        u16(p1, 524, 297)                        // 29.7 V
        p7[628] = 0                              // oil_in = JS5
        u16(p7, 631, 50)                         // 5.0 V
        p7[633] = 2                              // fuel_in desligado
        val v = MegaSpeedSensorsConfig.parse(definition, mapOf(0x04 to p1, 0x0C to p7))
        assertEquals("3.5 Kpa", v.value("Valor em 0 V", "Sensor MAP"))
        assertEquals("416.5 Kpa", v.value("Valor em 5 V", "Sensor MAP"))
        assertEquals("29.7 V", v.value("Valor máximo", "Tensão da bateria"))
        assertEquals("JS5", v.value("Entrada", "Pressão de óleo"))
        assertEquals("5.0 V", v.value("Tensão em 10,0 bar", "Pressão de óleo"))
        assertEquals("Desligado", v.value("Entrada", "Pressão de combustível"))
        assertFalse(v.sections.first { it.title == "Pressão de combustível" }.entries.any { it.label.startsWith("Tensão") })
        assertTrue(v.sections.size == 5)
    }
}
