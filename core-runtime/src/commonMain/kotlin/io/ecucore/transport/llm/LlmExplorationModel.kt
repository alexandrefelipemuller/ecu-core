package io.ecucore.transport.llm

/**
 * Exploração OBD2 assistida por LLM.
 *
 * Regra de segurança central: o LLM **nunca** produz bytes crus. Ele só escolhe itens de
 * um vocabulário fechado ([ReadService] + header + id), que o [ExplorationPlanValidator]
 * confere e o [LlmExplorationSession] traduz para comandos ELM327. Serviços de escrita,
 * reset, sessão, segurança, atuadores ou rotinas não existem neste vocabulário.
 *
 * Este pacote não depende de rede nem de JSON: a implementação do [LlmExplorationPlanner]
 * (Gemini via Firebase AI Logic, etc.) vive no app e converte a resposta do modelo para
 * estes tipos.
 */

/** Serviços de diagnóstico SOMENTE LEITURA que o LLM pode pedir. Lista fechada. */
enum class ReadService(
    /** Byte do serviço na requisição. */
    val serviceByte: Int,
    /** Tamanho, em dígitos hex, do identificador aceito (0 = requisição fixa). */
    val idHexLength: Int,
) {
    /** SAE J1979 modo 01 — dados correntes. */
    OBD_MODE_01(0x01, 2),
    /** SAE J1979 modo 09 — informação do veículo (VIN, CALID...). */
    OBD_MODE_09(0x09, 2),
    /** UDS ReadDataByIdentifier (DID de 2 bytes). */
    UDS_22(0x22, 4),
    /** KWP2000 ReadDataByLocalIdentifier (1 byte). */
    KWP_21(0x21, 2),
    /** KWP2000 ReadEcuIdentification (1 byte). */
    KWP_1A(0x1A, 2),
    /** UDS ReadDTCInformation — só reportDTCByStatusMask(0xFF), requisição fixa. */
    UDS_19_DTC_BY_STATUS(0x19, 0),
    ;

    /** Bytes hex da requisição (sem espaços) para um id já validado. */
    fun requestHex(id: String): String = when (this) {
        UDS_19_DTC_BY_STATUS -> "1902FF"
        else -> serviceByte.toString(16).uppercase().padStart(2, '0') + id.uppercase()
    }
}

/**
 * Um teste pedido pelo LLM.
 * @param header endereço de requisição: 11-bit CAN ("7E0"), 29-bit CAN ("18DA10F1") ou
 *   header KWP/J1850 de 3 bytes ("8110F1"). null = manter o header atual/padrão.
 * @param protocol protocolo ELM (ATSP): "0".."9", "A".."C". null = manter o atual.
 * @param hypothesis o que o modelo espera encontrar (só para registro/análise).
 */
data class ProbeRequest(
    val service: ReadService,
    val id: String = "",
    val header: String? = null,
    val protocol: String? = null,
    val hypothesis: String? = null,
)

data class ExplorationPlan(
    val probes: List<ProbeRequest>,
    /** Raciocínio resumido do modelo para esta rodada (registro). */
    val rationale: String? = null,
)

data class TokenUsage(val inputTokens: Int = 0, val outputTokens: Int = 0) {
    val total: Int get() = inputTokens + outputTokens
    operator fun plus(other: TokenUsage) =
        TokenUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens)
}

/** O que o planner devolve a cada passo. */
sealed class PlannerStep {
    abstract val usage: TokenUsage

    /** Quer rodar mais um lote de testes. */
    data class Probe(val plan: ExplorationPlan, override val usage: TokenUsage = TokenUsage()) : PlannerStep()

    /** Encerrou: entrega o perfil candidato (pode ser vazio). */
    data class Finish(val profile: CandidateProfile, override val usage: TokenUsage = TokenUsage()) : PlannerStep()
}

/**
 * Fórmula linear sobre bytes da resposta: value = raw * scale + offset, com raw sendo
 * os bytes [byteOffset, byteOffset + byteLength) do payload (após serviço+id), sem sinal,
 * big-endian (ou com sinal se [signed]). Só números — nunca expressão avaliada.
 */
data class CandidateSignal(
    val name: String,
    val service: ReadService,
    val id: String,
    val header: String? = null,
    val byteOffset: Int = 0,
    val byteLength: Int = 1,
    val signed: Boolean = false,
    val scale: Double = 1.0,
    val offset: Double = 0.0,
    val unit: String? = null,
    /** Grandeza de referência para validação cruzada com o modo 01, se houver. */
    val reference: ReferenceQuantity? = null,
)

data class CandidateProfile(
    val signals: List<CandidateSignal> = emptyList(),
    val notes: String? = null,
)

/** Grandezas que existem no modo 01 padrão e servem para validar um sinal OEM. */
enum class ReferenceQuantity(val mode01Pid: String, val tolerance: Double, val absoluteTolerance: Double) {
    RPM("0C", 0.10, 50.0),
    COOLANT_TEMP("05", 0.0, 4.0),
    INTAKE_TEMP("0F", 0.0, 4.0),
    VEHICLE_SPEED("0D", 0.0, 3.0),
    MODULE_VOLTAGE("42", 0.05, 0.3),
    ;

