package io.ecucore.model

import io.ecucore.definition.IniDefinition

/**
 * Pinagem em uso da MegaSpeed MS2-X, equivalente ao diálogo `iopinlist` do .ini da CostaMegaSpeed
 * ("Lista de Pinagem e Extras em Uso"). Somente leitura: mostra o que está atribuído, com as mesmas
 * condições de visibilidade do .ini, e reaproveita [RusefiInputOutputSnapshot] como contêiner.
 */
object MegaSpeedIoConfig {
    /** Páginas da ECU que contêm os campos de pinagem (IDs reais). */
    val REQUIRED_PAGES: List<Int> = listOf(0x04, 0x05, 0x0A, 0x0C)

    fun parse(definition: IniDefinition, pages: Map<Int, ByteArray>): RusefiInputOutputSnapshot {
        val r = IniFieldReader(definition, pages)
        val inputs = mutableListOf<RusefiIoEntry>()
        val fuel = mutableListOf<RusefiIoEntry>()
        val ignition = mutableListOf<RusefiIoEntry>()
        val aux = mutableListOf<RusefiIoEntry>()

        fun add(target: MutableList<RusefiIoEntry>, title: String, field: String, visible: Boolean = true) {
            if (!visible) return
            val value = r.label(field) ?: return
            target += RusefiIoEntry(title, value)
        }

        // Combustível
        fuel += RusefiIoEntry(
            "Injetores",
            if (r.flag("extrainj")) "Drivers adicionais (1 a 4)" else "Drivers padrão (1 ou 2)",
        )

        // Ignição / leitura de rotação
        val sparkMode = r.raw("spk_mode0")
        if (sparkMode != null) {
            ignition += RusefiIoEntry("Modo de ignição", MegaSpeedTriggerLabels.sparkModeLabel(sparkMode))
        }
        val fuelOnly = sparkMode == 31
        add(ignition, "Saída da centelha A", "spk_config_spka", visible = (sparkMode ?: 0) > 1 && !fuelOnly)
        val toothedSingle = r.raw("spk_config_trig2") == 1 && sparkMode == 4
        add(inputs, "Leitura de fase (JS10)", "spk_mode3a", visible = !toothedSingle)

        // Entradas
        val egoType = r.raw("egoType") ?: 0
        add(inputs, "Porta de sonda 1", "egoport", visible = egoType != 0)
        add(inputs, "Porta de sonda 2", "ego2port", visible = egoType == 2 || egoType == 4)
        add(inputs, "Porta do sensor barométrico", "rtbaroport", visible = r.raw("baroCorr") == 2)
        add(inputs, "Entrada do sensor MAF", "MAFOption", visible = (r.raw("MAFOption") ?: 0) != 0)
        add(inputs, "Porta do sensor flex", "flexport", visible = r.flag("flexFuel"))
        add(inputs, "Entrada de sinal do ar condicionado", "idle_up_options_input", visible = r.flag("ac_idleup_settings"))
        add(inputs, "Troca de mapa de injeção", "ts_port_f", visible = r.flag("f5_0_tsf") && r.raw("f5_0_tsf_opt") == 0)
        add(inputs, "Troca de mapa de ignição", "ts_port_s", visible = r.flag("f5_0_tss") && r.raw("f5_0_tss_opt") == 0)
        add(inputs, "Controle de tração", "knkport", visible = (r.raw("knk_option") ?: 0) != 0)
        add(inputs, "Acionamento do nitro", "N2Oopt_pins", visible = r.flag("N2Oopt_2"))
        add(inputs, "Acionamento de largada/flatshift", "launch_opt_pins", visible = (r.raw("launch_opt_on") ?: 0) > 0)
        add(inputs, "Acionamento do gear controller", "shift_cut_in", visible = r.flag("shift_cut_on"))

        // Saídas auxiliares
        val psLabels = listOf("FIDLE", null, null, "D15", "IAC1", "IAC2", "JS11")
        psLabels.forEachIndexed { i, pin ->
            if (pin != null && (r.element("psEnabled", i) ?: 0) != 0) {
                aux += RusefiIoEntry("Saída programável ($pin)", "Ativada")
            }
        }
        val idleCtl = r.raw("IdleCtl")
        add(aux, "Saída da marcha lenta PWM", "pwmidle_port2", visible = idleCtl == 1 || idleCtl == 2)
        val acOut = r.raw("ac_idleup_io_out")
        add(aux, "Saída do compressor do ar condicionado", "ac_idleup_io_out",
            visible = r.flag("ac_idleup_settings") && acOut != null && acOut != 0x0F)
        add(aux, "Saída do conta-giros", "tacho_opt3f", visible = r.flag("tacho_opt80"))
        add(aux, "Saída do controle de booster", "boost_ctl_pins",
            visible = r.flag("boost_ctl_settings_on") && r.raw("boost_ctl_settings_remote") == 0)
        add(aux, "Saída do nitro", "N2Oopt_pins2", visible = r.flag("N2Oopt_2"))
        add(aux, "Saída da embreagem do gear controller", "shift_cut_out", visible = r.flag("shift_cut_on"))

        return RusefiInputOutputSnapshot(
            inputs = inputs,
            fuelOutputs = fuel,
            ignitionOutputs = ignition,
            auxiliaryOutputs = aux,
        )
    }
}
