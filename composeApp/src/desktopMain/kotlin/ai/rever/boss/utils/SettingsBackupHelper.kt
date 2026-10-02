package ai.rever.boss.utils

import ai.rever.boss.utils.logging.ComponentLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.decodeFailure
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException

/**
 * Helper for persisting and recovering settings with a last-known-good backup (.bak sibling).
 *
 * Prevents destructive data loss on decode errors (e.g. power loss, crash during write, or
 * format changes) by maintaining an atomic backup of cleanly decoded settings and attempting
 * recovery from the backup before falling back to defaults (#1033).
 */
internal object SettingsBackupHelper {
    /**
     * Sibling backup file for [primaryFile].
     */
    fun backupFileFor(primaryFile: File): File = File(primaryFile.parentFile, "${primaryFile.name}.bak")

    /**
     * Atomically update the sibling backup file with known-good content.
     */
    fun updateBackup(
        backupFile: File,
        content: String,
        logger: ComponentLogger? = null,
        category: LogCategory = LogCategory.SYSTEM,
    ) {
        try {
            backupFile.parentFile?.mkdirs()
            backupFile.atomicWriteText(content)
        } catch (e: IOException) {
            logger?.warn(category, "Failed to update settings backup file", error = e)
        }
    }

    /**
     * Attempt to load and decode from [primaryFile]. If [primaryFile] does not exist,
     * checks if [backupFile] exists and recovers from it. If [primaryFile] fails to decode,
     * attempts recovery from [backupFile]. If both fail or neither exists, returns null.
     */
    inline fun <T> loadWithBackupRecovery(
        primaryFile: File,
        backupFile: File,
        logger: ComponentLogger,
        category: LogCategory,
        decode: (String) -> T,
    ): T? {
        val primary = loadPrimary(primaryFile, backupFile, logger, category, decode)
        return primary ?: recoverFromBackup(primaryFile, backupFile, logger, category, decode)
    }

    inline fun <T> loadPrimary(
        primaryFile: File,
        backupFile: File,
        logger: ComponentLogger,
        category: LogCategory,
        decode: (String) -> T,
    ): T? {
        if (!primaryFile.exists()) return null
        var result: T? = null
        val content =
            try {
                primaryFile.readText()
            } catch (e: IOException) {
                logger.warn(category, "Failed to read primary settings file", error = e)
                null
            }
        if (content != null) {
            try {
                val decoded = decode(content)
                // Primary file decoded cleanly: update backup with known-good content
                updateBackup(backupFile, content, logger, category)
                result = decoded
            } catch (e: SerializationException) {
                logger.warn(
                    category,
                    "Failed to decode primary settings file, attempting recovery from backup",
                    decodeFailure(e),
                )
            } catch (e: IllegalArgumentException) {
                logger.warn(
                    category,
                    "Failed to decode primary settings file, attempting recovery from backup",
                    error = e,
                )
            } catch (e: IllegalStateException) {
                logger.warn(
                    category,
                    "Failed to decode primary settings file, attempting recovery from backup",
                    error = e,
                )
            }
        }
        return result
    }

    inline fun <T> recoverFromBackup(
        primaryFile: File,
        backupFile: File,
        logger: ComponentLogger,
        category: LogCategory,
        decode: (String) -> T,
    ): T? {
        if (!backupFile.exists()) return null
        var result: T? = null
        try {
            val backupContent = backupFile.readText()
            val recovered = decode(backupContent)
            // Restore primary file from valid backup
            primaryFile.parentFile?.mkdirs()
            primaryFile.atomicWriteText(backupContent)
            logger.info(
                category,
                "Successfully recovered settings from backup file",
                mapOf("path" to backupFile.absolutePath),
            )
            result = recovered
        } catch (e: SerializationException) {
            logger.warn(
                category,
                "Failed to recover settings from backup file",
                decodeFailure(e),
            )
        } catch (e: IOException) {
            logger.warn(
                category,
                "Failed to read backup settings file",
                error = e,
            )
        } catch (e: IllegalArgumentException) {
            logger.warn(
                category,
                "Failed to recover settings from backup file",
                error = e,
            )
        } catch (e: IllegalStateException) {
            logger.warn(
                category,
                "Failed to recover settings from backup file",
                error = e,
            )
        }
        return result
    }
}
