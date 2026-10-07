package io.ecucore.transport

/**
 * Exploração rodada quando o preflight OBD2 falha de vez (o carro não conectou com
 * nenhum perfil). Não tenta conectar: só registra como o adaptador e o veículo reagem a
 * cada protocolo, pra virar insumo de suporte a novos veículos (telemetria).
 *
 * Fluxo: [Obd2Transport.connect] falha no preflight → chama [Obd2FailureExplorationHooks.askConsent]
 * com o socket ainda aberto → se o usuário aceitar, roda a varredura e entrega o
 * resultado em [Obd2FailureExplorationHooks.onResult] → desconecta e relança o erro original.
 */
interface Obd2FailureExplorationHooks {
    /** Suspende até o usuário decidir. false = pula a exploração. */
    suspend fun askConsent(failureSummary: String): Boolean

    /** Chamado ao fim da exploração (também quando interrompida pelo limite de tempo). */
    fun onResult(result: Obd2ExplorationResult)
}

data class Obd2ExplorationResult(
    /** Chave curta → resposta compacta (ex.: "sp6" → "41 00 BE 3E A8 11"). Ordem de execução. */
    val entries: Map<String, String>,
    /** Protocolos ELM (1..9, 0 = auto) em que o 0100 voltou positivo. */
    val respondingProtocols: List<String>,
    val elapsedMs: Long,
    val failureSummary: String,
)
