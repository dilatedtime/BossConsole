package ai.rever.boss.services

import ai.rever.boss.window.WindowManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UrlHandlerZeroWindowTest {
    private val originalResolver = URLHandlerService.windowResolver

    private fun closeAllWindows() {
        WindowManager.windows.toList().forEach { WindowManager.closeWindow(it.id) }
    }

    @BeforeEach
    fun setUp() {
        closeAllWindows()
        URLHandlerService.markAppReady()
    }

    @AfterEach
    fun tearDown() {
        URLHandlerService.windowResolver = originalResolver
        closeAllWindows()
    }

    @Test
    fun defaultWindowResolverCreatesWindowWhenNoneExist() {
        closeAllWindows()
        assertEquals(0, WindowManager.windowCount)

        val windowId = URLHandlerService.windowResolver()
        assertNotNull(windowId)
        assertEquals(1, WindowManager.windowCount)
        assertEquals(windowId, WindowManager.windows.first().id)
    }

    @Test
    fun defaultWindowResolverReusesExistingWindowWhenRegistered() {
        closeAllWindows()
        val created = WindowManager.createNewWindow()
        assertEquals(1, WindowManager.windowCount)

        val resolved = URLHandlerService.windowResolver()
        assertEquals(created.id, resolved)
        // No second window should have been created
        assertEquals(1, WindowManager.windowCount)
    }

    @Test
    fun handleUrlInvokesWindowResolverOnZeroWindow() {
        var resolverCalled = false
        val simulatedWindowId = "simulated-window-id"
        URLHandlerService.windowResolver = {
            resolverCalled = true
            simulatedWindowId
        }

        URLHandlerService.handleURL("https://example.com/test", requiresConfirmation = false)
        assertTrue(resolverCalled, "Window resolver must be invoked when handling URL")
    }

    @Test
    fun handleUrlGracefullyHandlesNullResolverResultWithoutThrowing() {
        URLHandlerService.windowResolver = { null }
        // Should log warning and return cleanly without throwing
        URLHandlerService.handleURL("https://example.com/test", requiresConfirmation = false)
    }
}
