package com.speeduino.manager.connection

/**
 * Interface abstrata para comunicação com Speeduino
 *
 * Permite implementações diversas de transporte (TCP, Bluetooth, USB Serial, etc)
 * sem alterar a lógica de protocolo ou UI.
 *
 * Implementações devem garantir:
 * - Thread-safety para operações de I/O
 * - Timeout adequado para evitar bloqueios
 * - Limpeza de recursos em close()
 */
interface ISpeeduinoConnection {

    /**
     * Conecta ao dispositivo Speeduino
     * @throws Exception se falhar
     */
    suspend fun connect()

    /**
     * Desconecta do dispositivo
     */
    fun disconnect()

    /**
     * Envia dados brutos
     * @param data Bytes a enviar
     * @throws Exception se não conectado ou erro de I/O
     */
    fun send(data: ByteArray)

    /**
     * Recebe dados brutos
     * @param size Número de bytes a ler (0 = ler até timeout)
     * @return Bytes recebidos
     * @throws Exception se não conectado ou erro de I/O
     */
    fun receive(size: Int = 0): ByteArray

    /**
     * Lê até [maxBytes] do stream sem exigir tamanho exato.
     * Retorna vazio quando nenhum byte chegou dentro do timeout informado.
     *
     * A implementação padrão delega para [receive] por compatibilidade, mas
     * transportes reais devem sobrescrever este método para expor semântica de
     * stream parcial.
     */
    fun readAvailable(maxBytes: Int = 0, timeoutMs: Int? = null): ByteArray = try {
        receive(maxBytes)
    } catch (_: Exception) {
        ByteArray(0)
    }

    /**
     * Verifica se está conectado
     */
    fun isConnected(): Boolean

    /**
     * Obtém informações da conexão para debug
     */
    fun getConnectionInfo(): String

    /**
     * Identificador curto do perfil/runtime strategy ativo, quando aplicável.
     */
    fun getConnectionProfileTag(): String? = null

    /**
     * Indica se a conexão suporta o protocolo modern (CRC32-based)
     */
    fun supportsModernProtocol(): Boolean = true

    /**
     * Indica se, mesmo com caminho principal legacy, podemos tentar Modern como fallback.
     */
    fun supportsModernProtocolFallback(): Boolean = false

    /**
     * Indica se leituras de configuração podem usar framing modern, mesmo quando
     * o transporte continua legacy-first para handshake/live data.
     */
    fun supportsModernConfigReads(): Boolean = supportsModernProtocol() || supportsModernProtocolFallback()

    /**
     * Indica se deve priorizar Legacy e usar Modern apenas como fallback.
     */
    fun prefersLegacyProtocol(): Boolean = false

    /**
     * Número de tentativas legacy para o primeiro handshake de firmware.
     */
    fun legacyFirmwareHandshakeAttempts(): Int = 1

    /**
     * Intervalo entre tentativas legacy do primeiro handshake de firmware.
     */
    fun legacyFirmwareHandshakeRetryDelayMs(): Long = 0L

    /**
     * Permite que o transporte force leitura de página legacy em uma única requisição.
     */
    fun useWholePageLegacyConfigReads(): Boolean = false

    /**
     * Permite limpar o buffer antes de leituras legacy de configuração.
     */
    fun shouldDrainBeforeLegacyConfigRead(): Boolean = false

    /**
     * Delay adicional entre leituras/retries de configuração legacy.
     */
    fun legacyConfigReadDelayMs(): Long = 0L

    /**
     * Override opcional do tamanho de bloco para leitura de páginas legacy.
     */
    fun legacyConfigBlockSizeOverride(): Int? = null

    /**
     * Callback para mudança de estado de conexão
     */
    fun setOnConnectionStateChanged(callback: (Boolean) -> Unit)

    /**
     * Callback para erros de conexão
     */
    fun setOnError(callback: (String) -> Unit)

    /**
     * Limpa bytes pendentes no buffer de entrada, se suportado pelo transporte.
     */
    fun clearInputBuffer() {}

    /**
     * Solicita o cancelamento de uma leitura bloqueante em andamento, se suportado pelo transporte.
     */
    fun abortPendingRead() {}

    /**
     * Permite ao transporte ajustar o perfil físico antes de uma nova tentativa de handshake.
     * Retorna true quando houve mudança relevante (ex: reopen com outro perfil).
     */
    suspend fun prepareHandshakeRetry(attempt: Int): Boolean = false

    /**
     * Notifica o transporte de que o handshake inicial concluiu com sucesso.
     */
    fun markHandshakeSuccess() {}

    /**
     * Limpa recursos da conexao.
     */
    fun close() {
        disconnect()
    }
}
