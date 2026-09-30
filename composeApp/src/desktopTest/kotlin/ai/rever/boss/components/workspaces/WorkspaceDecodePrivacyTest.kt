package ai.rever.boss.components.workspaces

import ai.rever.boss.app.subscribeWorkspaceLoadEvents
import ai.rever.boss.components.events.WorkspaceEventBus
import ai.rever.boss.components.events.WorkspaceLoadEvent
import ai.rever.boss.mcp.secrets.captureHostLogs
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.WorkspaceSerializer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Saved Spaces carry the most privacy-sensitive local-state mix: project paths, browser URLs and
 * terminal commands. kotlinx serialization includes the source document in decoder messages, so
 * every workspace decode boundary must publish structured diagnostics without attaching the raw
 * exception. These tests exercise the four distinct boundaries (including CLI/deep-link workspace load,
 * Issue #1711) rather than only [decodeFailure].
 */
class WorkspaceDecodePrivacyTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `a corrupt Space file is logged without its layout data`() =
        runBlocking {
            val secret = "private-space-url-${System.nanoTime()}"
            val fileName = "corrupt-space.json"
            File(dir, fileName).writeText("{\"name\":\"Space\",\"url\":\"https://$secret.example/")
            val fileManager = WorkspaceFileManager(dir.absolutePath)

            val (loaded, logged) = captureHostLogs { runBlocking { fileManager.loadWorkspace(fileName) } }

            assertNull(loaded)
            assertDecodeFailureRedacted(logged, "Failed to load workspace file", secret)
        }

    @Test
    fun `a corrupt Last Session set is logged without its saved tabs`() =
        runBlocking {
            val secret = "private-session-tab-${System.nanoTime()}"
            val fileManager = WorkspaceFileManager(dir.absolutePath)
            fileManager.writeDocumentBlocking(
                LAST_SESSION_SET_FILE,
                "{\"spaces\":[{\"workspaceId\":\"one\",\"tabUrl\":\"https://$secret.example/",
            )
            val manager = WorkspaceManager(fileManager)

            val (loaded, logged) = captureHostLogs { runBlocking { manager.loadLastSessionSet() } }

            assertNull(loaded)
            assertDecodeFailureRedacted(logged, "Last Session set could not be read", secret)
        }

    @Test
    fun `a rejected Space import is logged without caller JSON`() {
        val secret = "private-import-command-${System.nanoTime()}"
        val manager = WorkspaceManager(WorkspaceFileManager(dir.absolutePath))
        val document = "{\"name\":\"Imported\",\"command\":\"echo $secret"

        val (loaded, logged) = captureHostLogs { manager.importWorkspace(document) }

        assertNull(loaded)
        assertDecodeFailureRedacted(logged, "Failed to import workspace from JSON", secret)
    }

    @Test
    fun `a corrupt Space file loaded via CLI workspace event is logged without its layout data`() =
        runBlocking {
            val secret = "private-cli-space-secret-${System.nanoTime()}"
            val fileName = "corrupt-cli-space.json"
            val corruptFile = File(dir, fileName)
            corruptFile.writeText("{\"name\":\"CLISpace\",\"url\":\"https://$secret.example/")
            var loadedWorkspace: LayoutWorkspace? = null

            val (_, logged) =
                captureHostLogs {
                    runBlocking {
                        val subscription =
                            subscribeWorkspaceLoadEvents(
                                windowId = "window-cli-test",
                                onLoadSpace = { _, workspace -> loadedWorkspace = workspace },
                            )
                        WorkspaceEventBus.loadWorkspace(
                            workspacePath = corruptFile.absolutePath,
                            sourceWindowId = "window-cli-test",
                        )
                        delay(50)
                        subscription.cancel()
                    }
                }

            assertNull(loadedWorkspace, "corrupt Space must not be loaded")
            assertDecodeFailureRedacted(logged, "Workspace load from CLI failed", secret)
            val failure = logged.single { it.message == "Workspace load from CLI failed" }
            assertEquals(corruptFile.absolutePath, failure.data?.get("path"))
        }

    @Test
    fun `a corrupt Space file targeted at another window does not log or dispatch in this window`() =
        runBlocking {
            val secret = "private-other-window-secret-${System.nanoTime()}"
            val corruptFile = File(dir, "other-window.json")
            corruptFile.writeText("{\"name\":\"OtherSpace\",\"command\":\"echo $secret")
            var loadedWorkspace: LayoutWorkspace? = null

            val (_, logged) =
                captureHostLogs {
                    runBlocking {
                        val subscription =
                            subscribeWorkspaceLoadEvents(
                                windowId = "window-active",
                                onLoadSpace = { _, workspace -> loadedWorkspace = workspace },
                            )
                        WorkspaceEventBus.loadWorkspace(
                            workspacePath = corruptFile.absolutePath,
                            sourceWindowId = "window-other",
                        )
                        delay(50)
                        subscription.cancel()
                    }
                }

            assertNull(loadedWorkspace)
            assertTrue(
                logged.none { it.message == "Workspace load from CLI failed" },
                "events for window-other must not be processed by window-active",
            )
        }

    @Test
    fun `valid Space file loaded via CLI workspace event dispatches to onLoadSpace without errors`() =
        runBlocking {
            val validWorkspace =
                LayoutWorkspace(
                    id = "valid-space-id",
                    name = "ValidCLIWorkspace",
                    description = "Valid Space Description",
                    layout = SplitConfig.SinglePanel(PanelConfig("test-panel", emptyList())),
                )
            val validFile = File(dir, "valid-space.json")
            validFile.writeText(WorkspaceSerializer.serialize(validWorkspace))
            var loadedEvent: WorkspaceLoadEvent? = null
            var loadedSpace: LayoutWorkspace? = null

            val (_, logged) =
                captureHostLogs {
                    runBlocking {
                        val subscription =
                            subscribeWorkspaceLoadEvents(
                                windowId = "window-valid",
                                onLoadSpace = { event, workspace ->
                                    loadedEvent = event
                                    loadedSpace = workspace
                                },
                            )
                        WorkspaceEventBus.loadWorkspace(
                            workspacePath = validFile.absolutePath,
                            sourceWindowId = "window-valid",
                        )
                        delay(50)
                        subscription.cancel()
                    }
                }

            assertNotNull(loadedSpace)
            assertEquals("ValidCLIWorkspace", loadedSpace?.name)
            assertEquals(validFile.absolutePath, loadedEvent?.workspacePath)
            assertTrue(
                logged.none { it.message == "Workspace load from CLI failed" },
                "valid workspace load must not log failure",
            )
        }

    @Test
    fun `an unreadable Space path on CLI workspace event retains error throwable`() =
        runBlocking {
            val subDir = File(dir, "unreadable-dir-space")
            subDir.mkdir()

            val (_, logged) =
                captureHostLogs {
                    runBlocking {
                        val subscription =
                            subscribeWorkspaceLoadEvents(
                                windowId = "window-io-test",
                                onLoadSpace = { _, _ -> },
                            )
                        WorkspaceEventBus.loadWorkspace(
                            workspacePath = subDir.absolutePath,
                            sourceWindowId = "window-io-test",
                        )
                        delay(50)
                        subscription.cancel()
                    }
                }

            val failure = logged.single { it.message == "Workspace load from CLI failed" }
            assertNotNull(failure.error, "ordinary I/O exceptions must retain their throwable")
            assertEquals(subDir.absolutePath, failure.data?.get("path"))
            assertNull(failure.data?.get("decodeFailure"), "ordinary I/O must not be classified as decode failure")
        }

    private fun assertDecodeFailureRedacted(
        logged: List<ai.rever.boss.utils.logging.LogEntry>,
        message: String,
        secret: String,
    ) {
        val failure = logged.single { it.message == message }
        assertNull(failure.error, "the decoder exception includes workspace JSON and must not be attached")
        assertEquals("JsonDecodingException", failure.data?.get("decodeFailure"))
        for (entry in logged) {
            assertFalse(secret in "${entry.message} ${entry.data} ${entry.error}", "leaked in: $entry")
        }
    }
}
