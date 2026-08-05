package com.speeduino.manager.cache

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
     * frescos (`nowMs - fetchedAt < ttlMs`); caso contrário `null`.
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
