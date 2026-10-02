package ai.rever.boss.utils

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsBackupHelperTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Serializable
    private data class DummySettings(
        val enabled: Boolean = true,
        val counter: Int = 42,
        val name: String = "test",
    )

    private val json = Json { prettyPrint = true }
    private val logger = BossLogger.forComponent("SettingsBackupHelperTest")

    @Test
    fun `clean primary file decodes successfully and creates backup sibling`() {
        val primaryFile = tempFolder.newFile("settings.json")
        val backupFile = SettingsBackupHelper.backupFileFor(primaryFile)
        val original = DummySettings(enabled = false, counter = 100, name = "primary")
        val encoded = json.encodeToString(DummySettings.serializer(), original)
        primaryFile.writeText(encoded)

        val loaded =
            SettingsBackupHelper.loadWithBackupRecovery(
                primaryFile = primaryFile,
                backupFile = backupFile,
                logger = logger,
                category = LogCategory.SYSTEM,
                decode = { json.decodeFromString<DummySettings>(it) },
            )

        assertNotNull(loaded)
        assertEquals(original, loaded)
        assertTrue(backupFile.exists())
        assertEquals(encoded, backupFile.readText())
    }

    @Test
    fun `corrupted primary file recovers seamlessly from valid backup sibling`() {
        val primaryFile = tempFolder.newFile("corrupted.json")
        val backupFile = SettingsBackupHelper.backupFileFor(primaryFile)
        val validBackup = DummySettings(enabled = true, counter = 999, name = "from-backup")
        val validEncoded = json.encodeToString(DummySettings.serializer(), validBackup)

        primaryFile.writeText("{ corrupted json syntax !!")
        backupFile.writeText(validEncoded)

        val loaded =
            SettingsBackupHelper.loadWithBackupRecovery(
                primaryFile = primaryFile,
                backupFile = backupFile,
                logger = logger,
                category = LogCategory.SYSTEM,
                decode = { json.decodeFromString<DummySettings>(it) },
            )

        assertNotNull(loaded)
        assertEquals(validBackup, loaded)
        assertEquals(validEncoded, primaryFile.readText())
    }

    @Test
    fun `missing primary file recovers from valid backup sibling`() {
        val dir = tempFolder.newFolder("missing_primary")
        val primaryFile = File(dir, "primary.json")
        val backupFile = SettingsBackupHelper.backupFileFor(primaryFile)
        val validBackup = DummySettings(enabled = false, counter = 77, name = "recovered")
        val validEncoded = json.encodeToString(DummySettings.serializer(), validBackup)
        backupFile.writeText(validEncoded)

        val loaded =
            SettingsBackupHelper.loadWithBackupRecovery(
                primaryFile = primaryFile,
                backupFile = backupFile,
                logger = logger,
                category = LogCategory.SYSTEM,
                decode = { json.decodeFromString<DummySettings>(it) },
            )

        assertNotNull(loaded)
        assertEquals(validBackup, loaded)
        assertTrue(primaryFile.exists())
        assertEquals(validEncoded, primaryFile.readText())
    }

    @Test
    fun `corrupted primary file and missing backup file safely returns null`() {
        val primaryFile = tempFolder.newFile("unrecoverable.json")
        val backupFile = SettingsBackupHelper.backupFileFor(primaryFile)
        primaryFile.writeText("{ invalid json without backup")

        val loaded =
            SettingsBackupHelper.loadWithBackupRecovery(
                primaryFile = primaryFile,
                backupFile = backupFile,
                logger = logger,
                category = LogCategory.SYSTEM,
                decode = { json.decodeFromString<DummySettings>(it) },
            )

        assertNull(loaded)
    }

    @Test
    fun `both primary and backup files corrupted safely returns null`() {
        val primaryFile = tempFolder.newFile("both_corrupted.json")
        val backupFile = SettingsBackupHelper.backupFileFor(primaryFile)
        primaryFile.writeText("{ bad primary }")
        backupFile.writeText("{ bad backup }")

        val loaded =
            SettingsBackupHelper.loadWithBackupRecovery(
                primaryFile = primaryFile,
                backupFile = backupFile,
                logger = logger,
                category = LogCategory.SYSTEM,
                decode = { json.decodeFromString<DummySettings>(it) },
            )

        assertNull(loaded)
    }

    @Test
    fun `updateBackup creates parent directories and atomically writes content`() {
        val subDir = File(tempFolder.root, "nested/path")
        val backupFile = File(subDir, "settings.json.bak")
        val content = "{\"test\": true}"

        SettingsBackupHelper.updateBackup(backupFile, content, logger, LogCategory.SYSTEM)

        assertTrue(backupFile.exists())
        assertEquals(content, backupFile.readText())
    }
}
