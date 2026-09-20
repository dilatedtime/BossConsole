package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.events.NavigationTargetBus
import ai.rever.boss.components.events.NavigationTargetIpcPayload
import ai.rever.boss.ipc.IpcEventBridge
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class NavigationTargetProviderTest {
    @BeforeTest
    fun clearNavigationState() {
        NavigationTargetProviderImpl.clearCache()
        NavigationTargetBus.ipcBridge = null
    }

    @AfterTest
    fun resetNavigationState() {
        NavigationTargetProviderImpl.clearCache()
        NavigationTargetBus.ipcBridge = null
    }

    @Test
    fun `clearing a consumed target prevents replay to a later editor`() =
        runBlocking {
            val targetPath = "/workspace/src/Consumed.kt"

            NavigationTargetBus.navigateTo(
                filePath = targetPath,
                line = 41,
                column = 7,
                sourceWindowId = "window-a",
            )

            val consumed =
                withTimeout(TIMEOUT_MS) {
                    NavigationTargetProviderImpl.targets.first { it.filePath == targetPath }
                }
            assertEquals(41, consumed.line)

            NavigationTargetProviderImpl.clearCache()

            assertNull(
                withTimeoutOrNull(NO_EVENT_TIMEOUT_MS) {
                    NavigationTargetProviderImpl.targets.first()
                },
                "clearCache must remove the target exposed to plugins, not only the host copy",
            )
        }

    @Test
    fun `provider and bus expose one replay owner`() {
        assertSame(
            NavigationTargetBus.targets,
            NavigationTargetProviderImpl.targets,
            "a relay flow can retain a stale target after the bus cache is cleared",
        )
    }

    @Test
    fun `unconsumed target replays to an editor that subscribes after navigation`() =
        runBlocking {
            NavigationTargetBus.navigateTo(
                filePath = "/workspace/src/LateEditor.kt",
                line = 12,
                column = 3,
                sourceWindowId = "window-late",
            )

            val replayed = withTimeout(TIMEOUT_MS) { NavigationTargetProviderImpl.targets.first() }

            assertEquals("/workspace/src/LateEditor.kt", replayed.filePath)
            assertEquals(12, replayed.line)
            assertEquals(3, replayed.column)
            assertEquals("window-late", replayed.sourceWindowId)
        }

    @Test
    fun `new target remains deliverable after an earlier target is cleared`() =
        runBlocking {
            NavigationTargetBus.navigateTo("/workspace/Old.kt", 2, 1, "window-a")
            assertEquals("/workspace/Old.kt", NavigationTargetProviderImpl.targets.first().filePath)
            NavigationTargetProviderImpl.clearCache()

            NavigationTargetBus.navigateTo("/workspace/New.kt", 9, 4, "window-b")

            val next = withTimeout(TIMEOUT_MS) { NavigationTargetProviderImpl.targets.first() }
            assertEquals("/workspace/New.kt", next.filePath)
            assertEquals("window-b", next.sourceWindowId)
        }

    @Test
    fun `active editor receives navigation bursts in emission order`() =
        runBlocking {
            val received =
                async(start = CoroutineStart.UNDISPATCHED) {
                    NavigationTargetProviderImpl.targets
                        .take(3)
                        .toList()
                }

            NavigationTargetBus.navigateTo("/workspace/First.kt", 1, 1, "window-a")
            NavigationTargetBus.navigateTo("/workspace/Second.kt", 2, 1, "window-a")
            NavigationTargetBus.navigateTo("/workspace/Third.kt", 3, 1, "window-a")

            assertContentEquals(
                listOf("/workspace/First.kt", "/workspace/Second.kt", "/workspace/Third.kt"),
                withTimeout(TIMEOUT_MS) { received.await() }.map { it.filePath },
            )
        }

    @Test
    fun `invalid target lines are neither replayed nor forwarded`() =
        runBlocking {
            val bridge = RecordingBridge()
            NavigationTargetBus.ipcBridge = bridge

            NavigationTargetBus.navigateTo("/workspace/Zero.kt", 0, 8, "window-a")
            NavigationTargetBus.navigateTo("/workspace/Negative.kt", -4, 8, "window-a")

            assertNull(
                withTimeoutOrNull(NO_EVENT_TIMEOUT_MS) { NavigationTargetProviderImpl.targets.first() },
                "non-positive lines must not leave a navigation target for a future editor",
            )
            assertNull(bridge.payload, "a rejected local target must not cross the IPC boundary")
        }

    @Test
    fun `local and IPC consumers receive equivalent structured targets`() =
        runBlocking {
            val bridge = RecordingBridge()
            NavigationTargetBus.ipcBridge = bridge

            NavigationTargetBus.navigateTo(
                filePath = "/workspace/src/Shared.kt",
                line = 73,
                column = 19,
                sourceWindowId = "window-shared",
            )

            val local = withTimeout(TIMEOUT_MS) { NavigationTargetProviderImpl.targets.first() }
            val forwarded = assertNotNull(bridge.payload) as NavigationTargetIpcPayload
            assertEquals(local.filePath, forwarded.filePath)
            assertEquals(local.line, forwarded.line)
            assertEquals(local.column, forwarded.column)
            assertEquals(local.sourceWindowId, forwarded.sourceWindowId)
            assertEquals("NavigationTargetEvent", bridge.eventType)
            assertEquals("window-shared", bridge.sourceWindowId)

            val json = Json.encodeToJsonElement(NavigationTargetIpcPayload.serializer(), forwarded).jsonObject
            assertEquals(
                "/workspace/src/Shared.kt",
                json
                    .getValue("filePath")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(
                73,
                json
                    .getValue("line")
                    .jsonPrimitive
                    .content
                    .toInt(),
            )
            assertEquals(
                19,
                json
                    .getValue("column")
                    .jsonPrimitive
                    .content
                    .toInt(),
            )
            assertEquals(
                "window-shared",
                json
                    .getValue("sourceWindowId")
                    .jsonPrimitive
                    .content,
            )
        }

    private class RecordingBridge : IpcEventBridge {
        var eventType: String? = null
        var payload: Any? = null
        var sourceWindowId: String? = null

        override suspend fun forward(
            eventType: String,
            payload: Any,
            sourceWindowId: String,
        ) {
            this.eventType = eventType
            this.payload = payload
            this.sourceWindowId = sourceWindowId
        }
    }

    private companion object {
        const val TIMEOUT_MS = 2_000L
        const val NO_EVENT_TIMEOUT_MS = 200L
    }
}
