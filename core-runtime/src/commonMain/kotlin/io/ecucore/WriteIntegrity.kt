package io.ecucore

import io.ecucore.shared.Crc32Table

/**
 * Tipos de falha de integridade de gravação que merecem telemetria própria. Valores estáveis:
 * viram parâmetro de Analytics/Crashlytics.
 */
enum class WriteIntegrityKind(val critical: Boolean) {
    /** Depois do burn, a RAM da ECU não bate mais com o que foi gravado e confirmado antes dele. */
    POST_BURN_MISMATCH(critical = true),

    /** Depois do burn, a página não pôde ser relida (ECU resetou/calou): burn sem confirmação. */
    POST_BURN_UNREADABLE(critical = false),

    /** Burn recusado porque havia gravação sem read-back confirmado (nunca deveria acontecer). */
    BURN_BLOCKED_UNVERIFIED(critical = true),

    /**
     * A região lida da ECU antes de uma nova gravação não bate com o que o último burn deixou
     * (CRC guardado em [BurnRecordStore]). Cobre "o burn não persistiu na EEPROM" (a ECU recarregou
     * outro valor num power cycle) e edição por outra ferramenta no meio. Aviso, não alarme: a causa
     * pode ser externa ao app.
     */
    PREVIOUS_BURN_DIVERGED(critical = false),
}

/**
 * Evento de integridade de gravação.
 *
 * ATENÇÃO: a releitura pós-burn lê a RAM da ECU, não a EEPROM (só um power cycle recarrega a
 * EEPROM). Ela pega reset/brownout durante o burn e corrupção no link, mas NÃO prova que a
 * EEPROM guardou o valor - para isso, ver a conferência persistida na próxima conexão.
 */
data class WriteIntegrityEvent(
    val kind: WriteIntegrityKind,
    val pageId: Int,
    val offset: Int,
    val length: Int,
    val label: String,
    val expectedCrc: Long,
    val actualCrc: Long?,
    val detail: String? = null,
)

/** Uma região gravada: [verified] = confirmada por read-back antes do burn. */
data class WriteLedgerEntry(
    val pageId: Int,
    val offset: Int,
    val length: Int,
    val crc: Long,
    val verified: Boolean,
    val label: String,
)

/**
 * Livro das gravações desde o último burn. Regras: o burn só acontece com tudo confirmado, e o que
 * foi queimado fica disponível ([lastBurned]) para a conferência pós-burn e a de próxima conexão.
 */
internal class WriteLedger {
    private val pending = LinkedHashMap<Triple<Int, Int, Int>, WriteLedgerEntry>()
    private var burned: List<WriteLedgerEntry> = emptyList()

    @Synchronized
    fun recordVerified(pageId: Int, offset: Int, data: ByteArray, label: String) = put(pageId, offset, data, label, true)

    @Synchronized
    fun recordUnverified(pageId: Int, offset: Int, data: ByteArray, label: String) = put(pageId, offset, data, label, false)

    private fun put(pageId: Int, offset: Int, data: ByteArray, label: String, verified: Boolean) {
        val key = Triple(pageId, offset, data.size)
        pending[key] = WriteLedgerEntry(pageId, offset, data.size, Crc32Table.compute(data), verified, label)
    }

    @Synchronized
    fun pending(): List<WriteLedgerEntry> = pending.values.toList()

    /** Move o pendente para "queimado" (chamado logo depois do burn). */
    @Synchronized
    fun markBurned(): List<WriteLedgerEntry> {
        val entries = pending.values.toList()
        pending.clear()
        burned = entries
        return entries
    }

    @Synchronized
    fun lastBurned(): List<WriteLedgerEntry> = burned

    @Synchronized
    fun clear() {
        pending.clear()
        burned = emptyList()
    }
}

/** O burn foi recusado: havia gravação em Speeduino sem confirmação por read-back. */
class UnverifiedBurnBlockedException(val unverified: List<WriteLedgerEntry>) : IllegalStateException(
    "Burn blocked: ${unverified.size} write(s) not confirmed by read-back (" +
        unverified.joinToString { "page ${it.pageId}@${it.offset}+${it.length}" } + ")",
)

/** O que o último burn deixou numa região de uma ECU, para conferir na próxima gravação/conexão. */
data class BurnRecord(
    val pageId: Int,
    val offset: Int,
    val length: Int,
    val crc: Long,
    val burnedAtEpochMs: Long,
    val label: String,
) {
    fun overlaps(pageId: Int, offset: Int, length: Int): Boolean =
        this.pageId == pageId && this.offset < offset + length && offset < this.offset + this.length

    fun sameRegion(pageId: Int, offset: Int, length: Int): Boolean =
        this.pageId == pageId && this.offset == offset && this.length == length
}

/** Persistência por identidade de ECU (`FAMÍLIA|assinatura|endereço`). Implementada pelo app. */
interface BurnRecordStore {
    fun load(identity: String): List<BurnRecord>
    fun save(identity: String, records: List<BurnRecord>)
}

internal object BurnRecords {
    /** Um burn novo substitui qualquer registro antigo que se sobreponha na mesma página. */
    fun merge(existing: List<BurnRecord>, burned: List<WriteLedgerEntry>, nowMs: Long): List<BurnRecord> {
        val fresh = burned.filter { it.verified }.map { BurnRecord(it.pageId, it.offset, it.length, it.crc, nowMs, it.label) }
        val kept = existing.filterNot { old -> fresh.any { old.overlaps(it.pageId, it.offset, it.length) } }
        return kept + fresh
    }
}
