package io.ecucore

import io.ecucore.model.EcuFamily
import io.ecucore.model.Page6Validator
import io.ecucore.sync.ConfigSyncService
import io.ecucore.sync.RestoreOutcome
import io.ecucore.sync.SessionSyncPrompt
import io.ecucore.sync.SessionTablesSnapshot
import io.ecucore.sync.SyncDecision
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigSyncServiceTest {

    private val root: File = Files.createTempDirectory("configsync").toFile()
    private val manager = ConfigManager(root)
    private val service = ConfigSyncService(manager)
    private val sessionsDir = File(root, ConfigManager.CONFIG_DIR)

    @AfterTest
    fun cleanup() { root.deleteRecursively() }

    private val rangeErr = listOf(byteArrayOf(0x00, 0x01), byteArrayOf(0x84.toByte()), ByteArray(4))
    private val busyErr = listOf(byteArrayOf(0x00, 0x01), byteArrayOf(0x85.toByte()), ByteArray(4))

    private fun session(name: String, vararg pages: Pair<Int, ByteArray>, info: String? = null): File {
        val dir = File(sessionsDir, name).apply { mkdirs() }
        pages.forEach { (n, bytes) -> File(dir, "page_$n.bin").writeBytes(bytes) }
        info?.let { File(dir, "info.txt").writeText(it) }
        return dir
    }

    private fun fullBackup(name: String = "backup"): File =
        session(name, *ConfigManager.PAGE_SIZES.map { (p, size) -> p.toInt() to ByteArray(size) { (p + it).toByte() } }.toTypedArray())

    private val allPages: Set<Byte> = ConfigManager.PAGE_SIZES.keys

    private suspend fun connected(): Pair<SpeeduinoClient, ClientFakeEcu> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025)
        val client = SpeeduinoClient(ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        client.connect()
        return client to ecu
    }

    private fun pageOf(payload: ByteArray) = payload[1].toInt() and 0xFF

    // ---- download e decisão de sincronização --------------------------------------------------------------

    @Test
    fun `the ecu session is used when there is no local session`() = runBlocking<Unit> {
        val (client, _) = connected()
        val (result, decision) = service.downloadAndResolveSync(client, null) { _, _, _ -> }
        assertTrue(result.success)
        assertEquals(result.sessionDir, decision.sessionDir)
        assertNull(decision.prompt)
    }

    @Test
    fun `identical local and ecu sessions need no prompt`() = runBlocking<Unit> {
        val (client, _) = connected()
        val first = service.downloadAndResolveSync(client, null) { _, _, _ -> }
        val (_, decision) = service.downloadAndResolveSync(client, first.second.sessionDir) { _, _, _ -> }
        assertNull(decision.prompt)
        assertNotNull(decision.sessionDir)
    }

    @Test
    fun `a different local session still loads the ecu data but offers the local backup`() = runBlocking<Unit> {
        val (client, ecu) = connected()
        ecu.load(2, 0, ByteArray(288) { 9 })
        val local = session("local", 2 to ByteArray(288) { 1 })
        val (result, decision) = service.downloadAndResolveSync(client, local) { _, _, _ -> }
        assertEquals(result.sessionDir, decision.sessionDir) // a ECU é a fonte da verdade
        assertEquals(SessionSyncPrompt(local, result.sessionDir!!), decision.prompt)
    }

    @Test
    fun `a failed download resolves to no session`() = runBlocking<Unit> {
        val (client, ecu) = connected()
        ecu.onFrame = { cmd, p -> if (cmd == 'p' && pageOf(p) == 3) rangeErr else null }
        val (result, decision) = service.downloadAndResolveSync(client, null) { _, _, _ -> }
        assertFalse(result.success)
        assertEquals(SyncDecision(sessionDir = null, prompt = null), decision)
    }

    // ---- assinatura, backup MS3 e tabelas ----------------------------------------------------------------------

    @Test
    fun `session signature is a crc per page`() = runBlocking<Unit> {
        val data = byteArrayOf(1, 2, 3, 4)
        val dir = session("sig", 1 to data)
        val expected = CRC32().also { it.update(data) }.value
        assertEquals(mapOf(1.toByte() to expected), service.sessionSignature(dir))
        assertEquals(emptyMap(), service.sessionSignature(File(root, "nao-existe")))
        assertEquals(emptyMap(), service.sessionSignature(session("vazia")))
    }

    @Test
    fun `ms3 sessions are backed up before a write and other families are not`() = runBlocking<Unit> {
        val dir = session("s1", 1 to byteArrayOf(1, 2, 3), info = "ECU Family: MS3")
        assertNull(service.ensureMs3WriteSafetyBackup(dir, EcuFamily.SPEEDUINO))
        assertNull(service.ensureMs3WriteSafetyBackup(dir, EcuFamily.RUSEFI))
        assertNull(service.ensureMs3WriteSafetyBackup(File(root, "arquivo"), EcuFamily.MS3))
        assertNull(service.ensureMs3WriteSafetyBackup(File(dir, "page_1.bin"), EcuFamily.MS3)) // não é diretório

        val backup = service.ensureMs3WriteSafetyBackup(dir, EcuFamily.MS3)!!
        assertTrue(backup.name.startsWith("s1_ms3_backup_"))
        assertEquals(dir.parentFile, backup.parentFile)
        assertContentEquals(byteArrayOf(1, 2, 3), File(backup, "page_1.bin").readBytes())
        assertEquals("ECU Family: MS3", File(backup, "info.txt").readText())
    }

    @Test
    fun `tables are loaded from a session`() = runBlocking<Unit> {
        val full = service.loadTablesFromSession(fullBackup("full"))
        assertNotNull(full.veTable)
        assertNotNull(full.ignitionTable)
        assertNotNull(full.afrTable)
        assertNotNull(full.engineConstants)
        assertNotNull(full.triggerSettings)
        val empty = service.loadTablesFromSession(session("vazia"))
        assertNull(empty.veTable)
        assertNull(empty.veTable2)
        assertNull(empty.ignitionTable2)
        assertNull(empty.engineConstants)
        assertNull(empty.triggerSettings)
        assertTrue(SessionTablesSnapshot(null, null, null, null, null, null, null).veTable == null)
    }

    // ---- restore -----------------------------------------------------------------------------------------------------

    @Test
    fun `restoring an empty backup is refused`() = runBlocking<Unit> {
        val (client, _) = connected()
        val error = assertFailsWith<IllegalStateException> { service.restoreConfigToEcu(client, session("vazia")) }
        assertTrue(error.message!!.contains("sem páginas válidas"))
    }

    @Test
    fun `a full restore writes every page except the status page and burns once`() = runBlocking<Unit> {
        val (client, ecu) = connected()
        val outcome = service.restoreConfigToEcu(client, fullBackup())
        assertEquals(RestoreOutcome(outcome.warnings, emptyList(), true), outcome)
        assertEquals(listOf("Página 0 ignorada no restore (read-only/status)"), outcome.warnings)
        assertEquals((1..10).toSet(), ecu.writes.map { it.id }.toSet())
        assertEquals(1, ecu.burns.size)
        assertContentEquals(ByteArray(288) { (2 + it).toByte() }, ecu.mem(2).copyOf(288))
    }

    @Test
    fun `skipped missing and wrongly sized pages are reported`() = runBlocking<Unit> {
        val (client, ecu) = connected()
        val dir = session("parcial", 1 to ByteArray(128), 2 to ByteArray(10))
        val skip = (3..10).map { it.toByte() }.toSet() - 5 + 0
        val error = assertFailsWith<IllegalStateException> { service.restoreConfigToEcu(client, dir, skipPages = skip) }
        assertTrue(error.message!!.contains("Página 2 com tamanho inesperado (10 != 288)"), error.message)
        assertEquals(setOf(1), ecu.writes.map { it.id }.toSet())
        assertEquals(1, ecu.burns.size) // o burn roda mesmo assim, o erro é lançado depois
    }

    @Test
    fun `warnings explain skipped and absent pages`() = runBlocking<Unit> {
        val (client, ecu) = connected()
        val dir = session("so1", 1 to ByteArray(128))
        val outcome = service.restoreConfigToEcu(client, dir, skipPages = setOf(3.toByte()))
        assertTrue("Página 3 ignorada no restore (compatibilidade)" in outcome.warnings)
        assertTrue("Página 2 ausente no backup" in outcome.warnings)
        assertTrue("Página 0 ignorada no restore (read-only/status)" in outcome.warnings)
        assertEquals(setOf(1), ecu.writes.map { it.id }.toSet())
    }

    @Test
    fun `a page that fails twice is retried and then succeeds`() = runBlocking<Unit> {
        val (client, ecu) = connected()
        var failures = 0
        ecu.onFrame = { cmd, p -> if (cmd == 'M' && pageOf(p) == 1 && failures++ < 2) busyErr else null }
        val dir = session("retry", 1 to ByteArray(128) { 5 })
        val outcome = service.restoreConfigToEcu(client, dir, skipPages = (2..10).map { it.toByte() }.toSet())
        assertTrue(outcome.completed)
        assertEquals(3, failures)
        assertEquals(5, ecu.mem(1)[0].toInt())
    }

    @Test
    fun `a page that keeps failing aborts the restore with every error`() = runBlocking<Unit> {
        val (client, ecu) = connected()
        ecu.onFrame = { cmd, p -> if (cmd == 'M' && pageOf(p) == 1) busyErr else null }
        val dir = session("falha", 1 to ByteArray(128))
        val error = assertFailsWith<IllegalStateException> {
            service.restoreConfigToEcu(client, dir, skipPages = (2..10).map { it.toByte() }.toSet())
        }
        assertTrue(error.message!!.contains("Falha ao gravar página 1 (tentativa 3)"), error.message)
        assertTrue(ecu.burns.isEmpty()) // nada foi escrito: sem burn
    }

    @Test
    fun `range errors on other pages are warnings and the restore continues`() = runBlocking<Unit> {
        val (client, ecu) = connected()
        ecu.onFrame = { cmd, p -> if (cmd == 'M' && pageOf(p) == 1) rangeErr else null }
        val dir = session("range", 1 to ByteArray(128), 2 to ByteArray(288))
        val outcome = service.restoreConfigToEcu(client, dir, skipPages = (3..10).map { it.toByte() }.toSet())
        assertTrue(outcome.completed)
        assertTrue(outcome.inconsistentPages.isEmpty())
        assertTrue("Página 1 rejeitada (RANGE_ERR)" in outcome.warnings)
        assertEquals(setOf(2), ecu.writes.map { it.id }.toSet())
    }

    @Test
    fun `stop on range error marks the page inconsistent and skips the burn`() = runBlocking<Unit> {
        val (client, ecu) = connected()
        ecu.onFrame = { cmd, p -> if (cmd == 'M' && pageOf(p) == 2) rangeErr else null }
        val dir = session("stop", 1 to ByteArray(128), 2 to ByteArray(288), 3 to ByteArray(288))
        val outcome = service.restoreConfigToEcu(client, dir, stopOnRangeErr = true, applyTriggerFix = true)
        assertFalse(outcome.completed)
        assertEquals(listOf(2.toByte()), outcome.inconsistentPages)
        assertTrue(ecu.burns.isEmpty())
        assertEquals(setOf(1), ecu.writes.map { it.id }.toSet()) // página 3 nem foi tentada
    }

    @Test
    fun `page 6 is sanitized when the ecu rejects the raw restore`() = runBlocking<Unit> {
        val (client, ecu) = connected()
        var rejected = 0
        ecu.onFrame = { cmd, p -> if (cmd == 'M' && pageOf(p) == 6 && rejected++ < 1) rangeErr else null }
        val dir = session("p6", 6 to ByteArray(192))
        val outcome = service.restoreConfigToEcu(client, dir, skipPages = ((1..10) - 6).map { it.toByte() }.toSet())
        assertTrue("Página 6 corrigida (sanitização)" in outcome.warnings)
        val expected = Page6Validator.sanitize(ByteArray(192)).data
        assertContentEquals(expected, ecu.mem(6).copyOf(192))
        assertEquals(1, ecu.burns.size)
    }

    @Test
    fun `page 6 sanitization failures and no-op sanitization fall back to a range warning`() = runBlocking<Unit> {
        // a ECU rejeita tudo na página 6, inclusive a versão corrigida
        val (client, ecu) = connected()
        ecu.onFrame = { cmd, p -> if (cmd == 'M' && pageOf(p) == 6) rangeErr else null }
        val skip = ((1..10) - 6).map { it.toByte() }.toSet()
        val outcome = service.restoreConfigToEcu(client, session("p6a", 6 to ByteArray(192)), skipPages = skip)
        assertTrue(outcome.warnings.any { it.startsWith("Falha ao corrigir página 6") })
        assertTrue("Página 6 rejeitada (RANGE_ERR)" in outcome.warnings)

        // página já válida: a sanitização não muda nada, só o aviso de RANGE_ERR
        val valid = Page6Validator.sanitize(ByteArray(192)).data
        val outcome2 = service.restoreConfigToEcu(client, session("p6b", 6 to valid), skipPages = skip)
        assertTrue("Página 6 rejeitada (RANGE_ERR)" in outcome2.warnings)
        assertTrue(outcome2.warnings.none { it.contains("corrigida") })

        // stopOnRangeErr também vale para a página 6
        val outcome3 = service.restoreConfigToEcu(client, session("p6c", 6 to valid), skipPages = skip, stopOnRangeErr = true)
        assertEquals(listOf(6.toByte()), outcome3.inconsistentPages)
        assertFalse(outcome3.completed)
    }

    @Test
    fun `trigger fix rewrites page 4 through the typed settings`() = runBlocking<Unit> {
        val (client, ecu) = connected()
        val dir = session("trig", 4 to ByteArray(128))
        val outcome = service.restoreConfigToEcu(client, dir, skipPages = (1..10).map { it.toByte() }.toSet(), applyTriggerFix = true)
        assertTrue("Página 4 corrigida (Trigger Settings)" in outcome.warnings)
        assertTrue(ecu.writes.any { it.id == 4 })
        assertEquals(1, ecu.burns.size) // a correção conta como escrita: há burn
    }

    @Test
    fun `trigger fix without the page or with a failing write only warns`() = runBlocking<Unit> {
        val (client, ecu) = connected()
        val skip = (1..10).map { it.toByte() }.toSet()
        val absent = service.restoreConfigToEcu(client, session("sem4", 1 to ByteArray(128)), skipPages = skip, applyTriggerFix = true)
        assertTrue("Página 4 ausente no backup" in absent.warnings)

        ecu.onFrame = { cmd, p -> if (cmd == 'M' && pageOf(p) == 4) busyErr else null }
        val failing = service.restoreConfigToEcu(client, session("trigfail", 4 to ByteArray(128)), skipPages = skip, applyTriggerFix = true)
        assertTrue(failing.warnings.any { it.startsWith("Falha ao corrigir página 4") })
    }

    @Test
    fun `a failing burn is reported after writing`() = runBlocking<Unit> {
        val (client, ecu) = connected()
        ecu.onFrame = { cmd, _ -> if (cmd == 'B') rangeErr else null }
        val dir = session("burn", 1 to ByteArray(128))
        val error = assertFailsWith<IllegalStateException> {
            service.restoreConfigToEcu(client, dir, skipPages = (2..10).map { it.toByte() }.toSet())
        }
        assertTrue(error.message!!.contains("Falha ao executar burn"), error.message)
    }

    @Test
    fun `the live stream is paused and optionally restarted`() = runBlocking<Unit> {
        val (client, _) = connected()
        val dir = session("stream", 1 to ByteArray(128))
        val skip = (2..10).map { it.toByte() }.toSet()

        client.startLiveDataStream(20)
        assertTrue(client.isStreaming())
        service.restoreConfigToEcu(client, dir, skipPages = skip) // sem intervalo: não reinicia
        assertFalse(client.isStreaming())

        client.startLiveDataStream(20)
        service.restoreConfigToEcu(client, dir, skipPages = skip, restartStreamIntervalMs = 20)
        withTimeout(5000) { while (!client.isStreaming()) delay(10) }
        client.stopLiveDataStream()

        // sem stream ativo, nada é reiniciado mesmo pedindo
        service.restoreConfigToEcu(client, dir, skipPages = skip, restartStreamIntervalMs = 20)
        assertFalse(client.isStreaming())
    }
}
