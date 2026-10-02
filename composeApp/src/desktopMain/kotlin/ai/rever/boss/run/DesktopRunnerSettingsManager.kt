package ai.rever.boss.run

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.plugin.run.MAX_RERUN_DELAY_MS
import ai.rever.boss.plugin.run.MIN_RERUN_DELAY_MS
import ai.rever.boss.utils.SettingsBackupHelper
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.decodeFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Desktop implementation of RunnerSettingsManager.
 * Persists settings to ~/.boss/runner-settings.json
 *
 * Settings are loaded asynchronously on Dispatchers.IO to avoid blocking the main thread.
 * Default settings are provided immediately via StateFlow.
 *
 * Issue #347: Runner settings persistence
 */
actual object RunnerSettingsManager {
    private val logger = BossLogger.forComponent("RunnerSettingsManager")
    private val settingsFile = BossDirectories.resolve("runner-settings.json")
    private val backupFile = SettingsBackupHelper.backupFileFor(settingsFile)
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    // Coroutine scope for async operations - uses SupervisorJob so failures don't cancel other operations
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Serializes writes so overlapping saves persist in order, freshest last. The mutex alone
    // only orders them; atomicWriteText (temp file then atomic rename) is what makes each write
    // crash-safe, so a reader never sees a torn file and a crash mid-write cannot truncate it.
    private val saveMutex = Mutex()

    // Default settings provided immediately, updated async when file is loaded
    private val _currentSettings = MutableStateFlow(RunnerSettings())
    actual val currentSettings: StateFlow<RunnerSettings> = _currentSettings.asStateFlow()

    init {
        // Load settings asynchronously to avoid blocking main thread
        scope.launch {
            loadSettingsAsync()
        }
    }

    /**
     * Load settings asynchronously on Dispatchers.IO.
     * Creates parent directories and default settings file if needed.
     */
    private suspend fun loadSettingsAsync() =
        withContext(Dispatchers.IO) {
            settingsFile.parentFile?.mkdirs()
            val loaded =
                SettingsBackupHelper.loadWithBackupRecovery(
                    primaryFile = settingsFile,
                    backupFile = backupFile,
                    logger = logger,
                    category = LogCategory.SYSTEM,
                    decode = { json.decodeFromString<RunnerSettings>(it) },
                )
            if (loaded != null) {
                _currentSettings.value = loaded
                logger.debug(LogCategory.SYSTEM, "Loaded settings")
            } else {
                // Create default settings file and update backup
                saveMutex.withLock {
                    val content = json.encodeToString(RunnerSettings.serializer(), _currentSettings.value)
                    settingsFile.atomicWriteText(content)
                    SettingsBackupHelper.updateBackup(backupFile, content, logger, LogCategory.SYSTEM)
                }
                logger.debug(LogCategory.SYSTEM, "Created default settings file")
            }
        }

    internal suspend fun reloadForTest() = loadSettingsAsync()

    /**
     * Save current settings to persistent storage.
     */
    actual suspend fun saveSettings() =
        withContext(Dispatchers.IO) {
            saveMutex.withLock {
                try {
                    // Encode inside the lock so the last writer persists the freshest state.
                    val content = json.encodeToString(RunnerSettings.serializer(), _currentSettings.value)
                    settingsFile.atomicWriteText(content)
                    SettingsBackupHelper.updateBackup(backupFile, content, logger, LogCategory.SYSTEM)
                    logger.debug(LogCategory.SYSTEM, "Settings saved")
                } catch (e: Exception) {
                    logger.warn(LogCategory.SYSTEM, "Error saving settings", error = e)
                }
            }
        }

    /**
     * Update settings and persist.
     */
    actual suspend fun updateSettings(settings: RunnerSettings) {
        _currentSettings.value = settings
        saveSettings()
    }

    /**
     * Reset settings to defaults.
     */
    actual suspend fun resetToDefault() {
        updateSettings(RunnerSettings())
    }

    /**
     * Update only the terminal target setting.
     */
    actual suspend fun setTerminalTarget(target: RunnerTerminalTarget) {
        updateSettings(_currentSettings.value.copy(terminalTarget = target))
    }

    /**
     * Update only the focus on run setting.
     */
    actual suspend fun setFocusOnRun(enabled: Boolean) {
        updateSettings(_currentSettings.value.copy(focusOnRun = enabled))
    }

    /**
     * Update only the notify on exit setting.
     */
    actual suspend fun setNotifyOnExit(enabled: Boolean) {
        updateSettings(_currentSettings.value.copy(notifyOnExit = enabled))
    }

    /**
     * Update only the re-run delay setting.
     */
    actual suspend fun setRerunDelayMs(delayMs: Long) {
        val clampedDelay = delayMs.coerceIn(MIN_RERUN_DELAY_MS, MAX_RERUN_DELAY_MS)
        updateSettings(_currentSettings.value.copy(rerunDelayMs = clampedDelay))
    }
}
