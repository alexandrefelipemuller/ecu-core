package io.ecucore.sync

import io.ecucore.ConfigDownloadResult
import io.ecucore.ConfigManager
import io.ecucore.model.AfrTable
import io.ecucore.model.EcuFamily
import io.ecucore.model.EngineConstants
import io.ecucore.model.IgnitionTable
import io.ecucore.model.Page6Validator
import io.ecucore.model.TriggerSettings
import io.ecucore.model.VeTable
import io.ecucore.transport.EcuTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32

data class SessionTablesSnapshot(
    val veTable: VeTable?,
    val veTable2: VeTable?,
    val ignitionTable: IgnitionTable?,
    val ignitionTable2: IgnitionTable?,
    val afrTable: AfrTable?,
    val engineConstants: EngineConstants?,
    val triggerSettings: TriggerSettings?,
)

data class SyncDecision(
    val sessionDir: File?,
    val prompt: SessionSyncPrompt?,
)

data class SessionSyncPrompt(
    val localSessionDir: File,
    val ecuSessionDir: File,
)

data class RestoreOutcome(
    val warnings: List<String>,
    val inconsistentPages: List<Byte>,
    val completed: Boolean,
)

class ConfigSyncService(
    private val configManager: ConfigManager,
) {
    /**
     * Downloads the current ECU config and resolves which session should be treated as the
     * active one locally.
     *
     * The ECU is always the source of truth: [SyncDecision.sessionDir] is the freshly
     * downloaded ECU session whenever the download succeeds, regardless of whether a local
     * session exists or differs from it. When a local session does differ, [SyncDecision.prompt]
     * is still populated so a caller can offer "restore my local backup over the ECU" as an
     * option - but that offer is informational, never a precondition for loading the ECU data
     * that was just downloaded.
     */
    suspend fun downloadAndResolveSync(
        client: EcuTransport,
        localSessionDir: File?,
        onProgress: (Int, Int, String) -> Unit,
    ): Pair<ConfigDownloadResult, SyncDecision> = withContext(Dispatchers.IO) {
        val result = configManager.downloadAllConfigs(client, onProgress)
        if (!result.success || result.sessionDir == null) {
            return@withContext result to SyncDecision(sessionDir = null, prompt = null)
        }

        val ecuSessionDir = result.sessionDir
        if (localSessionDir == null) {
            return@withContext result to SyncDecision(sessionDir = ecuSessionDir, prompt = null)
        }

        val ecuSignature = sessionSignature(ecuSessionDir)
        val localSignature = sessionSignature(localSessionDir)
        val prompt = if (ecuSignature != localSignature) {
            SessionSyncPrompt(localSessionDir, ecuSessionDir)
        } else {
            null
        }
        result to SyncDecision(sessionDir = ecuSessionDir, prompt = prompt)
    }

    /**
     * Backs up [sessionDir] before a write to an MS3 ECU, since MS3 firmware has historically
     * been more sensitive to partial/rejected page writes than Speeduino. Ported from the
     * Android app's `ensureMs3WriteSafetyBackup`. No-op (returns null) for any other ECU family.
     */
    suspend fun ensureMs3WriteSafetyBackup(sessionDir: File, ecuFamily: EcuFamily): File? = withContext(Dispatchers.IO) {
        if (ecuFamily != EcuFamily.MS3 || !sessionDir.isDirectory) {
            return@withContext null
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val backupDir = File(sessionDir.parentFile, "${sessionDir.name}_ms3_backup_$timestamp")
        sessionDir.copyRecursively(backupDir, overwrite = true)
        backupDir
    }

    suspend fun loadTablesFromSession(sessionDir: File): SessionTablesSnapshot = withContext(Dispatchers.IO) {
        SessionTablesSnapshot(
            veTable = configManager.loadVeTable(sessionDir, 1),
            veTable2 = configManager.loadVeTable(sessionDir, 2),
            ignitionTable = configManager.loadIgnitionTable(sessionDir, 1),
            ignitionTable2 = configManager.loadIgnitionTable(sessionDir, 2),
            afrTable = configManager.loadAfrTable(sessionDir),
            engineConstants = configManager.loadEngineConstants(sessionDir),
            triggerSettings = configManager.loadTriggerSettings(sessionDir),
        )
    }

    suspend fun restoreConfigToEcu(
        client: EcuTransport,
        sessionDir: File,
        skipPages: Set<Byte> = emptySet(),
        stopOnRangeErr: Boolean = false,
        applyTriggerFix: Boolean = false,
        restartStreamIntervalMs: Long? = null,
    ): RestoreOutcome = withContext(Dispatchers.IO) {
        val wasStreaming = client.isStreaming()
        if (wasStreaming) {
            client.pauseLiveDataStream()
        }

        val pages = configManager.loadConfig(sessionDir)
        if (pages.isEmpty()) {
            throw IllegalStateException("Backup sem páginas válidas")
        }

        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val inconsistentPages = mutableListOf<Byte>()
        var wroteAnyPage = false
        val restoreSkipPages = skipPages + 0.toByte()
        var stopDueToRangeErr = false

        pageLoop@ for ((pageNum, pageSize) in ConfigManager.PAGE_SIZES) {
            if (restoreSkipPages.contains(pageNum)) {
                val reason = when (pageNum.toInt()) {
                    0 -> "read-only/status"
                    else -> "compatibilidade"
                }
                warnings.add("Página $pageNum ignorada no restore ($reason)")
                continue
            }
            val data = pages[pageNum]
            if (data == null) {
                warnings.add("Página $pageNum ausente no backup")
                continue
            }
            if (data.size != pageSize) {
                errors.add("Página $pageNum com tamanho inesperado (${data.size} != $pageSize)")
                continue
            }

            var attempt = 0
            var success = false
            while (attempt < 3 && !success) {
                attempt++
                try {
                    client.writeRawPageWithoutBurn(pageNum.toInt() and 0xFF, data)
                    wroteAnyPage = true
                    success = true
                } catch (e: Exception) {
                    val errorText = e.message ?: "Erro desconhecido"
                    if (errorText.contains("RANGE_ERR")) {
                        if (pageNum.toInt() == 6) {
                            val sanitized = Page6Validator.sanitize(data)
                            if (sanitized.changed) {
                                try {
                                    client.writeRawPageWithoutBurn(pageNum.toInt() and 0xFF, sanitized.data)
                                    wroteAnyPage = true
                                    warnings.add("Página 6 corrigida (sanitização)")
                                    break
                                } catch (sanitizeError: Exception) {
                                    warnings.add("Falha ao corrigir página 6: ${sanitizeError.message}")
                                }
                            }
                        }

                        warnings.add("Página $pageNum rejeitada (RANGE_ERR)")
                        if (stopOnRangeErr) {
                            inconsistentPages.add(pageNum)
                            stopDueToRangeErr = true
                        }
                        break
                    }
                    val message = "Falha ao gravar página $pageNum (tentativa $attempt): $errorText"
                    if (attempt >= 3) {
                        errors.add(message)
                    } else {
                        delay(400)
                    }
                }
            }
            if (stopDueToRangeErr) {
                break@pageLoop
            }
            delay(150)
        }

        if (!stopDueToRangeErr && applyTriggerFix) {
            val triggerPage = pages[TriggerSettings.PAGE_NUMBER.toByte()]
            if (triggerPage != null) {
                try {
                    val triggerSettings = TriggerSettings.fromPageData(triggerPage)
                    client.writeTriggerSettings(triggerSettings, burn = false)
                    wroteAnyPage = true
                    warnings.add("Página ${TriggerSettings.PAGE_NUMBER} corrigida (Trigger Settings)")
                } catch (e: Exception) {
                    warnings.add("Falha ao corrigir página ${TriggerSettings.PAGE_NUMBER}: ${e.message}")
                }
            } else {
                warnings.add("Página ${TriggerSettings.PAGE_NUMBER} ausente no backup")
            }
        }

        if (!stopDueToRangeErr && wroteAnyPage) {
            try {
                client.burnConfigs()
            } catch (e: Exception) {
                errors.add("Falha ao executar burn: ${e.message}")
            }
        }

        if (errors.isNotEmpty()) {
            throw IllegalStateException(errors.joinToString(" | "))
        }

        if (wasStreaming && restartStreamIntervalMs != null) {
            client.startLiveDataStream(restartStreamIntervalMs)
        }

        return@withContext RestoreOutcome(
            warnings = warnings,
            inconsistentPages = inconsistentPages,
            completed = !stopDueToRangeErr,
        )
    }

    suspend fun sessionSignature(sessionDir: File): Map<Byte, Long> = withContext(Dispatchers.IO) {
        val pages = runCatching { configManager.loadConfig(sessionDir) }.getOrDefault(emptyMap())
        pages.mapValues { (_, data) ->
            val crc = CRC32()
            crc.update(data)
            crc.value
        }
    }
}
