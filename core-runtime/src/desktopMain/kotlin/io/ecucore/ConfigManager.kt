package io.ecucore

import io.ecucore.definition.IniDefinition
import io.ecucore.definition.MsqCodec
import io.ecucore.definition.PageFieldCodec
import io.ecucore.model.AfrTable
import io.ecucore.model.EcuFamily
import io.ecucore.model.EngineConstants
import io.ecucore.model.IgnitionTable
import io.ecucore.model.TriggerSettings
import io.ecucore.model.VeTable
import io.ecucore.protocol.SerialCapability
import io.ecucore.transport.EcuTransport
import io.ecucore.sync.BackupIntegrity
import io.ecucore.sync.SessionTableParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Gerenciador de configurações do Speeduino.
 *
 * Responsável por baixar, salvar e carregar configurações e mapas da ECU.
 */
class ConfigManager(baseDir: File = defaultBaseDir()) {

    companion object {
        // Fallback quando o transporte não expõe o catálogo de páginas do .ini (ver [pageSizesFor]).
        // Valores do pageSize do speeduino_202501.ini. ⚠️ A página 6 tem 128 bytes (240 é a página 7).
        val PAGE_SIZES = linkedMapOf(
            0.toByte() to 128,   // Page 0 - Configurações gerais / status
            1.toByte() to 128,   // Page 1 - Engine constants (algoritmo, req fuel, etc)
            2.toByte() to 288,   // Page 2 - VE Table (16x16 + eixos compactados)
            3.toByte() to 288,   // Page 3 - Ignition Table (16x16 + eixos)
            4.toByte() to 128,   // Page 4 - trigger / idle / rev limiter
            5.toByte() to 288,   // Page 5 - AFR Target Table
            6.toByte() to 128,   // Page 6 - EGO / IAC / engine protect type
            7.toByte() to 240,   // Page 7 - Boost / VVT / staging
            8.toByte() to 384,   // Page 8
            9.toByte() to 192,   // Page 9 - hard rev mode / AFR protect / secondary serial
            10.toByte() to 192   // Page 10 - oil pressure flags
        )

        /**
         * Tamanho das páginas conforme o .ini carregado no cliente (varia por firmware: o 201609 tem
         * páginas de 64 bytes, o 202501 tem 15 páginas). Cai para [PAGE_SIZES] só sem catálogo.
         */
        fun pageSizesFor(client: EcuTransport): Map<Byte, Int> {
            val fromIni = client.getEcuPageCatalog()
                .filter { it.size > 0 && it.id in 0..255 }
                .associate { it.id.toByte() to it.size }
            return if (fromIni.isEmpty()) PAGE_SIZES else fromIni
        }

        private const val MAX_SPEEDUINO_PAGE = 15
        private val PAGE_FILE_REGEX = Regex("""page_(\d+)\.bin""")

        const val CONFIG_DIR = "speeduino_configs"

        fun defaultBaseDir(): File {
            return File(System.getProperty("user.home"), "SpeeduinoManagerDesktop")
        }
    }

    private val configDir: File = File(baseDir, CONFIG_DIR).apply {
        if (!exists()) mkdirs()
    }

