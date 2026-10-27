package io.ecucore.sync

import java.io.File
import java.util.zip.CRC32

/**
 * Verificação de integridade de um backup antes de restaurar. Os CRC32 por página são gravados
 * em [FILE_NAME] no download; sem verificá-los, uma página truncada ou editada seria gravada na
 * ECU como se fosse válida.
 */
object BackupIntegrity {
    const val FILE_NAME = "page_crc32.txt"

    fun crc32(data: ByteArray): Long = CRC32().also { it.update(data) }.value

    fun writeChecksums(sessionDir: File, pages: Map<Byte, ByteArray>) {
        File(sessionDir, FILE_NAME).writeText(
            pages.entries.sortedBy { it.key }.joinToString("\n") { (page, data) -> "$page=${crc32(data)}" }
        )
    }

    /** CRC32 registrados no backup, ou `null` em backups antigos, que não são verificáveis. */
    fun readChecksums(sessionDir: File): Map<Byte, Long>? {
        val file = File(sessionDir, FILE_NAME).takeIf { it.isFile } ?: return null
        return file.readLines().mapNotNull { line ->
            val (page, crc) = line.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            (page.trim().toByteOrNull() ?: return@mapNotNull null) to (crc.trim().toLongOrNull() ?: return@mapNotNull null)
        }.toMap()
    }

    /** Páginas cujo conteúdo não bate com o CRC32 registrado. Sem registro, nada é reprovado. */
    fun corruptPages(pages: Map<Byte, ByteArray>, expected: Map<Byte, Long>?): List<Byte> {
        if (expected.isNullOrEmpty()) return emptyList()
        return pages.entries
            .filter { (page, data) -> expected[page]?.let { it != crc32(data) } == true }
            .map { it.key }
            .sorted()
    }
}

/** Lançada antes de qualquer escrita: a ECU permanece intacta. */
class BackupCorruptedException(val pages: List<Byte>) :
    Exception("Backup corrompido (CRC32 diferente nas páginas ${pages.joinToString(", ")}); restauração cancelada")
