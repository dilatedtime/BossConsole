package ai.rever.boss.components.plugin.providers

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [FileSystemDataProviderImpl]'s lifecycle and coroutine supervision contract.
 *
 * It must be a [DisposableProvider] whose [dispose] cancels its [ioScope], preventing leaks across
 * window lifecycles, and the scope must be backed by a [SupervisorJob] so a failure in one
 * background launch does not cancel the scope and disable subsequent operations (#1088).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileSystemDataProviderImplDisposeTest {
    @Test
    fun `dispose cancels the provider ioScope`() {
        val testDispatcher = UnconfinedTestDispatcher()
        val provider = FileSystemDataProviderImpl(dispatcher = testDispatcher)
        assertTrue(provider.ioScope.isActive, "ioScope must be active upon creation")

        provider.dispose()

        assertFalse(provider.ioScope.isActive, "dispose must cancel ioScope")
    }

    @Test
    fun `dispose is idempotent`() {
        val testDispatcher = UnconfinedTestDispatcher()
        val provider = FileSystemDataProviderImpl(dispatcher = testDispatcher)

        provider.dispose()
        provider.dispose()

        assertFalse(provider.ioScope.isActive, "ioScope must remain cancelled after repeated dispose calls")
    }

    @Test
    fun `a failed operation does not cancel the supervised scope so later operations still run`() {
        val testDispatcher = UnconfinedTestDispatcher()
        val provider = FileSystemDataProviderImpl(dispatcher = testDispatcher)

        var caught: Throwable? = null
        provider.ioScope.launch(CoroutineExceptionHandler { _, e -> caught = e }) {
            error("simulated background failure")
        }

        assertEquals("simulated background failure", caught?.message)
        assertTrue(provider.ioScope.isActive, "supervised scope must remain active after child failure")

        var laterRan = false
        provider.ioScope.launch {
            laterRan = true
        }

        assertTrue(laterRan, "subsequent launch on the supervised scope must execute successfully")

        provider.dispose()
        assertFalse(provider.ioScope.isActive, "dispose cancels the supervised scope")
    }

    @Test
    fun `openFile handles execution gracefully on the provider scope`() {
        runTest {
            val testDispatcher = UnconfinedTestDispatcher(testScheduler)
            val provider = FileSystemDataProviderImpl(dispatcher = testDispatcher)

            // openFile delegates to FileEventBus.openFile asynchronously on ioScope.
            // It must not throw even if the target path or window is invalid.
            provider.openFile(path = "/nonexistent/test/path.txt", windowId = "test-window")

            assertTrue(provider.ioScope.isActive, "scope must remain active after openFile")
            provider.dispose()
            assertFalse(provider.ioScope.isActive)
        }
    }
}
