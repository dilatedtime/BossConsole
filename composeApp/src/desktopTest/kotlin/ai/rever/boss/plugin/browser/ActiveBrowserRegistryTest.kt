package ai.rever.boss.plugin.browser

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActiveBrowserRegistryTest {
    @BeforeEach
    fun setUp() {
        ActiveBrowserRegistry.resetForTest()
    }

    @AfterEach
    fun tearDown() {
        ActiveBrowserRegistry.resetForTest()
    }

    private fun createMockHandle(
        id: String,
        isValid: () -> Boolean = { true },
    ): BrowserHandle =
        Proxy.newProxyInstance(
            BrowserHandle::class.java.classLoader,
            arrayOf(BrowserHandle::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getId" -> id
                "isValid" -> isValid()
                else -> null
            }
        } as BrowserHandle

    @Test
    fun `register and unregister with token updates StateFlows and lookup methods`() {
        val handle = createMockHandle("handle-1")
        val token =
            ActiveBrowserRegistry.register(
                handle = handle,
                windowId = "window-1",
                inMainPanel = true,
                panelActive = true,
            )

        assertTrue(token is ActiveBrowserRegistry.Entry)
        assertTrue(ActiveBrowserRegistry.windowsWithActiveBrowser.value.contains("window-1"))
        assertEquals("handle-1", ActiveBrowserRegistry.activeHandleIdByWindow.value["window-1"])
        assertEquals("handle-1", ActiveBrowserRegistry.activeIn("window-1")?.id)
        assertNotNull(ActiveBrowserRegistry.handleById("handle-1"))

        // Unregister with non-matching token must not remove entry
        ActiveBrowserRegistry.unregister("handle-1", "invalid-token")
        assertTrue(ActiveBrowserRegistry.windowsWithActiveBrowser.value.contains("window-1"))
        assertEquals("handle-1", ActiveBrowserRegistry.activeHandleIdByWindow.value["window-1"])

        // Unregister with matching token must remove entry
        ActiveBrowserRegistry.unregister("handle-1", token)
        assertFalse(ActiveBrowserRegistry.windowsWithActiveBrowser.value.contains("window-1"))
        assertNull(ActiveBrowserRegistry.activeHandleIdByWindow.value["window-1"])
        assertNull(ActiveBrowserRegistry.activeIn("window-1"))
        assertNull(ActiveBrowserRegistry.handleById("handle-1"))
    }

    @Test
    fun `unconditional unregister removes entry without requiring token`() {
        val handle = createMockHandle("handle-2")
        ActiveBrowserRegistry.register(
            handle = handle,
            windowId = "window-2",
            inMainPanel = true,
            panelActive = true,
        )

        assertEquals("handle-2", ActiveBrowserRegistry.activeHandleIdByWindow.value["window-2"])
        ActiveBrowserRegistry.unregister("handle-2")

        assertFalse(ActiveBrowserRegistry.windowsWithActiveBrowser.value.contains("window-2"))
        assertNull(ActiveBrowserRegistry.activeHandleIdByWindow.value["window-2"])
        assertNull(ActiveBrowserRegistry.handleById("handle-2"))
    }

    @Test
    fun `republish recomputes active windows when handle validity flips`() {
        val isLive = AtomicBoolean(true)
        val handle = createMockHandle("handle-3", isValid = { isLive.get() })

        ActiveBrowserRegistry.register(
            handle = handle,
            windowId = "window-3",
            inMainPanel = true,
            panelActive = true,
        )

        assertTrue(ActiveBrowserRegistry.windowsWithActiveBrowser.value.contains("window-3"))
        assertEquals("handle-3", ActiveBrowserRegistry.activeHandleIdByWindow.value["window-3"])

        // Simulate transport connection drop
        isLive.set(false)
        ActiveBrowserRegistry.republish()

        assertFalse(ActiveBrowserRegistry.windowsWithActiveBrowser.value.contains("window-3"))
        assertNull(ActiveBrowserRegistry.activeHandleIdByWindow.value["window-3"])
    }

    @Test
    fun `resetForTest cleanly restores initial empty state across all fields and flows`() {
        val handleA = createMockHandle("handle-a")
        val handleB = createMockHandle("handle-b")

        ActiveBrowserRegistry.register(handleA, "window-a", inMainPanel = true, panelActive = true)
        ActiveBrowserRegistry.register(handleB, "window-b", inMainPanel = true, panelActive = true)

        assertEquals(2, ActiveBrowserRegistry.windowsWithActiveBrowser.value.size)
        assertEquals(2, ActiveBrowserRegistry.activeHandleIdByWindow.value.size)

        ActiveBrowserRegistry.resetForTest()

        assertTrue(ActiveBrowserRegistry.windowsWithActiveBrowser.value.isEmpty())
        assertTrue(ActiveBrowserRegistry.activeHandleIdByWindow.value.isEmpty())
        assertNull(ActiveBrowserRegistry.activeIn("window-a"))
        assertNull(ActiveBrowserRegistry.activeIn("window-b"))
        assertNull(ActiveBrowserRegistry.handleById("handle-a"))
        assertNull(ActiveBrowserRegistry.handleById("handle-b"))
    }

    @Test
    fun `concurrent registration and unregistration preserves consistency`() {
        val threadCount = 8
        val iterationsPerThread = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        for (threadIndex in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until iterationsPerThread) {
                        val handleId = "handle-$threadIndex-$i"
                        val windowId = "window-$threadIndex"
                        val handle = createMockHandle(handleId)
                        val token =
                            ActiveBrowserRegistry.register(
                                handle = handle,
                                windowId = windowId,
                                inMainPanel = true,
                                panelActive = true,
                            )
                        ActiveBrowserRegistry.unregister(handleId, token)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()
        assertTrue(completed, "Concurrent operations timed out")

        ActiveBrowserRegistry.resetForTest()
        assertTrue(ActiveBrowserRegistry.windowsWithActiveBrowser.value.isEmpty())
        assertTrue(ActiveBrowserRegistry.activeHandleIdByWindow.value.isEmpty())
    }
}
