package dev.ide.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.components.NewFileTarget
import dev.ide.ui.components.newFileTargetOf
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.languageFor
import dev.ide.ui.platform.isMobilePlatform

/**
 * Top-level screens, ordered by depth so the transition helper can infer direction: a move to a
 * higher-ordinal screen animates "forward" (deeper), a lower one "back".
 */
enum class Screen { Projects, CreateProject, Editor, Dependencies, ModuleConfig, SdkManager }

/**
 * Top-level editor destinations in the side rail / bottom nav. Per Apple's HIG these are peer
 * *destinations* (not actions): building/running is the Run button + the console sheet, not a tab.
 */
enum class RailDestination { Files, Search, Source, More }

/** Editor surface for a tab: the plain text editor, or the projectional block editor over the same AST. */
enum class EditorViewMode { Text, Blocks, Preview }

/**
 * One open editor tab. Its buffer-of-record is the [EditorSession] (the rope-backed model both the text
 * and block editors edit in place) — there is **no** mirrored `TextFieldValue`, so a keystroke never
 * materializes the document `String`. The host observes the session's snapshot state ([EditorSession.textRevision],
 * selection) and pulls [text] lazily, only in debounced effects (analyze/breadcrumb/project/save).
 */
class OpenFile(val path: String, val name: String, initial: String) {
    val session = EditorSession(initial, languageFor(name))
    var modified by mutableStateOf(false)
        private set
    /** Which surface this tab shows — text, blocks, or resource preview (text/blocks edit the one [session]).
     *  Image resources open straight into Preview (their bytes aren't editable text). */
    var viewMode by mutableStateOf(
        if (dev.ide.ui.editor.preview.previewKindOf(path) == dev.ide.ui.editor.preview.PreviewKind.BITMAP)
            EditorViewMode.Preview else EditorViewMode.Text,
    )
    /** The content this tab was opened/last-saved with — [modified] tracks divergence from it. */
    var savedText: String = initial
        private set

    init {
        // The session owns the buffer and its diagnostics (it shifts them on every edit). The tab only needs
        // the edit signal for save-state: mark dirty in O(1); the precise revert-to-saved check is deferred.
        session.onTextEdit = { modified = true }
    }

    /** The current buffer text, materialized lazily from the rope. Debounce reads — don't call per keystroke. */
    val text: String get() = session.doc.text

    /**
     * Precise dirty recompute against the saved baseline — catches a revert-to-saved. Off the hot path (call
     * on the analysis debounce): the O(1) length check short-circuits the O(n) content compare in the common
     * "lengths differ" case, and reuses the text the debounced analysis already materialized.
     */
    fun recomputeDirty() {
        modified = session.doc.length != savedText.length || text != savedText
    }

    /** Rebase the saved baseline after a successful save. */
    fun onSaved(text: String) { savedText = text; modified = false }
}

/**
 * App-wide UI state, hoisted so it survives screen switches. Holds the workspace tree, the open tabs,
 * and the overlay/pane toggles. Editor text lives per [OpenFile]; edits are pushed to the backend's
 * document overlay so cross-file analysis stays live.
 */
class IdeUiState(val backend: IdeBackend) {
    var tree: TreeNode by mutableStateOf(backend.fileTree())
        private set

    val openFiles = mutableStateListOf<OpenFile>()
    var activeIndex by mutableStateOf(-1)

    var rail by mutableStateOf(RailDestination.Files)
    // On mobile the tree + console are space-consuming sheets — start them closed; on desktop they're
    // persistent panes, so keep them open by default.
    var navOpen by mutableStateOf(!isMobilePlatform)
    var searchOpen by mutableStateOf(false)
    var consoleOpen by mutableStateOf(!isMobilePlatform)
    var paletteOpen by mutableStateOf(false)
    /** Editor inlay hints (inferred types, parameter names) — on by default; toggled from the editor top bar. */
    var inlayHintsEnabled by mutableStateOf(true)

    /** A transient destination shown as a sheet/overlay (Source, More) — null when none is open. */
    var sheetDest by mutableStateOf<RailDestination?>(null)

