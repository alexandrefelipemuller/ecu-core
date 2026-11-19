package io.ecucore.transport

/**
 * Tabela de DTC da Siemens Sirius 32 compartilhada por [Sirius32Transport] e
 * [RenaultTransport] (perfil OEM `sirius_32`).
 *
 * Fonte de verdade: `~/Projects/centrais_oem/Sirius32/core/dtc/dtc_sirius32.c`.
 * Só os índices 0-9 têm máscara conhecida (todos na palavra 0, `mask = 0x8000 >> índice`),
 * lidos via SID 0x2A local ID 0x0893. Apenas os índices 5 (DF003, IAT) e 9 (DF045, MAP)
 * têm identidade física confirmada; os demais usam rótulo genérico.
 *
 * O antigo "DF025 CKP" com máscara 0x0002 foi removido: era palpite (o DF025 real é o
 * índice 14, cuja máscara não é conhecida).
 */
internal object Sirius32Dtc {
    data class Bit(val mask: Int, val code: String, val description: String)

    val WORD0: List<Bit> = listOf(
        Bit(0x8000, "DTC00", "Falha interna #0 (identificação física não confirmada - candidato TPS)"),
        Bit(0x4000, "DTC01", "Falha interna #1 (identificação física não confirmada)"),
        Bit(0x2000, "DTC02", "Falha interna #2 (identificação física não confirmada)"),
        Bit(0x1000, "DTC03", "Falha interna #3 (identificação física não confirmada - candidato detonação/knock)"),
        Bit(0x0800, "DTC04", "Falha interna #4 (identificação física não confirmada - cluster de temperatura)"),
        Bit(0x0400, "DF003", "Circuito do sensor de temperatura do ar (IAT)"),
        Bit(0x0200, "DTC06", "Falha interna #6 (identificação física não confirmada)"),
        Bit(0x0100, "DTC07", "Falha interna #7 (identificação física não confirmada - candidato bobina)"),
        Bit(0x0080, "DTC08", "Falha interna #8 (identificação física não confirmada - candidato injetor)"),
        Bit(0x0040, "DF045", "Circuito do sensor de pressão do coletor (MAP)"),
    )

    fun decodeWord0(word: Int, status: DtcStatus): List<DtcCode> =
        WORD0.filter { (word and it.mask) != 0 }
            .map { DtcCode(code = it.code, description = it.description, status = status) }
}
