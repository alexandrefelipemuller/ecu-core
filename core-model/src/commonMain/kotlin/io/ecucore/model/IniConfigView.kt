package io.ecucore.model

/** Seção somente leitura de uma tela de configuração derivada do .ini (título + pares rótulo/valor). */
data class IniConfigSection(
    val title: String,
    val entries: List<RusefiIoEntry>,
)

/** Visão somente leitura de uma configuração da ECU, montada a partir do .ini e das páginas lidas. */
data class IniConfigView(
    val sections: List<IniConfigSection>,
)

/** Visões de configuração suportadas para a MegaSpeed. */
enum class MegaSpeedConfigView(val requiredPages: List<Int>) {
    IDLE(MegaSpeedIdleConfig.REQUIRED_PAGES),
}
