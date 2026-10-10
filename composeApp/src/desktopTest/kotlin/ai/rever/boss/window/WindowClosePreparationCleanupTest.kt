package ai.rever.boss.window

import org.junit.jupiter.api.Test

class WindowClosePreparationCleanupTest {
    @Test
    fun prepareWindowForCloseExecutesSafelyForUnregisteredWindow() {
        // Should execute CleanupRunner steps safely and not throw any exception
        prepareWindowForClose("non-existent-window-id")
    }

    @Test
    fun prepareWindowForCloseExecutesSafelyWhenNoBrowsersOpen() {
        val window = WindowManager.createNewWindow()
        try {
            prepareWindowForClose(window.id)
        } finally {
            WindowManager.closeWindow(window.id)
        }
    }
}
