package ai.rever.boss.window

import ai.rever.boss.keymap.KeymapSettingsManager
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.ShortcutContext
import ai.rever.boss.keymap.presets.KeymapPresets
import ai.rever.boss.utils.SystemUtils
import kotlinx.coroutines.flow.MutableStateFlow
import java.awt.Canvas
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for [WindowShortcutContextRegistry] and context-aware keymap routing
 * in [AWTKeyboardInterceptor].
 *
 * Verifies Issue #1566:
 * 1. [WindowShortcutContextRegistry] accurately tracks and broadcasts active contexts per window.
 * 2. Token-based unregistration avoids race conditions during panel/tab transitions.
 * 3. With [ShortcutContext.BROWSER], Cmd+L matches [KeymapPresets.FLUCK_FOCUS_ADDRESS_BAR_ACTION].
 * 4. With [ShortcutContext.EDITOR], Cmd+L matches [KeymapActions.EDITOR_GO_TO_LINE].
 * 5. With [ShortcutContext.GLOBAL] or no registered context, neither binding matches.
 */
class WindowShortcutContextTest {
    private val testWindowId = "test-window-shortcut-context"
    private val canvas = Canvas()
    private lateinit var previousSettings: KeymapSettings
    private lateinit var settingsState: MutableStateFlow<KeymapSettings>

    @Suppress("DEPRECATION")
    private val primaryModifierMask =
        if (SystemUtils.isMacOS) {
            InputEvent.META_DOWN_MASK or InputEvent.META_MASK
        } else {
            InputEvent.CTRL_DOWN_MASK or InputEvent.CTRL_MASK
        }

    @BeforeTest
    fun setUp() {
        val field = KeymapSettingsManager::class.java.getDeclaredField("_currentSettings")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        settingsState = field.get(KeymapSettingsManager) as MutableStateFlow<KeymapSettings>
        previousSettings = settingsState.value

        WindowShortcutContextRegistry.clear(clearListeners = false)
        AWTKeyboardInterceptor.install()
        AWTKeyboardInterceptor.clearWindowContext(testWindowId)
    }

    @AfterTest
    fun tearDown() {
        WindowShortcutContextRegistry.clear(clearListeners = false)
        AWTKeyboardInterceptor.clearWindowContext(testWindowId)
        AWTKeyboardInterceptor.cancelPendingShortcut()
        settingsState.value = previousSettings
    }

    private fun createCmdLEvent(): KeyEvent =
        KeyEvent(
            canvas,
            KeyEvent.KEY_PRESSED,
            0,
            primaryModifierMask,
            KeyEvent.VK_L,
            'L',
        )

    @Test
    fun `registry notifies listeners and tracks per-window contexts`() {
        val observed = mutableListOf<Pair<String, ShortcutContext?>>()
        val listener =
            WindowShortcutContextRegistry.Listener { windowId, context ->
                observed.add(windowId to context)
            }
        WindowShortcutContextRegistry.addListener(listener)

        try {
            WindowShortcutContextRegistry.updateContext(
                testWindowId,
                "panel-1",
                ShortcutContext.BROWSER,
            )
            assertEquals<ShortcutContext?>(
                ShortcutContext.BROWSER,
                WindowShortcutContextRegistry.getContext(testWindowId),
            )
            assertEquals(listOf<Pair<String, ShortcutContext?>>(testWindowId to ShortcutContext.BROWSER), observed)

            WindowShortcutContextRegistry.updateContext(
                testWindowId,
                "panel-1",
                ShortcutContext.TERMINAL,
            )
            assertEquals<ShortcutContext?>(
                ShortcutContext.TERMINAL,
                WindowShortcutContextRegistry.getContext(testWindowId),
            )
            assertEquals(
                listOf<Pair<String, ShortcutContext?>>(
                    testWindowId to ShortcutContext.BROWSER,
                    testWindowId to ShortcutContext.TERMINAL,
                ),
                observed,
            )

            WindowShortcutContextRegistry.clearContext(testWindowId)
            assertNull(WindowShortcutContextRegistry.getContext(testWindowId))
            assertEquals(
                listOf<Pair<String, ShortcutContext?>>(
                    testWindowId to ShortcutContext.BROWSER,
                    testWindowId to ShortcutContext.TERMINAL,
                    testWindowId to null,
                ),
                observed,
            )
        } finally {
            WindowShortcutContextRegistry.removeListener(listener)
        }
    }

    @Test
    fun `token-based unregistration prevents stale panel disposals from clearing new context`() {
        val tokenA =
            WindowShortcutContextRegistry.updateContext(
                testWindowId,
                "panel-a",
                ShortcutContext.BROWSER,
            )
        val tokenB =
            WindowShortcutContextRegistry.updateContext(
                testWindowId,
                "panel-b",
                ShortcutContext.TERMINAL,
            )

        // Panel A disposes after Panel B became active: stale tokenA must not clear context
        WindowShortcutContextRegistry.clearContext(testWindowId, tokenA)
        assertEquals<ShortcutContext?>(
            ShortcutContext.TERMINAL,
            WindowShortcutContextRegistry.getContext(testWindowId),
        )

        // Panel B disposes: tokenB matches and clears context
        WindowShortcutContextRegistry.clearContext(testWindowId, tokenB)
        assertNull(WindowShortcutContextRegistry.getContext(testWindowId))
    }

    @Test
    fun `browser context matches Cmd+L to focus address bar action`() {
        settingsState.value = KeymapPresets.getBOSSDefault()
        WindowShortcutContextRegistry.updateContext(testWindowId, "browser-panel", ShortcutContext.BROWSER)

        val match = AWTKeyboardInterceptor.findMatchingBinding(createCmdLEvent(), testWindowId)
        assertNotNull(match, "Cmd+L must match when window context is BROWSER")
        assertEquals(
            KeymapPresets.FLUCK_FOCUS_ADDRESS_BAR_ACTION,
            match.binding.actionId,
            "Cmd+L in BROWSER context must resolve to FLUCK_FOCUS_ADDRESS_BAR_ACTION",
        )
    }

    @Test
    fun `editor context matches Cmd+L to editor go to line`() {
        settingsState.value = KeymapPresets.getBOSSDefault()
        WindowShortcutContextRegistry.updateContext(testWindowId, "editor-panel", ShortcutContext.EDITOR)

        val match = AWTKeyboardInterceptor.findMatchingBinding(createCmdLEvent(), testWindowId)
        assertNotNull(match, "Cmd+L must match when window context is EDITOR")
        assertEquals(
            KeymapActions.EDITOR_GO_TO_LINE,
            match.binding.actionId,
            "Cmd+L in EDITOR context must resolve to EDITOR_GO_TO_LINE",
        )
    }

    @Test
    fun `global or cleared context drops both component-specific Cmd+L bindings`() {
        settingsState.value = KeymapPresets.getBOSSDefault()
        WindowShortcutContextRegistry.clearContext(testWindowId)

        val match = AWTKeyboardInterceptor.findMatchingBinding(createCmdLEvent(), testWindowId)
        assertNull(match, "Cmd+L must not match when window context is GLOBAL / unassigned")
    }
}
