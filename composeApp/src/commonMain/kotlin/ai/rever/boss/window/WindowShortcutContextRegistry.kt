package ai.rever.boss.window

import ai.rever.boss.keymap.model.ShortcutContext

/**
 * Registry bridging the Compose UI layer's active panel/tab context to the desktop
 * shortcut interception infrastructure.
 *
 * When an active tab changes in the main window panel, [BossMainWindowPanel] updates
 * this registry with the corresponding [ShortcutContext]. [AWTKeyboardInterceptor]
 * listens to these updates to route window-level key bindings (such as BROWSER-context
 * Cmd+L for address bar focus) to the currently active component.
 */
object WindowShortcutContextRegistry {
    /**
     * Listener receiving notifications when a window's active shortcut context changes.
     */
    fun interface Listener {
        fun onContextChanged(
            windowId: String,
            context: ShortcutContext?,
        )
    }

    /**
     * One active context registration entry.
     */
    internal data class Entry(
        val windowId: String,
        val ownerId: String,
        val context: ShortcutContext,
        val sequence: Long,
    )

    private val lock = Any()
    private var sequencer = 0L
    private val entriesByWindow = mutableMapOf<String, Entry>()
    private val listeners = mutableListOf<Listener>()

    /**
     * Update the active shortcut context for the specified window and owner.
     *
     * @param windowId The window identifier.
     * @param ownerId The identifier of the component setting this context.
     * @param context The shortcut context of the currently active component.
     * @return A token that can be passed to [clearContext] to guarantee safe removal.
     */
    fun updateContext(
        windowId: String,
        ownerId: String,
        context: ShortcutContext,
    ): Any {
        val (entry, notifyList) =
            synchronized(lock) {
                val created =
                    Entry(
                        windowId = windowId,
                        ownerId = ownerId,
                        context = context,
                        sequence = ++sequencer,
                    )
                entriesByWindow[windowId] = created
                created to listeners.toList()
            }
        for (listener in notifyList) {
            listener.onContextChanged(windowId, context)
        }
        return entry
    }

    /**
     * Update the active shortcut context for the specified window (unowned overload).
     */
    fun updateContext(
        windowId: String,
        context: ShortcutContext,
    ): Any = updateContext(windowId, windowId, context)

    /**
     * Clear the active shortcut context for the specified window, but only if [token]
     * matches the current entry. This prevents an out-of-order disposal from clearing
     * a newly activated panel's context.
     *
     * @param windowId The window identifier.
     * @param token The token returned by [updateContext]. If null, clears unconditionally.
     */
    fun clearContext(
        windowId: String,
        token: Any? = null,
    ) {
        val (cleared, notifyList) =
            synchronized(lock) {
                val current = entriesByWindow[windowId]
                if (token != null && current != token) {
                    false to emptyList()
                } else {
                    entriesByWindow.remove(windowId)
                    true to listeners.toList()
                }
            }
        if (cleared) {
            for (listener in notifyList) {
                listener.onContextChanged(windowId, null)
            }
        }
    }

    /**
     * Get the active shortcut context for the specified window, if any.
     */
    fun getContext(windowId: String): ShortcutContext? =
        synchronized(lock) {
            entriesByWindow[windowId]?.context
        }

    /**
     * Register a listener for context changes across all windows.
     */
    fun addListener(listener: Listener) {
        synchronized(lock) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
    }

    /**
     * Unregister a previously registered listener.
     */
    fun removeListener(listener: Listener) {
        synchronized(lock) {
            listeners.remove(listener)
        }
    }

    /**
     * Clear all recorded contexts, keeping listeners intact unless [clearListeners] is true.
     * Primarily for test isolation.
     */
    internal fun clear(clearListeners: Boolean = false) {
        synchronized(lock) {
            entriesByWindow.clear()
            sequencer = 0L
            if (clearListeners) {
                listeners.clear()
            }
        }
    }
}
