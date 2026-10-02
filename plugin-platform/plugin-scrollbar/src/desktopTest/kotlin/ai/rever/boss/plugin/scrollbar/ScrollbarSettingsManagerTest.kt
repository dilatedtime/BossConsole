package ai.rever.boss.plugin.scrollbar

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogEntry
import ai.rever.boss.plugin.logging.LogListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScrollbarSettingsManagerTest {
    @TempDir
    lateinit var tempDir: File

    @AfterEach
    fun tearDown() {
        ScrollbarSettingsManager.resetForTesting()
    }

    @Test
    fun `loads and creates default settings when settings file is absent`() {
        val file = File(tempDir, "scrollbar-settings.json")
        ScrollbarSettingsManager.reloadForTesting(file)

        val settings = ScrollbarSettingsManager.currentSettings.value
        assertEquals(ScrollbarSettingsManager.getDefaultSettings(), settings)
        assertTrue(file.isFile, "Default settings file should be created on initialization")
    }

    @Test
    fun `persists updated settings to primary file and backup file`() {
        val file = File(tempDir, "scrollbar-settings.json")
        val backup = File(tempDir, "scrollbar-settings.json.bak")
        ScrollbarSettingsManager.reloadForTesting(file)

        val custom =
            ScrollbarSettings(
                panelThickness = 12,
                barThickness = 4,
                alwaysShowScrollbars = true,
                fadeDelayMs = 2500,
                fadeDurationMs = 600,
            )
        runBlocking {
            ScrollbarSettingsManager.updateSettings(custom)
        }

        assertEquals(12, ScrollbarSettingsManager.currentSettings.value.panelThickness)
        assertEquals(4, ScrollbarSettingsManager.currentSettings.value.barThickness)
        assertTrue(ScrollbarSettingsManager.currentSettings.value.alwaysShowScrollbars)
        assertTrue(file.isFile, "Primary settings file should exist after update")
        assertTrue(backup.isFile, "Backup settings file should exist after update")
        assertTrue(file.readText().contains("2500"), "Primary file must contain updated fadeDelayMs")
        assertTrue(backup.readText().contains("2500"), "Backup file must contain updated fadeDelayMs")
    }

    @Test
    fun `restores from backup file when primary settings file is corrupted`() {
        val file = File(tempDir, "scrollbar-settings.json")
        val backup = File(tempDir, "scrollbar-settings.json.bak")

        val validContent =
            """
            {
              "panelThickness": 10,
              "barThickness": 5,
              "alwaysShowScrollbars": true,
              "fadeDelayMs": 3000,
              "fadeDurationMs": 700
            }
            """.trimIndent()
        backup.writeText(validContent)
        file.writeText("{ corrupted_raw_json_syntax: [")

        ScrollbarSettingsManager.reloadForTesting(file)

        val loaded = ScrollbarSettingsManager.currentSettings.value
        assertEquals(10, loaded.panelThickness)
        assertEquals(5, loaded.barThickness)
        assertTrue(loaded.alwaysShowScrollbars)
        assertEquals(3000, loaded.fadeDelayMs)
    }

    @Test
    fun `redacts decode failures and prevents secret leakage into logger when no backup exists`() {
        val file = File(tempDir, "scrollbar-settings.json")
        val secretCanary = "secret-token-canary-987654"
        val tornContent = """{"panelThickness": 10, "secretKey": "$secretCanary", "barThickness": """
        file.writeText(tornContent)

        val captured = mutableListOf<LogEntry>()
        val listener = LogListener { captured += it }
        BossLogger.addListener(listener)

        try {
            ScrollbarSettingsManager.reloadForTesting(file)
        } finally {
            BossLogger.removeListener(listener)
        }

        val settings = ScrollbarSettingsManager.currentSettings.value
        assertEquals(ScrollbarSettingsManager.getDefaultSettings(), settings)

        val decodeLogs =
            captured.filter {
                it.component == "ScrollbarSettingsManager" && it.message == "Failed to load settings"
            }
        assertTrue(decodeLogs.isNotEmpty(), "Decode failure must be logged")
        for (log in decodeLogs) {
            val failure = log.data?.get("decodeFailure") as? String
            assertNotNull(failure, "decodeFailure field must be present in log data")
            assertFalse(
                log.message.contains(secretCanary),
                "Log message must not contain secret canary",
            )
            val dataStr = log.data.toString()
            assertFalse(
                dataStr.contains(secretCanary),
                "Log data must not contain secret canary",
            )
            log.error?.let { err ->
                assertFalse(
                    err.message.orEmpty().contains(secretCanary),
                    "Log error must not leak secret canary",
                )
            }
        }
    }

    @Test
    fun `falls back to defaults when both primary and backup files are corrupted`() {
        val file = File(tempDir, "scrollbar-settings.json")
        val backup = File(tempDir, "scrollbar-settings.json.bak")
        file.writeText("{ malformed json 1")
        backup.writeText("{ malformed json 2")

        ScrollbarSettingsManager.reloadForTesting(file)

        val settings = ScrollbarSettingsManager.currentSettings.value
        assertEquals(ScrollbarSettingsManager.getDefaultSettings(), settings)
    }

    @Test
    fun `handles concurrent updates safely without corrupting persistence`() {
        val file = File(tempDir, "scrollbar-settings.json")
        ScrollbarSettingsManager.reloadForTesting(file)

        runBlocking(Dispatchers.IO) {
            val jobs =
                (1..10).map { i ->
                    async {
                        val thickness = 6 + i
                        ScrollbarSettingsManager.updateSettings(
                            ScrollbarSettings(panelThickness = thickness),
                        )
                    }
                }
            jobs.awaitAll()
        }

        ScrollbarSettingsManager.reloadForTesting(file)
        val finalThickness = ScrollbarSettingsManager.currentSettings.value.panelThickness
        assertTrue(finalThickness in 7..16, "Final thickness must be a valid updated value")
    }
}
