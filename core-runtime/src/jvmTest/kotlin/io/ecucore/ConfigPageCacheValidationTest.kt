package io.ecucore

import io.ecucore.cache.EcuConfigPageCache
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Validação do cache persistente de páginas pelo CRC32 que a ECU informa ('d' Speeduino / 'k' MS2). */
class ConfigPageCacheValidationTest {

    /** Cache em memória com suporte a CRC por página. */
    private class MemCache : EcuConfigPageCache {
        val entries = HashMap<String, HashMap<Triple<Int, Int, Int>, Pair<ByteArray, Long>>>()
        val crcs = HashMap<Pair<String, Int>, Long>()
        override fun read(identity: String, pageNum: Int, offset: Int, length: Int, nowMs: Long, ttlMs: Long) =
            entries[identity]?.get(Triple(pageNum, offset, length))?.first
        override fun store(identity: String, pageNum: Int, offset: Int, length: Int, data: ByteArray, nowMs: Long) {
            entries.getOrPut(identity) { HashMap() }[Triple(pageNum, offset, length)] = data.copyOf() to nowMs
        }
        override fun invalidatePage(identity: String, pageNum: Int) {
            entries[identity]?.keys?.removeAll { it.first == pageNum }
            crcs.remove(identity to pageNum)
        }
        override fun invalidateIdentity(identity: String) {
            entries.remove(identity); crcs.keys.removeAll { it.first == identity }
        }
        override fun lastSyncMs(identity: String): Long? = entries[identity]?.values?.maxOfOrNull { it.second }
        override fun cachedPages(identity: String) = entries[identity]?.keys?.map { it.first }?.toSet().orEmpty()
        override fun pageCrc(identity: String, pageNum: Int) = crcs[identity to pageNum]
        override fun storePageCrc(identity: String, pageNum: Int, crc: Long) { crcs[identity to pageNum] = crc }
        override fun touchPage(identity: String, pageNum: Int, nowMs: Long) {
            entries[identity]?.entries?.filter { it.key.first == pageNum }?.forEach { it.setValue(it.value.first to nowMs) }
        }
        fun pageCached(page: Int) = entries.values.any { m -> m.keys.any { it.first == page } }
    }

    private suspend fun connect(kind: ClientFakeEcu.Kind, ecu: ClientFakeEcu, cache: MemCache): EcuClient =
        EcuClient(ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {}, pageCache = cache)
            .also { it.connect() }

    @Test
    fun `speeduino - page read this session adopts crc, then survives reconnect while ECU unchanged`() = runBlocking<Unit> {
        val kind = ClientFakeEcu.Kind.SPEEDUINO_2025
        val ecu = ClientFakeEcu(kind)
        ecu.mem(4)[0] = 7
        val cache = MemCache()
        val c1 = connect(kind, ecu, cache)
        c1.validateConfigPageCache()
        c1.readPage(4, 0, 16)
        val adopt = c1.validateConfigPageCache(adoptSessionReads = true)
        assertTrue(4 in adopt.adopted)
        c1.disconnect()

        val c2 = connect(kind, ecu, cache)
        val result = c2.validateConfigPageCache()
        assertTrue(4 in result.confirmed, "$result")
        val before = ecu.commands.count { it == 'p' }
        assertContentEquals(ecu.mem(4).copyOf(16), c2.readPage(4, 0, 16))
        assertEquals(before, ecu.commands.count { it == 'p' }, "página confirmada deve vir do cache")
    }

    @Test
    fun `speeduino - page changed outside the app is discarded and reread`() = runBlocking<Unit> {
        val kind = ClientFakeEcu.Kind.SPEEDUINO_2025
        val ecu = ClientFakeEcu(kind)
        val cache = MemCache()
        val c1 = connect(kind, ecu, cache)
        c1.validateConfigPageCache()
        c1.readPage(4, 0, 16)
        c1.validateConfigPageCache(adoptSessionReads = true)
        c1.disconnect()

        ecu.mem(4)[3] = 42 // TunerStudio mexeu na ECU
        val c2 = connect(kind, ecu, cache)
        val result = c2.validateConfigPageCache()
        assertTrue(4 in result.invalidated, "$result")
        assertEquals(42, c2.readPage(4, 0, 16)[3].toInt())
    }

    @Test
    fun `cache from a previous session without crc is not trusted`() = runBlocking<Unit> {
        val kind = ClientFakeEcu.Kind.SPEEDUINO_2025
        val ecu = ClientFakeEcu(kind)
        val cache = MemCache()
        val c1 = connect(kind, ecu, cache)
        c1.readPage(4, 0, 16) // cacheado sem nunca registrar CRC (versão antiga do app)
        c1.disconnect()

        val c2 = connect(kind, ecu, cache)
        val result = c2.validateConfigPageCache()
        assertTrue(4 in result.invalidated, "$result")
        assertTrue(!cache.pageCached(4))
    }

    @Test
    fun `ms2 - validates partial table reads with the k command`() = runBlocking<Unit> {
        val kind = ClientFakeEcu.Kind.MS2
        val ecu = ClientFakeEcu(kind)
        val cache = MemCache()
        val c1 = connect(kind, ecu, cache)
        c1.validateConfigPageCache()
        c1.readConfigChunk(4, 0, 64)
        val adopt = c1.validateConfigPageCache(adoptSessionReads = true)
        // a conexão do MS2 já lê a tabela 4: a 1ª validação registra o CRC, aqui ela já confere
        assertTrue(4 in adopt.adopted + adopt.confirmed, "$adopt")
        c1.disconnect()

        val c2 = connect(kind, ecu, cache)
        assertTrue(4 in c2.validateConfigPageCache().confirmed)
        assertTrue('k' in ecu.commands)
        c2.disconnect()

        ecu.mem(4)[10] = 1
        val c3 = connect(kind, ecu, cache)
        assertTrue(4 in c3.validateConfigPageCache().invalidated)
    }

    @Test
    fun `ecu without crc support keeps ttl behaviour`() = runBlocking<Unit> {
        val kind = ClientFakeEcu.Kind.SPEEDUINO_2025
        val ecu = ClientFakeEcu(kind)
        val cache = MemCache()
        val c1 = connect(kind, ecu, cache)
        c1.readPage(4, 0, 16)
        c1.disconnect()
        ecu.onFrame = { cmd, _ -> if (cmd == 'd') listOf(byteArrayOf(0x00, 0x01), byteArrayOf(0x83.toByte()), ByteArray(4)) else null }
        val c2 = connect(kind, ecu, cache)
        val result = c2.validateConfigPageCache()
        assertTrue(4 in result.unverified, "$result")
        assertTrue(cache.pageCached(4))
        assertNull(cache.pageCrc("x", 4))
    }
}
