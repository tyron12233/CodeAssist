package dev.ide.ui

import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.TreeViewMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * File-tree expansion is remembered: [IdeUiState] seeds a sensible default on a project's first open, then
 * restores whatever the user left expanded (per project + view mode) via [dev.ide.ui.backend.FileService].
 * These exercise the seeding/restore/persistence-snapshot logic headlessly (no Compose UI).
 */
class TreeExpansionStateTest {

    /** A backend whose file tree + per-mode expansion store live in memory. */
    private class TreeBackend : StubBackend() {
        val saved = HashMap<TreeViewMode, List<String>>()
        override fun fileTree(mode: TreeViewMode): TreeNode = TreeNode(
            "workspace", "ws", NodeKind.Workspace, null,
            children = listOf(
                TreeNode(
                    "module:app", "app", NodeKind.Module, null,
                    children = listOf(
                        TreeNode(
                            "root:/app/src", "src/main/java", NodeKind.SourceRoot, null,
                            children = listOf(
                                TreeNode(
                                    "pkg:/app/src/com", "com", NodeKind.Package, null,
                                    children = listOf(
                                        TreeNode("file:/app/src/com/A.kt", "A.kt", NodeKind.File, "/app/src/com/A.kt"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        override fun expandedTreeState(mode: TreeViewMode): List<String>? = saved[mode]
        override fun saveExpandedTreeState(mode: TreeViewMode, ids: List<String>) { saved[mode] = ids }
    }

    @Test
    fun seedsDefaultsWhenNothingPersisted() {
        val state = IdeUiState(TreeBackend())
        runBlocking { state.ensureTreeLoaded() } // the tree builds off-thread now — await it before seeding defaults
        // Modules, source roots, and the workspace open by default; packages/files stay closed.
        assertTrue(state.treeExpanded["workspace"] == true)
        assertTrue(state.treeExpanded["module:app"] == true)
        assertTrue(state.treeExpanded["root:/app/src"] == true)
        assertFalse(state.treeExpanded["pkg:/app/src/com"] == true)
    }

    @Test
    fun restoresPersistedExpansionExactly() {
        val backend = TreeBackend().apply { saved[TreeViewMode.Project] = listOf("workspace", "pkg:/app/src/com") }
        val state = IdeUiState(backend)
        assertTrue(state.treeExpanded["pkg:/app/src/com"] == true, "a user-expanded package is restored")
        assertTrue(state.treeExpanded["workspace"] == true)
        // A default-expandable node NOT in the persisted set stays collapsed — persistence wins over defaults.
        assertFalse(state.treeExpanded["module:app"] == true, "persistence overrides the default expansion")
    }

    @Test
    fun collapsedAllStateIsRespected() {
        // Empty (not null) persisted state = "user collapsed everything"; defaults must NOT reappear.
        val backend = TreeBackend().apply { saved[TreeViewMode.Project] = emptyList() }
        val state = IdeUiState(backend)
        assertTrue(state.expandedTreeSnapshot().isEmpty(), "a collapsed-everything project reopens collapsed")
    }

    @Test
    fun snapshotReflectsToggles() {
        val state = IdeUiState(TreeBackend())
        state.treeExpanded["pkg:/app/src/com"] = true
        assertTrue("pkg:/app/src/com" in state.expandedTreeSnapshot())
        state.treeExpanded["module:app"] = false
        assertFalse("module:app" in state.expandedTreeSnapshot(), "a collapsed node drops out of the snapshot")
    }

    @Test
    fun switchingModeReloadsThatModesExpansion() {
        val backend = TreeBackend().apply {
            saved[TreeViewMode.Project] = listOf("module:app")
            saved[TreeViewMode.AllFiles] = listOf("workspace")
        }
        // Unconfined dispatchers make `selectTreeMode`'s off-thread tree reload run synchronously here.
        val state = IdeUiState(backend, mainDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)
        assertTrue(state.treeExpanded["module:app"] == true)
        assertFalse(state.treeExpanded["workspace"] == true)
        state.selectTreeMode(TreeViewMode.AllFiles)
        assertTrue(state.treeExpanded["workspace"] == true, "the All-Files mode restores its own set")
        assertFalse(state.treeExpanded["module:app"] == true, "the Project-mode set doesn't leak across modes")
    }
}
