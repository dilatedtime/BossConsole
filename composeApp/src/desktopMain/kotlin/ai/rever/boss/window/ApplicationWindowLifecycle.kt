package ai.rever.boss.window

import ai.rever.boss.utils.WindowFocusManager

/** App-level window actions, including a Dock reopen after the last window closes. */
internal class ApplicationWindowLifecycle {
    fun openInitialWindow(startWithoutWindow: Boolean = false) {
        if (!startWithoutWindow && WindowManager.windows.isEmpty()) WindowManager.createNewWindow()
    }

    fun reopen() {
        if (WindowManager.windows.isEmpty()) {
            WindowManager.createNewWindow()
            return
        }
        val target =
            WindowManager.windows.firstOrNull { WindowFocusManager.getWindow(it.id)?.isFocused == true }
                ?: WindowManager.windows.last()
        WindowFocusManager.focusWindow(target.id)
    }
}

/**
 * Resolves an actionable window ID, falling back to any existing window in
 * [WindowManager], or creating a new window when zero windows are registered.
 */
internal fun resolveActionableWindowOrFallback(): String? {
    val existingId = WindowFocusManager.resolveActionableWindowId() ?: WindowManager.windows.firstOrNull()?.id
    return existingId ?: WindowManager.createNewWindow().id
}
