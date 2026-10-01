package ai.rever.boss.components.workspaces

import ai.rever.boss.components.plugin.TabUpdateRegistry
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.TabConfig
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins panel ordering and tab integrity across nested split trees during layout restore (#1610).
 *
 * When a workspace layout contains nested splits on the left or top, recursion must preserve
 * screen layout order rather than inserting outer siblings between inner children.
 */
class WorkspaceApplierTreeRestoreTest {
    private class StubComponent(
        ctx: ComponentContext,
        override val config: TabInfo,
    ) : TabComponentWithUI,
        ComponentContext by ctx {
        override val tabTypeInfo: TabTypeInfo = CodeEditorTabType

        @Composable
        override fun Content() = Unit
    }

    private val tabRegistry =
        TabRegistry().apply {
            registerTabType(CodeEditorTabType) { config, ctx -> StubComponent(ctx, config) }
        }

    @AfterTest
    fun tearDown() {
        TabUpdateRegistry.clear()
    }

    private var savedPanelCounter = 0

    private fun editorTab(name: String) =
        TabConfig(
            type = "editor",
            title = name,
            filePath = "$PROJECT/$name",
        )

    private fun panel(
        vararg tabs: TabConfig,
        pinnedCount: Int = 0,
    ): SplitConfig.SinglePanel {
        val id = "saved-panel-${savedPanelCounter++}"
        return SplitConfig.SinglePanel(
            PanelConfig(id = id, tabs = tabs.toList(), pinnedCount = pinnedCount),
        )
    }

    /** Applies [layout] into a fresh split state and returns the state for inspection. */
    private fun appliedState(layout: SplitConfig): SplitViewState {
        val splitViewState = SplitViewState(tabRegistry, windowId = WINDOW_ID)
        runBlocking {
            applyWorkspace(
                workspace =
                    LayoutWorkspace(
                        id = "ws-tree",
                        name = "ws",
                        description = "",
                        layout = layout,
                        projectPath = PROJECT,
                    ),
                splitViewState = splitViewState,
                warmEngine = {},
            )
        }
        return splitViewState
    }

    /** Every panel's tab titles, in split-tree order (left/top before right/bottom). */
    private fun restoredTitles(layout: SplitConfig): List<List<String>> {
        val state = appliedState(layout)
        return state.getAllPanels().map { panel ->
            panel.tabsComponent.tabsState.value.tabs
                .map { it.title }
        }
    }

    @Test
    fun `a nested split on the left restores in saved order`() {
        val layout =
            SplitConfig.VerticalSplit(
                left =
                    SplitConfig.VerticalSplit(
                        left = panel(editorTab("A.kt")),
                        right = panel(editorTab("B.kt")),
                    ),
                right = panel(editorTab("C.kt")),
            )

        assertEquals(
            listOf(
                listOf("A.kt"),
                listOf("B.kt"),
                listOf("C.kt"),
            ),
            restoredTitles(layout),
        )
    }

    @Test
    fun `a nested split on the right restores in saved order`() {
        val layout =
            SplitConfig.VerticalSplit(
                left = panel(editorTab("A.kt")),
                right =
                    SplitConfig.VerticalSplit(
                        left = panel(editorTab("B.kt")),
                        right = panel(editorTab("C.kt")),
                    ),
            )

        assertEquals(
            listOf(
                listOf("A.kt"),
                listOf("B.kt"),
                listOf("C.kt"),
            ),
            restoredTitles(layout),
        )
    }

    @Test
    fun `a nested split on top restores in saved order`() {
        val layout =
            SplitConfig.HorizontalSplit(
                top =
                    SplitConfig.HorizontalSplit(
                        top = panel(editorTab("A.kt")),
                        bottom = panel(editorTab("B.kt")),
                    ),
                bottom = panel(editorTab("C.kt")),
            )

        assertEquals(
            listOf(
                listOf("A.kt"),
                listOf("B.kt"),
                listOf("C.kt"),
            ),
            restoredTitles(layout),
        )
    }

    @Test
    fun `a nested split on bottom restores in saved order`() {
        val layout =
            SplitConfig.HorizontalSplit(
                top = panel(editorTab("A.kt")),
                bottom =
                    SplitConfig.HorizontalSplit(
                        top = panel(editorTab("B.kt")),
                        bottom = panel(editorTab("C.kt")),
                    ),
            )

        assertEquals(
            listOf(
                listOf("A.kt"),
                listOf("B.kt"),
                listOf("C.kt"),
            ),
            restoredTitles(layout),
        )
    }

    @Test
    fun `deeply nested left split restores in saved order`() {
        val layout =
            SplitConfig.VerticalSplit(
                left =
                    SplitConfig.VerticalSplit(
                        left =
                            SplitConfig.VerticalSplit(
                                left = panel(editorTab("A.kt")),
                                right = panel(editorTab("B.kt")),
                            ),
                        right = panel(editorTab("C.kt")),
                    ),
                right = panel(editorTab("D.kt")),
            )

        assertEquals(
            listOf(
                listOf("A.kt"),
                listOf("B.kt"),
                listOf("C.kt"),
                listOf("D.kt"),
            ),
            restoredTitles(layout),
        )
    }

    @Test
    fun `nested splits on both left and right restore in saved order`() {
        val layout =
            SplitConfig.VerticalSplit(
                left =
                    SplitConfig.VerticalSplit(
                        left = panel(editorTab("A.kt")),
                        right = panel(editorTab("B.kt")),
                    ),
                right =
                    SplitConfig.VerticalSplit(
                        left = panel(editorTab("C.kt")),
                        right = panel(editorTab("D.kt")),
                    ),
            )

        assertEquals(
            listOf(
                listOf("A.kt"),
                listOf("B.kt"),
                listOf("C.kt"),
                listOf("D.kt"),
            ),
            restoredTitles(layout),
        )
    }

    @Test
    fun `mixed vertical and horizontal splits restore in saved order`() {
        val layout =
            SplitConfig.VerticalSplit(
                left =
                    SplitConfig.HorizontalSplit(
                        top = panel(editorTab("TopLeft.kt")),
                        bottom = panel(editorTab("BottomLeft.kt")),
                    ),
                right =
                    SplitConfig.HorizontalSplit(
                        top = panel(editorTab("TopRight.kt")),
                        bottom = panel(editorTab("BottomRight.kt")),
                    ),
            )

        assertEquals(
            listOf(
                listOf("TopLeft.kt"),
                listOf("BottomLeft.kt"),
                listOf("TopRight.kt"),
                listOf("BottomRight.kt"),
            ),
            restoredTitles(layout),
        )
    }

    @Test
    fun `multi-tab panels in nested left split preserve all tabs without dropping or duplicating`() {
        val layout =
            SplitConfig.VerticalSplit(
                left =
                    SplitConfig.VerticalSplit(
                        left = panel(editorTab("A1.kt"), editorTab("A2.kt")),
                        right = panel(editorTab("B1.kt"), editorTab("B2.kt")),
                    ),
                right = panel(editorTab("C1.kt"), editorTab("C2.kt")),
            )

        assertEquals(
            listOf(
                listOf("A1.kt", "A2.kt"),
                listOf("B1.kt", "B2.kt"),
                listOf("C1.kt", "C2.kt"),
            ),
            restoredTitles(layout),
        )
    }

    @Test
    fun `pinned counts on nested left split panels restore to correct panels`() {
        val layout =
            SplitConfig.VerticalSplit(
                left =
                    SplitConfig.VerticalSplit(
                        left = panel(editorTab("A1.kt"), editorTab("A2.kt"), pinnedCount = 1),
                        right = panel(editorTab("B1.kt"), editorTab("B2.kt"), pinnedCount = 2),
                    ),
                right = panel(editorTab("C1.kt"), editorTab("C2.kt"), pinnedCount = 1),
            )

        val panels = appliedState(layout).getAllPanels()
        assertEquals(3, panels.size)
        assertEquals(1, panels[0].tabsComponent.pinnedCount)
        assertEquals(2, panels[1].tabsComponent.pinnedCount)
        assertEquals(1, panels[2].tabsComponent.pinnedCount)
    }

    private companion object {
        const val PROJECT = "/tmp/tree-restore-proj"
        const val WINDOW_ID = "tree-restore-nest-test"
    }
}