    /** Decodifica a resposta padrão do modo 01 (payload após 41 PID). */
    fun decodeMode01(data: List<Int>): Double? = when (this) {
        RPM -> if (data.size >= 2) ((data[0] * 256) + data[1]) / 4.0 else null
        COOLANT_TEMP, INTAKE_TEMP -> data.firstOrNull()?.let { it - 40.0 }
        VEHICLE_SPEED -> data.firstOrNull()?.toDouble()
        MODULE_VOLTAGE -> if (data.size >= 2) ((data[0] * 256) + data[1]) / 1000.0 else null
    }
}

/** Contexto enviado ao planner. Sem VIN completo: só WMI + ano. */
data class ExplorationContext(
    val wmi: String? = null,
    val modelYear: Int? = null,
    val brandHint: String? = null,
    val adapter: String? = null,
    val respondingProtocols: List<String> = emptyList(),
    /** Resultado compacto da varredura determinística anterior (chave → resposta). */
    val baselineEntries: Map<String, String> = emptyMap(),
    /** Budget restante: o planner deve devolver [PlannerStep.Finish] quando true. */
    val mustFinish: Boolean = false,
    val roundsLeft: Int = 0,
    val probesLeft: Int = 0,
)

interface LlmExplorationPlanner {
    suspend fun nextStep(context: ExplorationContext, history: List<ExplorationRound>): PlannerStep
}

/** Classificação da resposta da ECU a um teste. */
enum class ProbeStatus { POSITIVE, NEGATIVE, NO_RESPONSE, ERROR }

data class ProbeOutcome(
    val request: ProbeRequest,
    /** Comandos ELM efetivamente enviados (ATSP/ATSH + requisição). */
    val commands: List<String>,
    val status: ProbeStatus,
    /** Bytes de dados após o eco de serviço+id (só POSITIVE). */
    val data: List<Int> = emptyList(),
    /** NRC (byte após 7F xx), só NEGATIVE. */
    val nrc: Int? = null,
    val rawResponse: String,
    val elapsedMs: Long,
)

data class RejectedProbe(val request: ProbeRequest, val reason: String)

/** De onde veio a rodada: do LLM ou de uma etapa determinística (sem custo de tokens). */
enum class RoundSource {
    /** Plano proposto pelo LLM. */
    LLM,
    /** Mapa de ECUs: leitura inofensiva (22 F190) em cada endereço candidato. */
    SURVEY_ECU_MAP,
    /** Amostragem de faixas de DID (XX00) nas ECUs encontradas. */
    SURVEY_DID_RANGES,
    /** Vizinhos (±N) de identificadores que responderam. */
    NEIGHBORS,
}

data class ExplorationRound(
    val index: Int,
    val plan: ExplorationPlan,
    val rejected: List<RejectedProbe>,
    val outcomes: List<ProbeOutcome>,
    val usage: TokenUsage,
    val source: RoundSource = RoundSource.LLM,
) {
    val positives: Int get() = outcomes.count { it.status == ProbeStatus.POSITIVE }
}

/** Limites da sessão — todos devem vir do Remote Config no app. */
data class ExplorationBudget(
    val maxRounds: Int = 10,
    val maxProbesPerRound: Int = 40,
    val maxTotalProbes: Int = 150,
    val maxTotalTokens: Int = 100_000,
    val maxDurationMs: Long = 5 * 60_000L,
    /** Intervalo mínimo entre requisições ao barramento. */
    val minProbeIntervalMs: Long = 100L,
    val probeTimeoutMs: Long = 2_000L,
    /** Rodadas seguidas sem nenhuma resposta positiva nova antes de parar. */
    val maxRoundsWithoutProgress: Int = 2,
    /** Sem resposta seguidas numa rodada antes de abortar a rodada (ECU ausente). */
    val maxConsecutiveNoResponse: Int = 8,
    /** Etapas determinísticas (mapa de ECUs, faixas de DID, vizinhos) antes/entre as rodadas do LLM. */
    val surveyEnabled: Boolean = true,
    /** Orçamento próprio das etapas determinísticas (não consome [maxTotalProbes] do LLM). */
    val maxDeterministicProbes: Int = 700,
    /** Quantas ECUs recebem a amostragem de faixas de DID (256 leituras cada). */
    val surveyMaxEcus: Int = 2,
    /** Raio da expansão de vizinhos em torno de cada identificador que respondeu. */
    val neighborRadius: Int = 16,
)

enum class StopReason {
    PLANNER_FINISHED, MAX_ROUNDS, MAX_PROBES, MAX_TOKENS, TIMEOUT,
    NO_PROGRESS, ENGINE_RUNNING, DISCONNECTED, PLANNER_ERROR, CANCELLED,
}

data class SignalVerification(
    val signal: CandidateSignal,
    /** Valor do sinal OEM decodificado com a fórmula do LLM. */
    val oemValue: Double?,
    /** Valor de referência do modo 01 (null se não houver/sem resposta). */
    val referenceValue: Double?,
    val verdict: Verdict,
) {
    enum class Verdict { VERIFIED, MISMATCH, UNVERIFIABLE, DECODE_FAILED }
}

/** Registro completo da sessão — é isto que o app envia ao Firestore para análise. */
data class LlmExplorationRecord(
    val context: ExplorationContext,
    val rounds: List<ExplorationRound>,
    val profile: CandidateProfile?,
    val verifications: List<SignalVerification>,
    val stopReason: StopReason,
    val totalUsage: TokenUsage,
    val elapsedMs: Long,
    val stopDetail: String? = null,
)
