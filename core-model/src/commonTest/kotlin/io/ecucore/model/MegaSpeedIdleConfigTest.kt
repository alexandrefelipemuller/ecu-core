package io.ecucore.model

import io.ecucore.definition.IniParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MegaSpeedIdleConfigTest {

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
        IdleCtl_alg     = bits  ,  U08,    547,      [3:3], "Circuito Aberto", "Circuito Fechado"
      IdleCtl_home    = bits  ,  U08,    547,      [5:5], "Fechado", "Aberto"
      IACtstep        = scalar,  U08,    548,             "ms",       1,   0.00000,  0.00,   255,      0
      IAC_tinitial_step=scalar,  U08,    549,             "ms",       1,   0.00000,  0.00,   255,      0
      IACminstep      = scalar,  U08,    550,             "Passos",    1,   0.00000,  0.00,  255,      0
      IACStart        = scalar, S16,     552,             "",         1.00000, 0.00000, 0.00, 4000.00,    0
      IdleHyst        = scalar,  S16,    554,             "Celsius",       0.05555,   0.00000, 0,  20.0,       1
      IACcrankxt      = scalar,  S16,    558,             "s",      1.00000,   0.00000,  0.00,32767.00,      0
        IACcurlim     = bits,    U08,    560, [0:1], "Somente em Movimento", "Manter Corrente", "Sempre Ligado", "INVALID"
      fastIdleT       = scalar,  S16,    602,             "Celsius",       0.05555,  -320.000,{cltlowlim},   {clthighlim},      1
      iacfullopen     = scalar,  U16,    914,  "Passos",    1.00000,   0.00000,  0.0,   4000.0,      0
page = 2
      pwmidle_ms = scalar, U16, 32, "ms", 1, 0.00000, 50, 500, 0
      pwmidle_close_delay = scalar, U08, 34, "s", 1, 0.00000, 0, 10, 0
      pwmidle_open_duty = scalar, U08, 35, "%", 0.392, 0.00000, 0, 100, 1
      pwmidle_open_steps = scalar, U08, 35, "Passos", 1.00000, 0.00000, 0, 255, 0
      pwmidle_closed_duty = scalar, U08, 36, "%", 0.392, 0.00000, 0, 100, 1
      pwmidle_closed_steps = scalar, U08, 36, "Passos", 1.00000, 0.00000, 0, 255, 0
      pwmidle_pid_wait_timer = scalar, U08, 37, "s", 0.1, 0.00000, 2, 10, 1
      pwmidle_tps_threshold = scalar, U16, 41, "%", 0.1, 0.00000, 0, 1000, 1
      pwmidle_dp_adder = scalar, U08, 43, "%", 0.392, 0.00000, 0, 20, 1
      pwmidle_dp_adder_steps = scalar, U08, 43, "Passos", 1.00000, 0.00000, 0, 20, 0
      pwmidle_rpmdot_threshold = scalar, S16, 44, "rpm/sec", 10.0, 0.00000, 0, 1000, 0
      pwmidle_decelload_threshold = scalar, S16, 46, "%", 0.1, 0.00000, 0, 100, 1
      pwmidle_Kp_new = scalar, U16, 48, "%", 0.10000, 0.00000, 0, 200, 1
      pwmidle_Ki_new = scalar, U16, 50, "%", 0.10000, 0.00000, 0, 200, 1
      pwmidle_Kd_new = scalar, U16, 52, "%", 0.10000, 0.00000, 0, 200, 1
      pwmidle_freq = bits, U08, 54, [0:3], "31Hz", "62Hz", "92Hz", "124Hz", "153Hz", "186Hz", "217Hz", "252Hz", "279Hz", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID", "INVALID"
      pwmidle_max_rpm_new = scalar, U16, 57, "", 1, 0.00000, 0, 4000, 0
      pwmidle_targ_ramptime = scalar, U08, 59, "s", 0.1, 0.00000, 2, 10, 1
      pwmidle_port2 = bits, U08, 62, [0:1], "FIDLE", "INVALID", "INVALID", "INVALID"
      pwmidle_dp_decay_factor = scalar, U08, 63, "", 1, 0, 0, 100, 0
      pwmidlecranktaper= scalar,  U08,     831,     "s",        0.1,   0,  0,  10,      1
      pwmidleset_inv  = bits,     U08,     832, [1:1], "Normal, 0%=Desligado", "Invertido, 100%=Desligado"
      pwmidleset_koeo = bits,   U08, 832, [7:7], "Desligado", "Ligado"
page = 4
      idleadvance_on  = bits, U08, 387, [0:2], "Desligado", "Carregamento", "RPMs", "INVALID", "Adaptavel", "INVALID", "INVALID", "INVALID"
      idleadvance_adder  = bits, U08, 387, [3:3], "Definir Valor", "Adcionando"
      idleadvance_cl_cond = bits, U08, 387, [4:4], "Manual", "CL PID"
      idleadvance_tps = scalar, S16, 388, "%", 0.1, 0, 0, 100, 1
      idleadvance_rpm = scalar, U16, 390, "rpm", 1, 0, 0, 3000, 0
      idleadvance_load = scalar, S16, 392, "Kpa", 0.1, 0, 0, 100, 0
      idleadvance_clt = scalar, S16, 394, "Celsius", 0.05555, -320, {cltlowlim},   {clthighlim}, 1
      idleadvance_delay = scalar, U08, 396, "s", 1, 0, 0, 5, 0
page = 7
      pwmidle_cl_opts_initvaluetable = bits, U08, 9, [0:0], "Usar o Ultimo Valor", "Usar Valor Inicial da Tabela"
    """.trimIndent()

    private val definition = IniParser.parse("megaspeed.ini", ini)

    private fun pages(block: (Map<Int, ByteArray>) -> Unit): Map<Int, ByteArray> =
        MegaSpeedIdleConfig.REQUIRED_PAGES.associateWith { ByteArray(1024) }.also(block)

    private fun IniConfigView.value(label: String, section: String? = null): String? =
        sections.filter { section == null || it.title == section }
            .flatMap { it.entries }.firstOrNull { it.label == label }?.value

    private fun u16(d: ByteArray, off: Int, v: Int) { d[off] = (v shr 8).toByte(); d[off + 1] = v.toByte() }

    @Test
    fun `escalares aplicam escala unidade e digitos do ini`() {
        val p = pages {
            u16(it[0x05]!!, 48, 200)    // Kp: 200 * 0.1 = 20.0 %
            it[0x05]!![36] = 100        // closed_duty: 100 * 0.392 = 39.2 %
            u16(it[0x04]!!, 554, 180)   // IdleHyst: 180 * 0.05555 = 10.0 Celsius
            u16(it[0x04]!!, 602, 0xFF70) // fastIdleT S16 -144 -> (-144-320)*0.05555 = -25.8
        }
        val r = IniFieldReader(definition, p)
        assertEquals("20.0 %", r.display("pwmidle_Kp_new"))
        assertEquals("39.2 %", r.display("pwmidle_closed_duty"))
        assertEquals("10.0 Celsius", r.display("IdleHyst"))
        assertEquals("-25.8 Celsius", r.display("fastIdleT"))
        assertNull(r.display("campoInexistente"))
    }

    @Test
    fun `PWM em malha fechada mostra PID e esconde passo`() {
        val p = pages {
            it[0x04]!![547] = 0b1010.toByte() // IdleCtl=2 (PWM), IdleCtl_alg=1 (fechado)
            u16(it[0x05]!!, 48, 200)
            it[0x05]!![36] = 100
        }
        val v = MegaSpeedIdleConfig.parse(definition, p)
        assertEquals("Atuador PWM", v.value("Método de atuador de lenta"))
        assertEquals("Circuito Fechado", v.value("Método de malha"))
        assertEquals("20.0 %", v.value("Ganho proporcional", "PID"))
        assertEquals("39.2 %", v.value("Atuador de lenta fechado", "Malha fechada"))
        assertTrue(v.sections.any { it.title == "Atuador PWM" })
        assertFalse(v.sections.any { it.title == "Atuador motor de passo" })
        assertFalse(v.sections.any { it.title == "Atuador On/Off" })
    }

    @Test
    fun `motor de passo em malha aberta`() {
        val p = pages {
            it[0x04]!![547] = 3                // IdleCtl=3, alg aberto
            it[0x04]!![548] = 4                // IACtstep 4 ms
            it[0x04]!![550] = 3                // IACminstep
            u16(it[0x04]!!, 552, 100)          // IACStart
        }
        val v = MegaSpeedIdleConfig.parse(definition, p)
        assertEquals("4 ms", v.value("Tamanho do passo de tempo"))
        assertEquals("100", v.value("Passos de retorno"))
        assertFalse(v.sections.any { it.title == "Malha fechada" })
        assertFalse(v.sections.any { it.title == "PID" })
    }

    @Test
    fun `sem atuador mostra so o metodo`() {
        val v = MegaSpeedIdleConfig.parse(definition, pages { })
        assertEquals("Nenhuma", v.value("Método de atuador de lenta"))
        assertEquals(listOf("Método do atuador", "Ajuste de lenta por ponto"), v.sections.map { it.title })
    }

    @Test
    fun `pagina ausente nao quebra`() {
        val v = MegaSpeedIdleConfig.parse(definition, emptyMap())
        assertTrue(v.sections.isEmpty())
    }
}