    /** Route to a rail destination: Files/Search toggle their side panes; Source/More open as a sheet. */
    fun selectRail(dest: RailDestination) {
        rail = dest
        when (dest) {
            RailDestination.Files -> { navOpen = !navOpen; searchOpen = false; sheetDest = null }
            RailDestination.Search -> { searchOpen = !searchOpen; navOpen = false; sheetDest = null }
            RailDestination.Source, RailDestination.More -> { sheetDest = dest }
        }
    }

    val active: OpenFile? get() = openFiles.getOrNull(activeIndex)

    fun open(path: String, name: String) {
        val existing = openFiles.indexOfFirst { it.path == path }
        if (existing >= 0) {
            activeIndex = existing
            return
        }
        val text = backend.readFile(path)
        backend.updateDocument(path, text)
        openFiles.add(OpenFile(path, name, text))
        activeIndex = openFiles.lastIndex
    }

    /** Open [path] and move the caret to [offset] (go-to-symbol). */
    fun openAt(path: String, offset: Int) {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        open(path, name)
        active?.session?.setCaret(offset) // setCaret coerces into the buffer
    }

    fun close(file: OpenFile) {
        val idx = openFiles.indexOf(file)
        if (idx < 0) return
        openFiles.removeAt(idx)
        activeIndex = activeIndex.coerceAtMost(openFiles.lastIndex)
    }

    /** Persist [file]'s buffer to disk, rebase its saved baseline, and clear the dirty flag. No-op if clean. */
    fun save(file: OpenFile) {
        if (!file.modified) return
        val text = file.text // one lazy materialization, on save (not per keystroke)
        backend.saveFile(file.path, text)
        file.onSaved(text)
    }

    /** Save the active tab (Cmd/Ctrl-S, toolbar). */
    fun saveActive() { active?.let(::save) }

    /** Pick a sensible first file: a `Main.java`, else the first source file in the tree. */
    fun defaultFile(): TreeNode? {
        val all = ArrayList<TreeNode>()
        fun walk(n: TreeNode) { if (n.filePath != null) all.add(n); n.children.forEach(::walk) }
        walk(tree)
        return all.firstOrNull { it.name == "Main.java" }
            ?: all.firstOrNull { it.name.endsWith(".java") }
            ?: all.firstOrNull()
    }

    /** Re-read the workspace tree from the backend (after a file is created/removed). */
    fun refreshTree() { tree = backend.fileTree() }

    /**
     * Reflect a completed project-wide rename in the open tabs. The rename wrote every reference site to
     * disk, so each clean tab is re-read (a no-op for files it didn't touch — those keep their session, undo
     * and caret). The active tab follows the backing-file rename to [newPath] when the file itself was
     * renamed. Tabs with unsaved edits are left untouched so a rename never clobbers in-progress work.
     */
    fun reloadAfterRename(activePath: String?, newPath: String?) {
        for (i in openFiles.indices) {
            val f = openFiles[i]
            val followsFileRename = newPath != null && f.path == activePath
            if (!followsFileRename && f.modified) continue
            val diskPath = if (followsFileRename) newPath!! else f.path
            val text = runCatching { backend.readFile(diskPath) }.getOrNull() ?: continue
            if (!followsFileRename && text == f.savedText) continue // untouched → preserve session/undo/caret
            val name = diskPath.substringAfterLast('/').substringAfterLast('\\')
            openFiles[i] = OpenFile(diskPath, name, text)
            backend.updateDocument(diskPath, text)
        }
        refreshTree()
    }

    /** Create a new file through the backend, refresh the tree, and open it in the editor. */
    fun createFile(dirPath: String, fileName: String, content: String) {
        val path = backend.createFile(dirPath, fileName, content) ?: return
        refreshTree()
        open(path, fileName)
    }

    /** Create a new directory through the backend and refresh the tree (nothing to open). */
    fun createDirectory(parentPath: String, name: String) {
        if (backend.createDirectory(parentPath, name) != null) refreshTree()
    }

    /** A sensible default New-Class target: the first Java/Kotlin source root in the tree. */
    fun defaultNewFileTarget(): NewFileTarget? {
        var found: TreeNode? = null
        fun walk(n: TreeNode) {
            if (found == null && n.kind == NodeKind.SourceRoot && n.sourceRootPath != null) found = n
            n.children.forEach(::walk)
        }
        walk(tree)
        return found?.let(::newFileTargetOf)
    }
}
