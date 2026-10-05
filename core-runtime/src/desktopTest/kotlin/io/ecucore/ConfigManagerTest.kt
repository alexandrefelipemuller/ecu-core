package io.ecucore

import io.ecucore.definition.IniParser
import io.ecucore.model.EcuFamily
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigManagerTest {

    private val root: File = Files.createTempDirectory("configmanager").toFile()
    private val manager = ConfigManager(root)
    private val sessionsDir = File(root, ConfigManager.CONFIG_DIR)

    @AfterTest
    fun cleanup() { root.deleteRecursively() }

    private fun session(name: String, lastModified: Long? = null, vararg pages: Pair<Int, ByteArray>, info: String? = null): File {
        val dir = File(sessionsDir, name).apply { mkdirs() }
        pages.forEach { (n, bytes) -> File(dir, "page_$n.bin").writeBytes(bytes) }
        info?.let { File(dir, "info.txt").writeText(it) }
        lastModified?.let { dir.setLastModified(it) }
        return dir
    }

    // ---- constantes e listagem ---------------------------------------------------------------------------

    @Test
    fun `constants and default directory`() {
        assertEquals(11, ConfigManager.PAGE_SIZES.size)
        assertEquals(288, ConfigManager.PAGE_SIZES[2.toByte()])
        assertEquals("speeduino_configs", ConfigManager.CONFIG_DIR)
        assertTrue(ConfigManager.defaultBaseDir().path.endsWith("SpeeduinoManagerDesktop"))
        assertTrue(sessionsDir.isDirectory) // criado pelo construtor
    }

    @Test
    fun `saved configs are listed newest first`() {
        assertTrue(manager.listSavedConfigs().isEmpty())
        assertNull(manager.latestSavedConfig())
        val old = session("old", 1_000_000L)
        val new = session("new", 9_000_000L)
        val mid = session("mid", 5_000_000L)
        assertEquals(listOf(new, mid, old).map { it.name }, manager.listSavedConfigs().map { it.name })
        assertEquals("new", manager.latestSavedConfig()!!.name)
    }

    @Test
    fun `delete removes a session and its files`() {
        val dir = session("a", null, 1 to ByteArray(4))
        assertTrue(manager.deleteConfig(dir))
        assertFalse(dir.exists())
    }

    @Test
    fun `loadConfig reads only the known pages that exist`() = runBlocking<Unit> {
        val dir = session("a", null, 1 to byteArrayOf(1, 2), 4 to byteArrayOf(3), 99 to byteArrayOf(9))
        val pages = manager.loadConfig(dir)
        assertEquals(setOf(1.toByte(), 4.toByte()), pages.keys)
        assertContentEquals(byteArrayOf(1, 2), pages[1.toByte()])
    }

    // ---- download ------------------------------------------------------------------------------------------

    private suspend fun connectedClient(ecu: ClientFakeEcu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025)): SpeeduinoClient =
        SpeeduinoClient(ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {}).also { it.connect() }

    @Test
    fun `downloads every page and writes info and page files`() = runBlocking<Unit> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025)
        for ((page, size) in ConfigManager.PAGE_SIZES) ecu.load(page.toInt(), 0, ByteArray(size) { (page + it).toByte() })
        val client = connectedClient(ecu)
        val progress = mutableListOf<String>()

        val result = manager.downloadAllConfigs(client) { _, _, message -> progress += message }

        assertTrue(result.success, result.error)
        assertNull(result.error)
        assertEquals(11, result.pagesDownloaded)
        assertEquals(11, result.totalPages)
        assertEquals(2, result.capability!!.protocolVersion)
        assertTrue(result.timestamp.matches(Regex("""\d{8}_\d{6}""")))
        val dir = result.sessionDir!!
        assertTrue(File(dir, "info.txt").readText().contains("Protocol Version: 2"))
        assertContentEquals(ByteArray(288) { (2 + it).toByte() }, File(dir, "page_2.bin").readBytes())
        assertEquals(288, result.pages[2.toByte()]!!.size)
        assertTrue(progress.first().startsWith("Obtendo capacidades"))
        assertTrue(progress.last().startsWith("Download concluído!"))
        assertEquals(dir.name, manager.latestSavedConfig()!!.name)
    }

    @Test
    fun `uses a default block size when the ecu does not report one`() = runBlocking<Unit> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025)
        val client = connectedClient(ecu)
        ecu.onFrame = { cmd, _ -> if (cmd == 'f') ModernEnvelopeFake.framedResponse(byteArrayOf(2, 0, 0, 0, 0)) else null }
        val result = manager.downloadAllConfigs(client) { _, _, _ -> }
        assertTrue(result.success, result.error)
        assertEquals(0, result.capability!!.blockingFactor)
    }

    @Test
    fun `failed pages are reported without aborting the download`() = runBlocking<Unit> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025)
        val client = connectedClient(ecu)
        ecu.onFrame = { cmd, p ->
            if (cmd == 'p' && (p[1].toInt() and 0xFF) == 7) listOf(byteArrayOf(0x00, 0x01), byteArrayOf(0x84.toByte()), ByteArray(4)) else null
        }
        val progress = mutableListOf<String>()
        val result = manager.downloadAllConfigs(client) { _, _, message -> progress += message }
        assertFalse(result.success)
        assertEquals(10, result.pagesDownloaded)
        assertEquals("Páginas com falha: 7", result.error)
        assertTrue(progress.any { it.startsWith("Erro na página 7") })
        assertTrue(progress.last().startsWith("Download concluído com falhas: 7"))
    }

    @Test
    fun `an unexpected failure becomes an error result`() = runBlocking<Unit> {
        val client = connectedClient()
        val result = manager.downloadAllConfigs(client) { _, _, _ -> throw IllegalStateException("cancelado pela UI") }
        assertFalse(result.success)
        assertEquals("cancelado pela UI", result.error)
        assertNull(result.sessionDir)
        assertNull(result.capability)
        assertTrue(result.pages.isEmpty())
        assertEquals(0, result.pagesDownloaded)
    }

    // ---- zip ----------------------------------------------------------------------------------------------------

    @Test
    fun `a session survives a zip round trip and sub directories are skipped`() {
        val dir = session("a", null, 1 to byteArrayOf(1, 2, 3), 2 to byteArrayOf(4), info = "ECU Family: MS3")
        File(dir, "nested").mkdirs()
        File(dir, "nested/ignored.txt").writeText("não exportado")
        val out = ByteArrayOutputStream()
        manager.exportSessionToZip(dir, out)

        val imported = manager.importSessionFromZip(ByteArrayInputStream(out.toByteArray()))

        assertTrue(imported.name.endsWith("_imported"))
        assertContentEquals(byteArrayOf(1, 2, 3), File(imported, "page_1.bin").readBytes())
        assertEquals("ECU Family: MS3", File(imported, "info.txt").readText())
        assertFalse(File(imported, "nested").exists())
    }

    @Test
    fun `exporting an empty or missing directory yields an empty zip`() {
        val out = ByteArrayOutputStream()
        manager.exportSessionToZip(File(root, "nao-existe"), out)
        assertTrue(out.size() > 0) // cabeçalho do zip vazio
    }

    @Test
    fun `zip entries that escape the session directory are rejected`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("../fora.txt")); zip.write(1); zip.closeEntry()
        }
        assertFailsWith<IllegalArgumentException> { manager.importSessionFromZip(ByteArrayInputStream(out.toByteArray())) }
        assertFalse(File(sessionsDir, "fora.txt").exists())
    }

    @Test
    fun `zip directories and nested files are extracted`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("sub/")); zip.closeEntry()
            zip.putNextEntry(ZipEntry("sub/page_1.bin")); zip.write(byteArrayOf(5, 6)); zip.closeEntry()
        }
        val imported = manager.importSessionFromZip(ByteArrayInputStream(out.toByteArray()))
        assertContentEquals(byteArrayOf(5, 6), File(imported, "sub/page_1.bin").readBytes())
    }

    // ---- MSQ -----------------------------------------------------------------------------------------------------

    private val ini = IniParser.parse(
        "speeduino.ini",
        """
        [MegaTune]
        signature = "speeduino 202501"

        [Constants]
        nPages = 2
        pageSize = 128, 288

        page = 1
        reqFuel = scalar, U08, 24, "ms", 0.1, 0.0, 0.0, 25.0, 1
        """.trimIndent(),
    )

    @Test
    fun `msq export names the fields from the ini`() {
        val page = ByteArray(128).also { it[24] = 87 }
        val xml = manager.exportSessionToMsq(ini, mapOf(1 to page))
        assertTrue(xml.contains("reqFuel"), xml)
        assertTrue(xml.contains("speeduino 202501"))
    }

    @Test
    fun `msq import applies named values over copies of the base pages`() {
        val base = ByteArray(128).also { it[24] = 50 }
        val source = ByteArray(128).also { it[24] = 87 }
        val xml = manager.exportSessionToMsq(ini, mapOf(1 to source))

        val dir = manager.importSessionFromMsq(xml, ini, mapOf(1 to base))

        assertTrue(dir.name.endsWith("_imported_msq"))
        assertEquals(87, File(dir, "page_1.bin").readBytes()[24].toInt())
        assertEquals(50, base[24].toInt()) // a base original não é alterada
    }

    // ---- tabelas e constantes carregadas da sessão ------------------------------------------------------------

    private val speeduinoPages = arrayOf(
        1 to ByteArray(128), 2 to ByteArray(288), 3 to ByteArray(288), 4 to ByteArray(128), 5 to ByteArray(288),
    )

    @Test
    fun `tables are parsed from a speeduino session`() = runBlocking<Unit> {
        val dir = session("sp", 5L, *speeduinoPages)
        assertNotNull(manager.loadVeTable(dir, 1))
        assertNotNull(manager.loadIgnitionTable(dir, 1))
        assertNotNull(manager.loadAfrTable(dir))
        assertNotNull(manager.loadEngineConstants(dir))
        assertNotNull(manager.loadTriggerSettings(dir))
        // sem argumento usa a sessão mais recente
        assertNotNull(manager.loadVeTable())
        assertNotNull(manager.loadIgnitionTable())
        assertNotNull(manager.loadAfrTable())
    }

    @Test
    fun `without sessions the latest-session loaders return null`() = runBlocking<Unit> {
        assertNull(manager.loadVeTable())
        assertNull(manager.loadIgnitionTable(2))
        assertNull(manager.loadAfrTable())
    }

    @Test
    fun `extra map indices read their own pages`() = runBlocking<Unit> {
        val dir = session("maps", null, 1 to ByteArray(128), 7 to ByteArray(288), 8 to ByteArray(288), 9 to ByteArray(288), 10 to ByteArray(288), 11 to ByteArray(288), 12 to ByteArray(288), 2 to ByteArray(288), 3 to ByteArray(288))
        for (index in 2..4) {
            assertNotNull(manager.loadVeTable(dir, index), "ve $index")
            assertNotNull(manager.loadIgnitionTable(dir, index), "ign $index")
        }
        // índice fora do intervalo cai nas páginas padrão
        assertNotNull(manager.loadVeTable(dir, 9))
        assertNotNull(manager.loadIgnitionTable(dir, 9))
        // páginas ausentes
        val empty = session("empty", null)
        for (index in 2..4) {
            assertNull(manager.loadVeTable(empty, index))
            assertNull(manager.loadIgnitionTable(empty, index))
        }
        assertNull(manager.loadVeTable(empty, 9))
        assertNull(manager.loadIgnitionTable(empty, 9))
    }

    @Test
    fun `missing or corrupt constants and trigger pages yield null`() = runBlocking<Unit> {
        val empty = session("empty", null)
        assertNull(manager.loadEngineConstants(empty))
        assertNull(manager.loadTriggerSettings(empty))
        val corrupt = session("corrupt", null, 1 to ByteArray(10), 4 to ByteArray(10))
        assertNull(manager.loadEngineConstants(corrupt))
        assertNull(manager.loadTriggerSettings(corrupt))
        // a tabela de índice extra com constantes corrompidas ainda carrega (load type padrão)
        val withCorruptConstants = session("c2", null, 1 to ByteArray(10), 7 to ByteArray(288))
        assertNotNull(manager.loadVeTable(withCorruptConstants, 2))
    }

    @Test
    fun `the ecu family comes from info txt and falls back to speeduino`() = runBlocking<Unit> {
        val pages = speeduinoPages
        val speeduino = session("a", null, *pages)
        val ms3 = session("b", null, *pages, info = "Speeduino Configuration\nECU Family: MS3\n")
        val invalid = session("c", null, *pages, info = "ECU Family: NAO_EXISTE")
        val noLine = session("d", null, *pages, info = "Timestamp: x")
        assertNotNull(manager.loadVeTable(speeduino, 1))
        assertNull(manager.loadVeTable(ms3, 1)) // MS3 não monta a tabela só com a página 2
        assertNotNull(manager.loadVeTable(invalid, 1))
        assertNotNull(manager.loadVeTable(noLine, 1))
        assertEquals(EcuFamily.MS3, EcuFamily.valueOf("MS3"))
    }

    @Test
    fun `page data compares by content`() {
        val a = PageData(1, 4, 0, byteArrayOf(1, 2))
        assertEquals(a, PageData(1, 4, 0, byteArrayOf(1, 2)))
        assertEquals(a.hashCode(), PageData(1, 4, 0, byteArrayOf(1, 2)).hashCode())
        assertEquals(a, a)
        assertFalse(a.equals(null))
        assertFalse(a.equals("x"))
        assertFalse(a == PageData(2, 4, 0, byteArrayOf(1, 2)))
        assertFalse(a == PageData(1, 5, 0, byteArrayOf(1, 2)))
        assertFalse(a == PageData(1, 4, 9, byteArrayOf(1, 2)))
        assertFalse(a == PageData(1, 4, 0, byteArrayOf(1, 3)))
    }
}
