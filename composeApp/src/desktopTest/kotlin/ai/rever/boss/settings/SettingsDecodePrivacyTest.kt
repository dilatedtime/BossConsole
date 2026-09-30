package ai.rever.boss.settings

import ai.rever.boss.components.sidebar.SidebarVisibilitySettingsManager
import ai.rever.boss.config.AutoPipSettingsManager
import ai.rever.boss.config.BrowserEngineSettingsManager
import ai.rever.boss.config.ChromiumFlagsSettingsManager
import ai.rever.boss.config.SwipeNavSettingsManager
import ai.rever.boss.filetypes.DefaultAppsSettingsManager
import ai.rever.boss.html.HtmlFileSettingsStore
import ai.rever.boss.mcp.secrets.captureHostLogs
import ai.rever.boss.performance.PerformanceSettingsManager
import ai.rever.boss.plugin.PluginPersistence
import ai.rever.boss.plugin.PluginStateManager
import ai.rever.boss.plugin.PluginStoreSetup
import ai.rever.boss.plugin.SystemPluginManifestService
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.run.RunnerSettingsManager
import ai.rever.boss.startup.StartupSettingsManager
import ai.rever.boss.terminal.TerminalLinkSettingsManager
import ai.rever.boss.theme.AppThemeSettingsManager
import ai.rever.boss.updater.UpdateSettingsManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogEntry
import ai.rever.boss.utils.logging.decodeFailure
import ai.rever.boss.window.WindowAppearanceSettingsManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies that torn or corrupt JSON settings files log structured decodeFailure metadata
 * and never leak raw file contents or secrets into log messages (Issue #1711).
 */
class SettingsDecodePrivacyTest {
    @TempDir
    lateinit var tempDir: File

    private val filesToCleanup = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        for (file in filesToCleanup) {
            file.delete()
        }
        filesToCleanup.clear()
    }

    private fun track(file: File): File {
        filesToCleanup.add(file)
        return file
    }

    private fun assertDecodeFailureRedacted(
        logged: List<LogEntry>,
        expectedMessage: String,
        secret: String,
    ) {
        val failures = logged.filter { it.message == expectedMessage }
        assertTrue(failures.isNotEmpty(), "expected log message '$expectedMessage' was not emitted in: $logged")
        for (failure in failures) {
            assertNull(failure.error, "the decoder exception includes file content and must not be attached")
            assertEquals(
                "JsonDecodingException",
                failure.data?.get("decodeFailure"),
                "decodeFailure metadata must record the exception class",
            )
        }
        for (entry in logged) {
            val fullEntry = "${entry.message} ${entry.data} ${entry.error}"
            assertFalse(secret in fullEntry, "secret leaked in log entry: $fullEntry")
        }
    }

    @Test
    fun `sidebar visibility settings decode failure is redacted`() {
        val secret = "leak-secret-sidebar-${System.nanoTime()}"
        val file = track(BossDirectories.resolve("sidebar-visibility-settings.json"))
        file.parentFile?.mkdirs()
        file.writeText("{\"hiddenPanelIds\": [\"panel-$secret")

        val (_, logged) =
            captureHostLogs {
                SidebarVisibilitySettingsManager.reloadForTest()
            }

        assertDecodeFailureRedacted(logged, "Failed to load sidebar visibility, using defaults", secret)
    }

    @Test
    fun `startup settings decode failure is redacted`() =
        runBlocking {
            val secret = "leak-secret-startup-${System.nanoTime()}"
            val file = track(BossDirectories.resolve("startup-settings.json"))
            file.parentFile?.mkdirs()
            file.writeText("{\"projectPath\": \"/secret/$secret")

            val (_, logged) =
                captureHostLogs {
                    runBlocking { StartupSettingsManager.loadSettings() }
                }

            assertDecodeFailureRedacted(logged, "Error loading settings", secret)
        }

    @Test
    fun `terminal link settings decode failure is redacted`() =
        runBlocking {
            val secret = "leak-secret-terminal-${System.nanoTime()}"
            val file = track(BossDirectories.resolve("terminal-link-settings.json"))
            file.parentFile?.mkdirs()
            file.writeText("{\"patterns\": [\"pattern-$secret")

            val (_, logged) =
                captureHostLogs {
                    runBlocking { TerminalLinkSettingsManager.reloadForTest() }
                }

            assertDecodeFailureRedacted(logged, "Error loading settings", secret)
        }

    @Test
    fun `runner settings decode failure is redacted`() =
        runBlocking {
            val secret = "leak-secret-runner-${System.nanoTime()}"
            val file = track(BossDirectories.resolve("runner-settings.json"))
            file.parentFile?.mkdirs()
            file.writeText("{\"customArgs\": \"--token=$secret")

            val (_, logged) =
                captureHostLogs {
                    runBlocking { RunnerSettingsManager.reloadForTest() }
                }

            assertDecodeFailureRedacted(logged, "Error loading settings", secret)
        }

    @Test
    fun `window appearance settings decode failure is redacted`() {
        val secret = "leak-secret-window-${System.nanoTime()}"
        val file = track(BossDirectories.resolve("window-appearance-settings.json"))
        file.parentFile?.mkdirs()
        file.writeText("{\"theme\": \"secret-theme-$secret")

        val (_, logged) =
            captureHostLogs {
                WindowAppearanceSettingsManager.reloadForTest()
            }

        assertDecodeFailureRedacted(logged, "Failed to load settings", secret)
    }

    @Test
    fun `update settings decode failure is redacted`() {
        val secret = "leak-secret-update-${System.nanoTime()}"
        val file = track(BossDirectories.resolve("update-settings.json"))
        file.parentFile?.mkdirs()
        file.writeText("{\"channel\": \"beta-$secret")

        val (_, logged) =
            captureHostLogs {
                UpdateSettingsManager.reloadForTest()
            }

        assertDecodeFailureRedacted(logged, "Failed to load update settings", secret)
    }

    @Test
    fun `default apps settings decode failure is redacted`() =
        runBlocking {
            val secret = "leak-secret-default-apps-${System.nanoTime()}"
            val file = track(BossDirectories.resolve("default-apps.json"))
            file.parentFile?.mkdirs()
            file.writeText("{\"declinedCategories\": [\"$secret")

            val (_, logged) =
                captureHostLogs {
                    DefaultAppsSettingsManager.resetForTest()
                    runBlocking { DefaultAppsSettingsManager.ensureLoaded() }
                }

            assertDecodeFailureRedacted(logged, "Could not read default-apps settings", secret)
        }

    @Test
    fun `auto-pip settings decode failure is redacted`() {
        val secret = "leak-secret-autopip-${System.nanoTime()}"
        val file = track(BossDirectories.resolve("auto-pip.json"))
        file.parentFile?.mkdirs()
        file.writeText("{\"enabled\": \"invalid-$secret")

        val (_, logged) =
            captureHostLogs {
                AutoPipSettingsManager.reloadForTest()
            }

        assertDecodeFailureRedacted(
            logged,
            "Could not read auto Picture-in-Picture settings; using the default",
            secret,
        )
    }

    @Test
    fun `browser engine settings decode failure is redacted`() {
        val secret = "leak-secret-browser-engine-${System.nanoTime()}"
        val file = track(BossDirectories.resolve("browser-engine-settings.json"))
        file.parentFile?.mkdirs()
        file.writeText("{\"engine\": \"custom-$secret")

        val (_, logged) =
            captureHostLogs {
                BrowserEngineSettingsManager.reloadForTest()
            }

        assertDecodeFailureRedacted(logged, "Error loading browser engine settings, using defaults", secret)
    }

    @Test
    fun `chromium flags settings decode failure is redacted`() {
        val secret = "leak-secret-chromium-flags-${System.nanoTime()}"
        val file = track(BossDirectories.resolve("chromium-flags.json"))
        file.parentFile?.mkdirs()
        file.writeText("{\"extraSwitches\": \"--secret-flag=$secret")

        val (_, logged) =
            captureHostLogs {
                ChromiumFlagsSettingsManager.reloadForTest()
            }

        assertDecodeFailureRedacted(logged, "Error loading Chromium flag settings, using defaults", secret)
    }

    @Test
    fun `swipe nav settings decode failure is redacted`() {
        val secret = "leak-secret-swipe-nav-${System.nanoTime()}"
        val file = track(BossDirectories.resolve("swipe-nav.json"))
        file.parentFile?.mkdirs()
        file.writeText("{\"enabled\": \"broken-$secret")

        val (_, logged) =
            captureHostLogs {
                SwipeNavSettingsManager.reloadForTest()
            }

        assertDecodeFailureRedacted(logged, "Could not read swipe settings; using the default", secret)
    }

    @Test
    fun `performance settings decode failure is redacted`() {
        val secret = "leak-secret-performance-${System.nanoTime()}"
        val file = track(BossDirectories.resolve("performance-settings.json"))
        file.parentFile?.mkdirs()
        file.writeText("{\"renderMode\": \"test-$secret")

        val (_, logged) =
            captureHostLogs {
                PerformanceSettingsManager.reloadForTest()
            }

        assertDecodeFailureRedacted(logged, "Failed to load performance settings - using defaults", secret)
    }

    @Test
    fun `app theme settings decode failure is redacted`() {
        val secret = "leak-secret-app-theme-${System.nanoTime()}"
        val file = track(BossDirectories.resolve("app-theme-settings.json"))
        file.parentFile?.mkdirs()
        file.writeText("{\"appThemeId\": \"custom-$secret")

        val (_, logged) =
            captureHostLogs {
                AppThemeSettingsManager.reloadForTest()
            }

        assertDecodeFailureRedacted(logged, "Failed to load app theme settings, using default", secret)
    }

    @Test
    fun `html file settings store decode failure is redacted`() =
        runBlocking {
            val secret = "leak-secret-html-file-${System.nanoTime()}"
            val file = File(tempDir, "html-file-settings.json")
            file.writeText("{\"openMode\": \"corrupt-$secret")

            val logger = BossLogger.forComponent("HtmlFileSettingsManagerTest")
            val store =
                HtmlFileSettingsStore(file) { error ->
                    if (error is SerializationException) {
                        logger.warn(LogCategory.UI, "Unable to read or save HTML file settings", decodeFailure(error))
                    } else {
                        logger.warn(LogCategory.UI, "Unable to read or save HTML file settings", error = error)
                    }
                }

            val (_, logged) =
                captureHostLogs {
                    runBlocking { store.awaitSettings() }
                }

            assertDecodeFailureRedacted(logged, "Unable to read or save HTML file settings", secret)
        }

    @Test
    fun `plugin persistence decode failure is redacted`() {
        val secret = "leak-secret-plugin-persist-${System.nanoTime()}"
        val file = track(File(PluginStoreSetup.getPluginDir(), "installed.json"))
        file.parentFile?.mkdirs()
        file.writeText("{\"plugins\": [{\"pluginId\": \"plugin-$secret")

        val (_, logged) =
            captureHostLogs {
                PluginPersistence.resetForTest()
                PluginPersistence.getInstalledPlugins()
            }

        assertDecodeFailureRedacted(logged, "Failed to load installed plugins config", secret)
    }

    @Test
    fun `plugin state manager decode failure is redacted`() =
        runBlocking {
            val secret = "leak-secret-plugin-states-${System.nanoTime()}"
            val file = File(tempDir, "plugin-states.json")
            file.writeText("{\"version\": 1, \"plugins\": {\"plugin-$secret\": {")
            val manager = PluginStateManager(tempDir)

            val (_, logged) =
                captureHostLogs {
                    runBlocking { manager.loadStates() }
                }

            assertDecodeFailureRedacted(logged, "Failed to load plugin states", secret)
        }

    @Test
    fun `system plugin manifest service decode failure is redacted`() {
        val secret = "leak-secret-system-plugins-${System.nanoTime()}"
        val file = track(BossDirectories.resolve("system-plugins.json"))
        file.parentFile?.mkdirs()
        file.writeText("[{\"plugin_id\": \"plugin-$secret")

        val (_, logged) =
            captureHostLogs {
                SystemPluginManifestService.reloadForTest()
            }

        assertDecodeFailureRedacted(logged, "System-plugins cache unreadable; using built-in fallback", secret)
    }
}
