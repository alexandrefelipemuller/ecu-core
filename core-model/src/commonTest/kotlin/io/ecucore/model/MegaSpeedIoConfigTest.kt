package io.ecucore.model

import io.ecucore.definition.IniParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MegaSpeedIoConfigTest {

    // Linhas reais de [Constants] do MegaSpeedMS2-X.ini (CostaMegaSpeed), extraídas por script.
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
        IdleCtl         = bits  ,  U08,    547,      [0:1], "Nenhuma", "Atuador On/Off", "Atuador PWM", "Motor de Passo"
      baroCorr        = bits  ,  U08,    593,      [0:1], "Desligado", "Por ciclo de pos chave", "Sensor Externo", "INVALID"
      egoType         = bits  ,  U08,    594,      [0:2], "Desativado", "Sonda Narrow Band", "Duas Sondas Narrow Band", "Sonda Wide Band", "Duas Sondas Wide Band", "INVALID", "INVALID", "INVALID"
      MAFOption       = bits  ,  U08,    600,      [4:5], "Desligado", "JS4", "JS5", "INVALID"
      psEnabled       = array ,  U08,    756,    [    7], "on/off",   1.00000,   0.00000,      0,       1,     0
      flexFuel        = bits  ,  U08,    908,      [0:0], "Desativado", "Ativado"
      flexport        = bits,    U08,    908,      [1:3], "PE0", "JS11", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID"
      knk_option      = bits  ,  U08,    927,      [0:1], "Desativado", "Modo Simples", "Modo Agressivo", "INVALID"
      knkport         = bits,    U08,    973,      [0:3], "JS4", "JS5", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "Remote Port3 Bit 0", "Remote Port3 Bit 1", "Remote Port3 Bit 2", "Remote Port3 Bit 3", "Remote Port3 Bit 4", "Remote Port3 Bit 5", "Remote Port3 Bit 6", "Remote Port3 Bit 7"
      spk_config_spka = bits  ,  U08,    986,      [0:0], "JS10", "D14"
      spk_config_trig2 =bits,    U08,    988,      [2:3], "INVALID", "Uma Roda Fonica com Dente Faltante", "Duas Rodas de Leitura", "Duas Rodas com Dente Faltante"
      spk_mode0       = bits  ,  U08,    989,      [0:5], "EDIS", "EDIS Multispk", "Distribuidor", "Leitura de Retorno Baixo",  "Roda Fonica", "420A/Neon", "36-2+2", "36-2-2-2", "Subaru 6/7", "Miata 99-00", "6g72", "IAW Weber", "CAS 4/1", "4G63 e Derivados", "Twin trigger", "Chrysler 2.2/2.5", "Renix 44-2-2", "Suzuki Swift", "Suzuki Vitara 2.0", "Daihatsu 3cyl", "Daihatsu 4cyl", "VTR1000", "Rover#1", "Rover#2", "Rover#3", "GM 7X", "Log crank", "Log crank&cam", "QR25DE", "Honda RC51", "INVALID", "Fuel Only", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID","LS1", "YZF1000", "INVALID", "INVALID","INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID","INVALID", "INVALID", "HD 32-2", "Miata 36-2","INVALID", "INVALID", "Ski doo PTEC", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID"
      spk_mode3a      = bits,    U08,    990,      [0:0], "Fase", "Sensor MAP"
      rtbaroport      = bits,    U08,    991,      [0:3], "MAP", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "JS5", "JS4", "Remote ADC0", "Remote ADC1", "Remote ADC2", "Remote ADC3", "Remote ADC4", "Remote ADC5", "Remote ADC6", "Remote ADC7"
      ego2port        = bits,    U08,    992,      [0:3], "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "JS5", "JS4", "Remote ADC0", "Remote ADC1", "Remote ADC2", "Remote ADC3", "Remote ADC4", "Remote ADC5", "Remote ADC6", "Remote ADC7"
      egoport         = bits,    U08,    993,      [0:3], "Local", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID"
page = 2
      pwmidle_port2 = bits, U08, 62, [0:1], "FIDLE", "INVALID", "INVALID", "INVALID"
      boost_ctl_settings_on = bits, U08, 67, [3:3], "Desligado","Ligado"
      boost_ctl_settings_remote = bits, U08, 67, [6:6], "Local", "Remoto"
      boost_ctl_pins = bits, U08, 68, [0:3], "Desligado", "IAC1", "IAC2", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "FIDLE", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID","INVALID"
      ts_port_f     = bits  ,  U08,      827,      [0:3], "PE1", "PE0", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID"
      ts_port_s     = bits  ,  U08,      827,      [4:7], "PE1", "PE0", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID"
      f5_0_tsf       = bits  ,  U08,      828,      [1:1], "Desligado", "Ligado"
      f5_0_tsf_opt   = bits  ,  U08,      828,      [2:3], "Botao", "Rotacao", "Pressao", "TPS"
      f5_0_tss_opt   = bits  ,  U08,      828,      [5:6], "Botao ", "Rotacao", "Pressao", "TPS"
      f5_0_tss       = bits  ,  U08,      828,      [4:4], "Desligado", "Ligado"
      tacho_opt80     = bits,     U08,     842, [7:7], "Desligado", "Ligado"
      tacho_opt3f     = bits,     U08,     842, [0:3], "JS10", "IAC1", "IAC2", "JS11", "INVALID", "INVALID", "INVALID", "INVALID", "JS5", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID"
page = 3
      launch_opt_on   = bits,    U08,    673,      [6:7], "Desligado", "Controle de Largada","INVALID","Largada/Flatshift"
      launch_opt_pins = bits,    U08,    673,      [0:3], "PE0", "PE1", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID" "INVALID"
      N2Oopt_2    = bits,   U08, 707,[2:2], "Desligado", "Ligado"
      N2Oopt_pins = bits,   U08, 803,[0:3], "PE0", "PE1", "INVALID", "JS11", "INVALID", "INVALID", "INVALID" "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID"
      N2Oopt_pins2 = bits,   U08, 803,[4:7], "IAC1+IAC2", "FIDLE+D15", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID"
page = 4
      extrainj        = bits, U08, 415, [2:2], "Drivers Padrao (1 ou 2)", "Drivers Adicionais (1 a 4)"
page = 7
      idle_up_options_input = bits,   U08, 0,[0:3], "PE0", "PE1", "INVALID", "JS11", "INVALID", "INVALID", "INVALID" "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID"
    ac_idleup_settings          =   bits,   U08, 0, [6:6], "Desligado","Ligado"
      ac_idleup_io_out     = bits,     U08,     8, [0:4], "INVALID", "IAC1", "IAC2", "JS11", "FIDLE", "INVALID", "INVALID", "D15", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "Desligado"
    shift_cut_on    = bits, U08, 502, [0:0], "Desligado", "Ligado"
      shift_cut_in = bits,   U08, 503,[0:3], "PE0", "PE1", "INVALID", "JS11", "INVALID", "INVALID", "INVALID" "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID"
      shift_cut_out     = bits,     U08,     504, [0:3], "INVALID", "IAC1", "IAC2", "JS11", "FIDLE", "INVALID", "INVALID", "D15", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "Desligado"
    """.trimIndent()

    private val definition = IniParser.parse("megaspeed.ini", ini)

    private fun pages(block: (Map<Int, ByteArray>) -> Unit): Map<Int, ByteArray> =
        MegaSpeedIoConfig.REQUIRED_PAGES.associateWith { ByteArray(1024) }.also(block)

    @Test
    fun `page ids resolvem como na ECU`() {
        assertEquals(listOf(0x04, 0x05, 0x0A, 0x08, 0x09, 0x0B, 0x0C), definition.pageDefinitions.map { it.resolvedId })
    }

    @Test
    fun `leitor de campos le bits e elementos de array`() {
        val p = pages {
            it[0x04]!![594] = 0b011          // egoType = 3 (wide band)
            it[0x04]!![993] = 0              // egoport = Local
            it[0x04]!![756 + 3] = 1          // psEnabled[3] (D15)
            it[0x05]!![62] = 0               // pwmidle_port2 = FIDLE
        }
        val r = IniFieldReader(definition, p)
        assertEquals(3, r.raw("egoType"))
        assertEquals("Sonda Wide Band", r.label("egoType"))
        assertEquals(1, r.element("psEnabled", 3))
        assertEquals(0, r.element("psEnabled", 2))
        assertNull(r.element("psEnabled", 7))
        assertNull(r.raw("campoInexistente"))
    }

    @Test
    fun `valor INVALID nao vira rotulo`() {
        val p = pages { it[0x04]!![993] = 5 } // egoport 5 = INVALID
        assertNull(IniFieldReader(definition, p).label("egoport"))
    }

    @Test
    fun `pagina ausente retorna null`() {
        val r = IniFieldReader(definition, emptyMap())
        assertNull(r.raw("egoType"))
        assertFalse(r.flag("egoType"))
    }

    @Test
    fun `pinagem respeita as condicoes de visibilidade do ini`() {
        val p = pages {
            it[0x04]!![989] = 4              // spk_mode0 = Roda Fonica
            it[0x04]!![594] = 1              // egoType = narrow band 1 sonda
            it[0x04]!![993] = 0              // egoport Local
            it[0x04]!![756 + 0] = 1          // FIDLE programável
            it[0x04]!![756 + 6] = 1          // JS11 programável
            it[0x05]!![842] = 0x80.toByte() // tacho on, pino JS10 (0)
        }
        val s = MegaSpeedIoConfig.parse(definition, p)

        assertTrue(s.inputs.any { it.label == "Porta de sonda 1" && it.value == "Local" })
        assertFalse(s.inputs.any { it.label == "Porta de sonda 2" })
        assertTrue(s.ignitionOutputs.any { it.label == "Modo de ignição" && it.value == "Roda Fonica" })
        assertTrue(s.auxiliaryOutputs.any { it.label == "Saída programável (FIDLE)" })
        assertTrue(s.auxiliaryOutputs.any { it.label == "Saída programável (JS11)" })
        assertFalse(s.auxiliaryOutputs.any { it.label == "Saída programável (D15)" })
        assertTrue(s.auxiliaryOutputs.any { it.label == "Saída do conta-giros" && it.value == "JS10" })
        assertFalse(s.auxiliaryOutputs.any { it.label == "Saída do nitro" })
        assertEquals("Drivers padrão (1 ou 2)", s.fuelOutputs.single().value)
    }

    @Test
    fun `modo fuel only nao mostra saida de centelha`() {
        val p = pages { it[0x04]!![989] = 31 }
        val s = MegaSpeedIoConfig.parse(definition, p)
        assertTrue(s.ignitionOutputs.any { it.value == "Fuel Only" })
        assertFalse(s.ignitionOutputs.any { it.label == "Saída da centelha A" })
    }
}
