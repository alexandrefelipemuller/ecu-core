package io.ecucore.cache

/**
 * Cache persistente de páginas de configuração da ECU, indexado por identidade da ECU
 * (assinatura de firmware + endereço de conexão) e por página.
 *
 * Objetivo: evitar rebaixar as mesmas páginas a cada conexão. Cada entrada guarda um timestamp
 * de sincronização e é considerada "fresca" enquanto `now - fetchedAt < ttl`. Escritas na ECU
 * invalidam a página correspondente.
 *
 * A interface é pura (sem dependências de Android) para poder viver em `commonMain` e ser testada
 * na JVM. A implementação em disco fica na camada `:app` (`FileEcuConfigPageCache`).
 */
interface EcuConfigPageCache {
    /**
     * Retorna os bytes em cache para a entrada `(pageNum, offset, length)` se existirem e estiverem
     * frescos (`0 <= nowMs - fetchedAt < ttlMs`); caso contrário `null`. `nowMs` é epoch ms
     * (relógio de parede): o timestamp é persistido e comparado entre execuções do app.
     */
    fun read(identity: String, pageNum: Int, offset: Int, length: Int, nowMs: Long, ttlMs: Long): ByteArray?

    /**
     * Grava (ou substitui) os bytes da entrada `(pageNum, offset, length)` com o timestamp informado.
     */
    fun store(identity: String, pageNum: Int, offset: Int, length: Int, data: ByteArray, nowMs: Long)

    /**
     * Invalida todas as entradas em cache de uma página (usado após gravar naquela página).
     */
    fun invalidatePage(identity: String, pageNum: Int)

    /**
     * Remove todo o cache de uma identidade de ECU.
     */
    fun invalidateIdentity(identity: String)

    /**
     * Timestamp (epoch ms) da entrada mais recente dessa identidade, ou `null` se não houver cache.
     */
    fun lastSyncMs(identity: String): Long?

    // --- Validação por CRC (ver EcuClient.validateConfigPageCache) ---
    // Implementações com default para não quebrar caches de outros apps; sem elas a validação
    // simplesmente não encontra páginas e nada é descartado nem renovado.

    /** Páginas que têm ao menos uma entrada em cache nessa identidade. */
    fun cachedPages(identity: String): Set<Int> = emptySet()

    /**
     * CRC32 que a ECU informou para a página quando as entradas atuais foram aceitas, ou `null`.
     * [invalidatePage]/[invalidateIdentity] devem apagá-lo junto com as entradas.
     */
    fun pageCrc(identity: String, pageNum: Int): Long? = null

    /** Registra o CRC32 informado pela ECU para a página. */
    fun storePageCrc(identity: String, pageNum: Int, crc: Long) {}

    /** Renova o timestamp de todas as entradas da página (página confirmada pela ECU via CRC). */
    fun touchPage(identity: String, pageNum: Int, nowMs: Long) {}
}

/**
 * Implementação no-op: usada em testes e em transportes sem persistência. Nunca retorna cache.
 */
object NoopEcuConfigPageCache : EcuConfigPageCache {
    override fun read(identity: String, pageNum: Int, offset: Int, length: Int, nowMs: Long, ttlMs: Long): ByteArray? = null
    override fun store(identity: String, pageNum: Int, offset: Int, length: Int, data: ByteArray, nowMs: Long) {}
    override fun invalidatePage(identity: String, pageNum: Int) {}
    override fun invalidateIdentity(identity: String) {}
    override fun lastSyncMs(identity: String): Long? = null
}
