package ai.rever.boss.plugin.scrollbar

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.plugin.pathutils.BossDirectories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Desktop implementation of scrollbar settings manager.
 * Manages loading and saving of scrollbar appearance settings.
 * Follows the BOSS settings management pattern with:
 * - JSON persistence in ~/.boss/scrollbar-settings.json
 * - Atomic writes with fsync durability and .bak recovery
 * - Concurrency synchronization via write mutex
 * - Redacted decode failure logging
 * - Automatic directory creation
 * - Synchronous load on init, asynchronous save
 * - Graceful error handling with fallback to defaults
 */
actual object ScrollbarSettingsManager {
    private val logger = BossLogger.forComponent("ScrollbarSettingsManager")
    private val settingsFile = BossDirectories.resolve("scrollbar-settings.json")
    private val writeMutex = Mutex()
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    @Volatile
    private var settingsFileOverride: File? = null

    internal val effectiveSettingsFile: File
        get() = settingsFileOverride ?: settingsFile

    private val _currentSettings = MutableStateFlow(ScrollbarSettings())
    actual val currentSettings: StateFlow<ScrollbarSettings> = _currentSettings.asStateFlow()

    init {
        // Ensure directory exists
        settingsFile.parentFile?.mkdirs()

        // Load settings on initialization
        loadSettingsSync()
    }

    private fun backupFile(file: File): File = File(file.parentFile, "${file.name}.bak")

    private fun atomicWriteSettings(
        targetFile: File,
        content: String,
    ) {
        val parent = targetFile.parentFile ?: return
        if (!parent.exists()) {
            parent.mkdirs()
        }
        val tempFile = File.createTempFile(".scrollbar-settings-", ".tmp", parent)
        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.fd.sync()
            }
            val source = tempFile.toPath()
            val target = targetFile.toPath()
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun tryLoadFromBackup(file: File): ScrollbarSettings? {
        val backup = backupFile(file)
        if (!backup.isFile) {
            return null
        }
        return try {
            val backupContent = backup.readText()
            val restored = json.decodeFromString<ScrollbarSettings>(backupContent)
            logger.warn(LogCategory.SYSTEM, "Restored settings from backup", mapOf("path" to backup.absolutePath))
            restored
        } catch (e: SerializationException) {
            logger.warn(
                LogCategory.SYSTEM,
                "Backup settings file also corrupted",
                data =
                    mapOf(
                        "decodeFailure" to (e::class.simpleName ?: "SerializationException"),
                        "path" to backup.absolutePath,
                    ),
            )
            null
        } catch (e: IOException) {
            logger.warn(LogCategory.SYSTEM, "Failed to read backup settings", error = e)
            null
        }
    }

    /**
     * Load settings synchronously on startup.
     * If file doesn't exist, uses defaults and saves them.
     */
    private fun loadSettingsSync() {
        val file = effectiveSettingsFile
        try {
            if (file.exists()) {
                val content = file.readText()
                val settings = json.decodeFromString<ScrollbarSettings>(content)
                _currentSettings.value = settings
                logger.debug(LogCategory.SYSTEM, "Loaded settings", mapOf("path" to file.absolutePath))
            } else {
                val defaults = getDefaultSettings()
                _currentSettings.value = defaults

                try {
                    val content = json.encodeToString(ScrollbarSettings.serializer(), defaults)
                    atomicWriteSettings(file, content)
                    logger.debug(
                        LogCategory.SYSTEM,
                        "Created default settings",
                        mapOf("path" to file.absolutePath),
                    )
                } catch (e: IOException) {
                    logger.warn(LogCategory.SYSTEM, "Could not write default settings file", error = e)
                }
            }
        } catch (e: SerializationException) {
            logger.warn(
                LogCategory.SYSTEM,
                "Failed to load settings",
                data =
                    mapOf(
                        "decodeFailure" to (e::class.simpleName ?: "SerializationException"),
                        "path" to file.absolutePath,
                    ),
            )
            val restored = tryLoadFromBackup(file)
            _currentSettings.value = restored ?: getDefaultSettings()
        } catch (e: IOException) {
            logger.warn(
                LogCategory.SYSTEM,
                "Failed to load settings",
                data = mapOf("path" to file.absolutePath),
                error = e,
            )
            _currentSettings.value = getDefaultSettings()
        }
    }

    /**
     * Save current settings to disk asynchronously.
     */
    private suspend fun saveSettings() =
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val file = effectiveSettingsFile
                try {
                    val content = json.encodeToString(ScrollbarSettings.serializer(), _currentSettings.value)
                    atomicWriteSettings(file, content)
                    try {
                        atomicWriteSettings(backupFile(file), content)
                    } catch (backupEx: IOException) {
                        logger.debug(
                            LogCategory.SYSTEM,
                            "Failed to update backup settings",
                            mapOf("error" to (backupEx.message ?: "unknown")),
                        )
                    }
                    logger.debug(LogCategory.SYSTEM, "Settings saved", mapOf("path" to file.absolutePath))
                } catch (e: IOException) {
                    logger.warn(LogCategory.SYSTEM, "Failed to save settings", error = e)
                } catch (e: SerializationException) {
                    logger.warn(
                        LogCategory.SYSTEM,
                        "Failed to encode settings",
                        data =
                            mapOf(
                                "decodeFailure" to (e::class.simpleName ?: "SerializationException"),
                                "path" to file.absolutePath,
                            ),
                    )
                }
            }
        }

    /**
     * Update the current settings and save to disk asynchronously.
     */
    actual suspend fun updateSettings(settings: ScrollbarSettings) {
        _currentSettings.value = settings
        saveSettings()
    }

    /**
     * Reset settings to defaults and save.
     */
    actual suspend fun resetToDefault() {
        _currentSettings.value = getDefaultSettings()
        saveSettings()
    }

    actual fun getDefaultSettings(): ScrollbarSettings = ScrollbarSettings()

    internal fun reloadForTesting(override: File? = null) {
        settingsFileOverride = override
        loadSettingsSync()
    }

    internal fun resetForTesting() {
        settingsFileOverride = null
        _currentSettings.value = getDefaultSettings()
    }
}