    /**
     * Baixa todas as configurações do Speeduino.
     */
    suspend fun downloadAllConfigs(
        client: EcuTransport,
        onProgress: (Int, Int, String) -> Unit
    ): ConfigDownloadResult = withContext(Dispatchers.IO) {
        val pageSizes = pageSizesFor(client)
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val sessionDir = File(configDir, timestamp).apply { mkdirs() }

            // 1. Obter capacidades
            onProgress(0, pageSizes.size + 1, "Obtendo capacidades...")
            val capability = client.getSerialCapability()

            // Salvar informações gerais
            val infoFile = File(sessionDir, "info.txt")
            infoFile.writeText(
                """
                Speeduino Configuration Download
                Timestamp: $timestamp
                Protocol Version: ${capability.protocolVersion}
                Blocking Factor: ${capability.blockingFactor}
                Table Blocking Factor: ${capability.tableBlockingFactor}
                """.trimIndent()
            )

            val downloadedPages = mutableMapOf<Byte, PageData>()
            val failedPages = mutableListOf<Byte>()
            var currentPage = 0

            // 2. Baixar cada página
            for ((pageNum, pageSize) in pageSizes) {
                currentPage++
                onProgress(currentPage, pageSizes.size + 1, "Baixando página $pageNum ($pageSize bytes)...")

                try {
                    // CRC desabilitado - comando 'd' pode causar timeout em alguns firmwares
                    val crc = 0L

                    val blockSize = if (capability.blockingFactor > 0) {
                        capability.blockingFactor
                    } else {
                        64
                    }

                    val pageData = client.readFullPage(pageNum.toInt() and 0xFF, pageSize, blockSize)

                    val pageFile = File(sessionDir, "page_$pageNum.bin")
                    pageFile.writeBytes(pageData)

                    downloadedPages[pageNum] = PageData(pageNum, pageSize, crc, pageData)

                    val crcInfo = if (crc != 0L) " CRC: 0x${crc.toString(16)}" else ""
                    onProgress(
                        currentPage,
                        pageSizes.size + 1,
                        "Página $pageNum baixada (${pageData.size} bytes$crcInfo)"
                    )
                } catch (e: Exception) {
                    failedPages += pageNum
                    onProgress(
                        currentPage,
                        pageSizes.size + 1,
                        "Erro na página $pageNum: ${e.message}"
                    )
                }
            }

            BackupIntegrity.writeChecksums(sessionDir, downloadedPages.mapValues { it.value.data })

            onProgress(
                pageSizes.size + 1,
                pageSizes.size + 1,
                if (failedPages.isEmpty()) {
                    "Download concluído! ${downloadedPages.size} páginas salvas"
                } else {
                    "Download concluído com falhas: ${failedPages.joinToString(", ")}"
                }
            )

            ConfigDownloadResult(
                success = failedPages.isEmpty(),
                timestamp = timestamp,
                pagesDownloaded = downloadedPages.size,
                totalPages = pageSizes.size,
                sessionDir = sessionDir,
                capability = capability,
                pages = downloadedPages,
                error = if (failedPages.isEmpty()) null else "Páginas com falha: ${failedPages.joinToString(", ")}"
            )
        } catch (e: Exception) {
            ConfigDownloadResult(
                success = false,
                timestamp = "",
                pagesDownloaded = 0,
                totalPages = pageSizes.size,
                sessionDir = null,
                capability = null,
                pages = emptyMap(),
                error = e.message
            )
        }
    }

    fun listSavedConfigs(): List<File> {
        return configDir.listFiles()?.toList()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun latestSavedConfig(): File? {
        return listSavedConfigs().firstOrNull()
    }

    suspend fun loadConfig(sessionDir: File): Map<Byte, ByteArray> = withContext(Dispatchers.IO) {
        val pages = mutableMapOf<Byte, ByteArray>()

        // Lê as page_N.bin (0..15) da sessão: o conjunto de páginas depende do firmware do backup.
        sessionDir.listFiles()
            ?.mapNotNull { file ->
                val number = PAGE_FILE_REGEX.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()
                number?.takeIf { it in 0..MAX_SPEEDUINO_PAGE }?.let { it.toByte() to file }
            }
            ?.sortedBy { (page, _) -> page.toInt() and 0xFF }
            ?.forEach { (page, file) -> pages[page] = file.readBytes() }

        pages
    }

    private fun findPageFile(sessionDir: File, candidates: List<Int>): File? {
        for (page in candidates) {
            val file = File(sessionDir, "page_${page}.bin")
            if (file.exists()) {
                return file
            }
        }
        return null
    }

    private fun resolveEngineConstantsModel(sessionDir: File): EngineConstants? {
        val pageFile = findPageFile(sessionDir, listOf(1)) ?: return null
        return try {
            EngineConstants.fromPage1(pageFile.readBytes())
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun readSessionPagesInt(sessionDir: File): Map<Int, ByteArray> {
        return loadConfig(sessionDir).mapKeys { (page, _) -> page.toInt() and 0xFF }
    }

    private fun resolveSessionEcuFamily(sessionDir: File): EcuFamily {
        val infoFile = File(sessionDir, "info.txt")
        if (!infoFile.exists()) return EcuFamily.SPEEDUINO

        val familyLine = infoFile.readLines().firstOrNull { it.startsWith("ECU Family:", ignoreCase = true) }
            ?: return EcuFamily.SPEEDUINO
        val familyValue = familyLine.substringAfter(':').trim()
        return runCatching { EcuFamily.valueOf(familyValue) }.getOrDefault(EcuFamily.SPEEDUINO)
    }

    fun deleteConfig(sessionDir: File): Boolean {
        return sessionDir.deleteRecursively()
    }

    fun exportSessionToZip(sessionDir: File, outputStream: OutputStream) {
        ZipOutputStream(outputStream).use { zip ->
            sessionDir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                val entry = ZipEntry(file.name)
                zip.putNextEntry(entry)
                file.inputStream().use { input ->
                    input.copyTo(zip)
                }
                zip.closeEntry()
            }
        }
    }

    fun importSessionFromZip(inputStream: InputStream): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val sessionDir = File(configDir, "${timestamp}_imported").apply { mkdirs() }
        val rootPath = sessionDir.canonicalPath

        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val outFile = File(sessionDir, entry.name)
                val outPath = outFile.canonicalPath
                if (!outPath.startsWith(rootPath)) {
                    throw IllegalArgumentException("Entrada ZIP inválida: ${entry.name}")
                }
                if (!entry.isDirectory) {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output ->
                        zip.copyTo(output)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        return sessionDir
    }

    /**
     * Gera o XML MSQ (formato de tune do TunerStudio) a partir dos bytes de página já
     * baixados, usando a definição .ini ativa da ECU conectada para nomear/escalar cada campo.
     */
    fun exportSessionToMsq(definition: IniDefinition, pageBytes: Map<Int, ByteArray>): String {
        val decoded = PageFieldCodec.decodeAllPages(definition, pageBytes)
        return MsqCodec.encode(
            signature = definition.signature,
            firmwareInfo = definition.sourceName,
            pages = decoded,
        )
    }

    /**
     * Importa um tune MSQ, aplicando os valores nomeados sobre uma cópia de [basePages] (bytes
     * de página já baixados da ECU, usados como base para campos que o MSQ não cobrir) e salva
     * o resultado como uma nova sessão, pronta para ser restaurada como um backup normal.
     */
    fun importSessionFromMsq(
        xmlText: String,
        definition: IniDefinition,
        basePages: Map<Int, ByteArray>,
    ): File {
        val doc = MsqCodec.decode(xmlText)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val sessionDir = File(configDir, "${timestamp}_imported_msq").apply { mkdirs() }

        val mutablePages = basePages.mapValues { (_, bytes) -> bytes.copyOf() }
        PageFieldCodec.applyMsqDocument(definition, doc, mutablePages)
        mutablePages.forEach { (page, bytes) -> File(sessionDir, "page_$page.bin").writeBytes(bytes) }

        return sessionDir
    }

    suspend fun loadVeTable(mapIndex: Int = 1): VeTable? = withContext(Dispatchers.IO) {
        val latestSession = listSavedConfigs().firstOrNull() ?: return@withContext null
        return@withContext loadVeTable(latestSession, mapIndex)
    }

    suspend fun loadVeTable(sessionDir: File, mapIndex: Int = 1): VeTable? = withContext(Dispatchers.IO) {
        val ecuFamily = resolveSessionEcuFamily(sessionDir)
        if (mapIndex == 1) {
            val pages = readSessionPagesInt(sessionDir)
            return@withContext SessionTableParser.parseVeTable(ecuFamily, pages)
        }

        val candidates = when (mapIndex) {
            2 -> listOf(7)
            3 -> listOf(8)
            4 -> listOf(9)
            else -> listOf(2, 1)
        }
        val pageFile = findPageFile(sessionDir, candidates) ?: return@withContext null
        val data = pageFile.readBytes()
        val constants = resolveEngineConstantsModel(sessionDir)
        return@withContext VeTable.fromPageData(
            data,
            loadType = io.ecucore.tables.TableDomainFacade.resolveVeLoadType(constants),
        )
    }

    suspend fun loadIgnitionTable(mapIndex: Int = 1): IgnitionTable? = withContext(Dispatchers.IO) {
        val latestSession = listSavedConfigs().firstOrNull() ?: return@withContext null
        return@withContext loadIgnitionTable(latestSession, mapIndex)
    }

    suspend fun loadIgnitionTable(sessionDir: File, mapIndex: Int = 1): IgnitionTable? = withContext(Dispatchers.IO) {
        val ecuFamily = resolveSessionEcuFamily(sessionDir)
        if (mapIndex == 1) {
            val pages = readSessionPagesInt(sessionDir)
            return@withContext SessionTableParser.parseIgnitionTable(ecuFamily, pages)
        }

        val candidates = when (mapIndex) {
            2 -> listOf(10)
            3 -> listOf(11)
            4 -> listOf(12)
            else -> listOf(3)
        }
        val pageFile = findPageFile(sessionDir, candidates) ?: return@withContext null
        val data = pageFile.readBytes()
        val constants = resolveEngineConstantsModel(sessionDir)
        return@withContext IgnitionTable.fromPageData(
            data,
            loadType = io.ecucore.tables.TableDomainFacade.resolveIgnitionLoadType(constants),
        )
    }

    suspend fun loadAfrTable(): AfrTable? = withContext(Dispatchers.IO) {
        val latestSession = listSavedConfigs().firstOrNull() ?: return@withContext null
        return@withContext loadAfrTable(latestSession)
    }

    suspend fun loadAfrTable(sessionDir: File): AfrTable? = withContext(Dispatchers.IO) {
        val ecuFamily = resolveSessionEcuFamily(sessionDir)
        val pages = readSessionPagesInt(sessionDir)
        SessionTableParser.parseAfrTable(ecuFamily, pages)
    }

    suspend fun loadEngineConstants(sessionDir: File): EngineConstants? = withContext(Dispatchers.IO) {
        val pageFile = findPageFile(sessionDir, listOf(1)) ?: return@withContext null
        return@withContext try {
            EngineConstants.fromPage1(pageFile.readBytes())
        } catch (_: Exception) {
            null
        }
    }

    suspend fun loadTriggerSettings(sessionDir: File): TriggerSettings? = withContext(Dispatchers.IO) {
        val pageFile = findPageFile(sessionDir, listOf(TriggerSettings.PAGE_NUMBER)) ?: return@withContext null
        return@withContext try {
            TriggerSettings.fromPageData(pageFile.readBytes())
        } catch (_: Exception) {
            null
        }
    }
}

data class PageData(
    val pageNum: Byte,
    val size: Int,
    val crc: Long,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PageData

        if (pageNum != other.pageNum) return false
        if (size != other.size) return false
        if (crc != other.crc) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = pageNum.toInt()
        result = 31 * result + size
        result = 31 * result + crc.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

data class ConfigDownloadResult(
    val success: Boolean,
    val timestamp: String,
    val pagesDownloaded: Int,
    val totalPages: Int,
    val sessionDir: File?,
    val capability: SerialCapability?,
    val pages: Map<Byte, PageData>,
    val error: String? = null
)
