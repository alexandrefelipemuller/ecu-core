package io.ecucore.model

import io.ecucore.definition.IniDefinition

/**
 * Calibração de sensores da MegaSpeed MS2-X cujos valores são constantes simples no .ini
 * (`sensorCal`, `battcalib`, `oilCal`, `fuelCal`). Somente leitura. A calibração de temperatura e
 * da sonda grava em flash protegida por `flashlock` e fica fora desta visão.
 */
object MegaSpeedSensorsConfig {
    /** Páginas 1 e 7 do .ini = IDs 0x04 e 0x0C na ECU. */
    val REQUIRED_PAGES: List<Int> = listOf(0x04, 0x0C)

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

        section("Sensor MAP") {
            add("Valor em 0 V", "map0")
            add("Valor em 5 V", "mapmax")
        }
        section("Sensor barométrico") {
            add("Valor em 0 V", "baro0")
            add("Valor em 5 V", "baromax")
        }
        section("Tensão da bateria") {
            add("Valor mínimo", "batt0")
            add("Valor máximo", "battmax")
        }
        section("Pressão de óleo") {
            val on = (r.raw("oil_in") ?: 2) < 2
            add("Entrada", "oil_in")
            add("Tensão em 0,0 bar", "oil0", on)
            add("Tensão em 10,0 bar", "oilmax", on)
        }
        section("Pressão de combustível") {
            val on = (r.raw("fuel_in") ?: 2) < 2
            add("Entrada", "fuel_in")
            add("Tensão em 0,0 bar", "fuel0", on)
            add("Tensão em 10,0 bar", "fuelmax", on)
        }
        return IniConfigView(sections)
    }
}
