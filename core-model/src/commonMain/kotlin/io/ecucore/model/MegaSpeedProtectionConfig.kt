package io.ecucore.model

import io.ecucore.definition.IniDefinition

/**
 * Corte de giro e proteção da MegaSpeed MS2-X, equivalente ao diálogo `revlimiter2` do .ini da
 * CostaMegaSpeed. Somente leitura, com as mesmas condições de visibilidade do .ini.
 */
object MegaSpeedProtectionConfig {
    /** Página 1 do .ini = ID 0x04 na ECU. */
    val REQUIRED_PAGES: List<Int> = listOf(0x04)

    fun parse(definition: IniDefinition, pages: Map<Int, ByteArray>): IniConfigView {
        val r = IniFieldReader(definition, pages)
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

        val retard = r.raw("RevLimOption_retard") ?: 0
        val cltBased = r.flag("RevLimCLTbased")

        section("Limite de rotação do motor") {
            add("Limite de rotação", "RevLimNormal2")
            add("Rotação de corte subtraindo", "RevLimNormal2_hyst")
        }
        section("Proteção por temperatura") {
            add("Limite de rotação por temperatura", "RevLimCLTbased")
            add("Valor em TPS para ignorar a proteção", "TpsBypassCLTRevlim", cltBased)
        }
        section("Retardo de ignição") {
            add("Método de retardo", "RevLimOption_retard")
            add("Valor de retardo", "RevLimMaxRtd", retard == 1)
            add("Valor de retardo fixo", "RevLimRtdAng", retard == 2)
        }
        section("Corte de ignição") {
            add("Ativar corte de ignição", "RevLimOption_spkcut")
        }
        section("Corte de combustível") {
            add("Ativar corte de combustível", "RevLimOption_fuelcut")
            add("Corte de combustível progressivo", "RevLimOption_fuelprog", r.flag("RevLimOption_fuelcut"))
        }
        return IniConfigView(sections)
    }
}
