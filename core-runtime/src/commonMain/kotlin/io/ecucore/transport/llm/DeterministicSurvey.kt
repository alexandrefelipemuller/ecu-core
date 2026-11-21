package io.ecucore.transport.llm

/**
 * Geração determinística de testes (sem LLM, sem tokens). Tudo aqui produz [ProbeRequest]
 * comuns, que ainda passam pelo [ExplorationPlanValidator] antes de ir ao barramento.
 *
 * Ideia: o código faz a força bruta barata (onde há ECU, quais blocos de DID existem,
 * vizinhança de cada acerto) e o LLM fica com o que exige conhecimento — interpretar e
 * escolher onde aprofundar.
 */
object DeterministicSurvey {

    /** Endereços físicos CAN 11-bit comuns (OBD 7E0-7E7 + endereços OEM frequentes). */
    val CAN_11BIT_HEADERS = listOf(
        "7E0", "7E1", "7E2", "7E3", "7E4", "7E5", "7E6", "7E7",
        "710", "714", "720", "726", "740", "744", "745", "750", "752", "760", "765", "7A0", "7C0",
    )

    /** Endereços físicos CAN 29-bit comuns (ECU motor 10, transmissão 18...). */
    val CAN_29BIT_HEADERS = listOf("18DA10F1", "18DA11F1", "18DA17F1", "18DA18F1")

    private val CAN_11BIT_PROTOCOLS = listOf("6", "8")
    private val CAN_29BIT_PROTOCOLS = listOf("7", "9")

    /** DID de identificação (VIN) usado como "ping": qualquer resposta, até NRC, prova que há ECU. */
    const val PING_DID = "F190"

    /**
     * Pings para mapear ECUs. Se a varredura base achou protocolos CAN respondendo, usa só
     * eles; senão tenta 11-bit (6, 8) e 29-bit (7, 9).
     */
    fun ecuMapProbes(respondingProtocols: List<String>): List<ProbeRequest> {
        val can11 = respondingProtocols.filter { it in CAN_11BIT_PROTOCOLS }.ifEmpty { CAN_11BIT_PROTOCOLS }
        val can29 = respondingProtocols.filter { it in CAN_29BIT_PROTOCOLS }.ifEmpty { CAN_29BIT_PROTOCOLS }
        val onlyNonCan = respondingProtocols.isNotEmpty() &&
            respondingProtocols.none { it in CAN_11BIT_PROTOCOLS || it in CAN_29BIT_PROTOCOLS }
        if (onlyNonCan) return emptyList() // KWP/J1850 puros: fica com o LLM.
        return can11.flatMap { sp -> CAN_11BIT_HEADERS.map { ping(it, sp) } } +
            can29.flatMap { sp -> CAN_29BIT_HEADERS.map { ping(it, sp) } }
    }

    private fun ping(header: String, protocol: String) = ProbeRequest(
        service = ReadService.UDS_22,
        id = PING_DID,
        header = header,
        protocol = protocol,
        hypothesis = "survey:ecu_map",
    )

    /** Uma ECU "existe" se respondeu algo do protocolo (positivo ou NRC). */
    fun ecuFound(outcome: ProbeOutcome) =
        outcome.status == ProbeStatus.POSITIVE || outcome.status == ProbeStatus.NEGATIVE

    /** Amostra os 256 blocos de DID (XX00) de uma ECU — fabricantes agrupam DIDs por bloco. */
    fun didRangeProbes(header: String, protocol: String?): List<ProbeRequest> =
        (0x00..0xFF).map { hi ->
            ProbeRequest(
                service = ReadService.UDS_22,
                id = hex(hi, 2) + "00",
                header = header,
                protocol = protocol,
                hypothesis = "survey:did_block",
            )
        }

    /**
     * Resposta "interessante": positiva, ou NRC diferente de 0x31 (requestOutOfRange) —
     * 0x33 (security), 0x22 (conditions), 0x13 (length) indicam que o identificador existe.
     */
    fun isInteresting(outcome: ProbeOutcome) = outcome.status == ProbeStatus.POSITIVE ||
        (outcome.status == ProbeStatus.NEGATIVE && outcome.nrc != NRC_OUT_OF_RANGE && outcome.nrc !in PRUNE_NRCS)

    /** Vizinhos ±radius do identificador (mesmo serviço, header e protocolo). */
    fun neighborProbes(origin: ProbeRequest, radius: Int): List<ProbeRequest> {
        val width = origin.service.idHexLength
        if (width == 0 || radius <= 0) return emptyList()
        val center = origin.id.toIntOrNull(16) ?: return emptyList()
        val max = (1 shl (width * 4)) - 1
        return (1..radius).flatMap { d -> listOf(center - d, center + d) }
            .filter { it in 0..max }
            .map { origin.copy(id = hex(it, width), hypothesis = "survey:neighbor_of_${origin.id}") }
    }

    /**
     * NRCs que dizem "este serviço não existe nesta ECU (nesta sessão)": depois de um
     * deles, nenhum outro teste do mesmo serviço naquele header vale a pena.
     */
    val PRUNE_NRCS = setOf(0x11, 0x7F)
    const val NRC_OUT_OF_RANGE = 0x31

    fun pruneKey(probe: ProbeRequest) = "${probe.header.orEmpty()}|${probe.service.name}"

    private fun hex(value: Int, width: Int) = value.toString(16).uppercase().padStart(width, '0')
}
