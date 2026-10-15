package io.ecucore.model

import io.ecucore.definition.IniDefinition

/**
 * Controle de marcha lenta da MegaSpeed MS2-X, equivalente aos diálogos `combinedidle`,
 * `combinedCLidle` e `idleAdvance` do .ini da CostaMegaSpeed. Somente leitura, com as mesmas
 * condições de visibilidade do .ini.
 */
object MegaSpeedIdleConfig {
    /** Páginas 1, 2, 4 e 7 do .ini = IDs 0x04, 0x05, 0x08 e 0x0C na ECU. */
    val REQUIRED_PAGES: List<Int> = listOf(0x04, 0x05, 0x08, 0x0C)

    fun parse(definition: IniDefinition, pages: Map<Int, ByteArray>): IniConfigView {
        val r = IniFieldReader(definition, pages)
        val ctl = r.raw("IdleCtl") ?: 0
        val closedLoop = ctl > 1 && r.raw("IdleCtl_alg") == 1
        val sections = mutableListOf<IniConfigSection>()

        fun section(title: String, build: MutableList<RusefiIoEntry>.() -> Unit) {
            val entries = mutableListOf<RusefiIoEntry>().apply(build)
            if (entries.isNotEmpty()) sections += IniConfigSection(title, entries)
        }
        fun MutableList<RusefiIoEntry>.add(title: String, field: String, visible: Boolean = true) {
            if (!visible) return
            val value = r.display(field) ?: return
            add(RusefiIoEntry(title, value))
        }

        section("Método do atuador") {
            add("Método de atuador de lenta", "IdleCtl")
            add("Método de malha", "IdleCtl_alg", visible = ctl > 1)
        }
        section("Atuador On/Off") {
            add("Temperatura limite com atuador aberto", "fastIdleT", visible = ctl == 1)
            add("Histerese", "IdleHyst", visible = ctl == 1)
        }
        section("Atuador motor de passo") {
            val on = ctl == 3
            add("Tamanho do passo de tempo", "IACtstep", on)
            add("Tamanho do passo de tempo inicial", "IAC_tinitial_step", on)
            add("Mínimo de passos para mover", "IACminstep", on)
            add("Passos de retorno", "IACStart", on)
            add("Direção de retorno", "IdleCtl_home", on)
            add("Passos bem abertos", "iacfullopen", on && r.flag("IdleCtl_home"))
            add("Tempo de transição de partida para funcionamento", "IACcrankxt", on && r.raw("IdleCtl_alg") == 0)
            add("Histerese", "IdleHyst", on && r.raw("IdleCtl_alg") == 0)
            add("Controle de corrente", "IACcurlim", on)
        }
        section("Atuador PWM") {
            val on = ctl == 2
            add("Tempo de transição de partida para funcionamento", "pwmidlecranktaper", on)
            add("Modo do atuador", "pwmidleset_inv", on)
            add("Executar o atuador na partida", "pwmidleset_koeo", on)
            add("Porta do atuador", "pwmidle_port2", on)
            add("Frequência do atuador PWM", "pwmidle_freq", on && (r.raw("pwmidle_port2") ?: 0) == 0)
        }
        section("Malha fechada") {
            val pwm = ctl == 2
            val stepper = ctl == 3
            add("Atuador de lenta fechado", "pwmidle_closed_duty", closedLoop && pwm)
            add("Atuador de lenta aberto", "pwmidle_open_duty", closedLoop && pwm)
            add("Adicionando abertura", "pwmidle_dp_adder", closedLoop && pwm)
            add("Atuador de lenta fechado", "pwmidle_closed_steps", closedLoop && stepper)
            add("Atuador de lenta aberto", "pwmidle_open_steps", closedLoop && stepper)
            add("Abertura adicional temporária", "pwmidle_dp_adder_steps", closedLoop && stepper)
            add("Velocidade de decaimento do atuador", "pwmidle_dp_decay_factor", closedLoop)
            add("Usar a tabela ou último valor", "pwmidle_cl_opts_initvaluetable", closedLoop)
            add("Atraso de fechamento (0 = não fechar)", "pwmidle_close_delay", closedLoop)
        }
        section("PID") {
            add("Atraso PID", "pwmidle_pid_wait_timer", closedLoop)
            add("Tempo de rampa até o alvo", "pwmidle_targ_ramptime", closedLoop)
            add("Intervalo de controle PID", "pwmidle_ms", closedLoop)
            add("Ganho proporcional", "pwmidle_Kp_new", closedLoop)
            add("Ganho integral", "pwmidle_Ki_new", closedLoop)
            add("Ganho derivativo", "pwmidle_Kd_new", closedLoop)
            add("Limite de TPS de ativação (máx.)", "pwmidle_tps_threshold", closedLoop)
            add("Limite de RPM dot (máx.)", "pwmidle_rpmdot_threshold", closedLoop)
            add("Limite de carga (mín.)", "pwmidle_decelload_threshold", closedLoop)
        }
        section("Ajuste de lenta por ponto") {
            val on = (r.raw("idleadvance_on") ?: 0) != 0
            add("Método", "idleadvance_on")
            add("Aplicando como", "idleadvance_adder", on)
            add("Condição", "idleadvance_cl_cond", on)
            val manual = on && !r.flag("idleadvance_cl_cond")
            add("TPS abaixo de", "idleadvance_tps", manual)
            add("Rotação abaixo de", "idleadvance_rpm", manual)
            add("Carga acima de", "idleadvance_load", manual)
            add("CLT acima de", "idleadvance_clt", manual)
            add("Atraso de", "idleadvance_delay", manual)
        }
        return IniConfigView(sections)
    }
}
