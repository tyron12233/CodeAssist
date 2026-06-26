package dev.ide.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.TreeViewMode
import dev.ide.ui.backend.UiRenameResult
import dev.ide.ui.backend.UiSourceRootRole
import dev.ide.ui.backend.UiNewFileTemplate
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.languageFor
import dev.ide.ui.platform.isMobilePlatform

/**
 * Top-level screens, ordered by depth so the transition helper can infer direction: a move to a
 * higher-ordinal screen animates "forward" (deeper), a lower one "back".
 */
enum class Screen { Projects, CreateProject, Editor, Run, ModuleConfig, SdkManager }

/**
 * Top-level editor destinations in the side rail / bottom nav. Per Apple's HIG these are peer
 * *destinations* (not actions): building/running is the Run button + the console sheet, not a tab.
 */
enum class RailDestination { Files, Search, Source, More }

/** Editor surface for a tab: the plain text editor, the projectional block editor over the same AST, a
 *  full-pane preview, or [Split] — code and its preview together (so you can edit and watch it update,
 *  the one layout that works on a phone where the panes can't otherwise share the screen). */
enum class EditorViewMode { Text, Blocks, Preview, Split }

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
    /** Which `@Preview` composable the Compose preview should render — set when the user taps a preview
     *  gutter icon next to a specific function. Null falls back to the file's first `@Preview`. */
    var previewTarget by mutableStateOf<String?>(null)
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
class IdeUiState(val backend: IdeBackend, val composePreviewHost: ComposePreviewHost? = null) {
    /** Which shape the file tree takes — curated Project view vs the raw All-Files view (IntelliJ-style). */
    var treeMode by mutableStateOf(TreeViewMode.Project)
        private set
    var tree: TreeNode by mutableStateOf(backend.fileTree(treeMode))
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
    /** Editor text zoom (pinch / Ctrl-+ / Ctrl--), 1.0 = the theme's default code size. Shared across tabs. */
    var editorFontScale by mutableStateOf(1f)

    /** A transient destination shown as a sheet/overlay (Source, More) — null when none is open. */
    var sheetDest by mutableStateOf<RailDestination?>(null)

    /** Whether the Logs viewer sheet (editor & analysis logs, opened from the More menu) is showing. */
    var logsOpen by mutableStateOf(false)

    /** Whether the indexing-status detail dialog (opened by tapping the top-bar index chip) is showing. */
    var indexDetailOpen by mutableStateOf(false)

    /** The module whose Add-Source-Root dialog is open, or null when closed. */
    var addSourceRootModule by mutableStateOf<String?>(null)

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

    /** Open [path] and move the caret to 1-based [line]/[column] — the build console's jump-to-diagnostic. */
    fun openAtLine(path: String, line: Int, column: Int) {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        open(path, name)
        val session = active?.session ?: return
        val base = session.doc.lineStart((line - 1).coerceAtLeast(0))
        session.setCaret(base + (column - 1).coerceAtLeast(0)) // setCaret coerces into the buffer
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

    /**
     * Reopen the tabs persisted from a previous session with this project (paths in tab order + the active
     * tab). Files that no longer exist on disk are skipped. Returns true if at least one tab was restored, so
     * the caller can fall back to [defaultFile] when there was no remembered session.
     */
    fun restoreTabs(): Boolean {
        val saved = backend.openTabs()
        if (saved.paths.isEmpty()) return false
        for (path in saved.paths) {
            val name = path.substringAfterLast('/').substringAfterLast('\\')
            runCatching { open(path, name) } // a deleted file throws in readFile — skip it
        }
        if (openFiles.isEmpty()) return false
        activeIndex = saved.activeIndex.coerceIn(0, openFiles.lastIndex)
        return true
    }

    /** The current open tabs as a persistable snapshot (paths in tab order + the active index). */
    fun tabsSnapshot(): dev.ide.ui.backend.UiOpenTabs =
        dev.ide.ui.backend.UiOpenTabs(openFiles.map { it.path }, activeIndex)

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
    fun refreshTree() { tree = backend.fileTree(treeMode) }

    /** Switch the tree view mode (Project ↔ All Files) and rebuild the tree. */
    fun selectTreeMode(mode: TreeViewMode) {
        if (mode == treeMode) return
        treeMode = mode
        refreshTree()
    }

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

    // ---- file & package operations (delete / rename / move / copy) ----

    /** Delete a file or directory/package: close any open tabs under it, then refresh the tree. */
    fun deletePath(path: String) {
        if (!backend.deletePath(path)) return
        closeTabsUnder(path)
        refreshTree()
    }

    /** Rename a file/directory to [newName]; rebase open tabs onto the new path + refresh. Returns the result. */
    suspend fun renamePath(path: String, newName: String): UiRenameResult {
        val r = backend.renamePath(path, newName)
        if (r.success) { rebaseTabs(path, r.newPath ?: path); refreshCleanTabs(); refreshTree() }
        return r
    }

    /** Move a file/directory into [destDir]; rebase open tabs + refresh. Returns true on success. */
    fun movePath(path: String, destDir: String): Boolean {
        val newPath = backend.movePath(path, destDir) ?: return false
        rebaseTabs(path, newPath); refreshTree()
        return true
    }

    /** Copy a file/directory into [destDir]; refresh the tree. Returns true on success. */
    fun copyPath(path: String, destDir: String): Boolean {
        if (backend.copyPath(path, destDir) == null) return false
        refreshTree()
        return true
    }

    /** True if [p] is [root] or lives under it (matching on either path separator). */
    private fun underPath(p: String, root: String): Boolean =
        p == root || p.startsWith("$root/") || p.startsWith("$root\\")

    /** Close every open tab at [path] or under it (after a delete). */
    private fun closeTabsUnder(path: String) {
        openFiles.filter { underPath(it.path, path) }.forEach(::close)
    }

    /** Re-point open tabs at [oldPath] (or under it, for a directory) to [newPath], re-reading from disk. */
    private fun rebaseTabs(oldPath: String, newPath: String) {
        for (i in openFiles.indices) {
            val p = openFiles[i].path
            val rebased = when {
                p == oldPath -> newPath
                underPath(p, oldPath) -> newPath + p.substring(oldPath.length)
                else -> continue
            }
            val text = runCatching { backend.readFile(rebased) }.getOrNull() ?: continue
            val name = rebased.substringAfterLast('/').substringAfterLast('\\')
            openFiles[i] = OpenFile(rebased, name, text)
            backend.updateDocument(rebased, text)
        }
    }

    /** Re-read clean (unmodified) tabs whose disk content changed — e.g. references rewritten by a rename. */
    private fun refreshCleanTabs() {
        for (i in openFiles.indices) {
            val f = openFiles[i]
            if (f.modified) continue
            val text = runCatching { backend.readFile(f.path) }.getOrNull() ?: continue
            if (text == f.savedText) continue
            openFiles[i] = OpenFile(f.path, f.name, text)
            backend.updateDocument(f.path, text)
        }
    }

    /** Create a smart-scaffolded, nested-path-aware file under [dirPath], refresh the tree, and open it. */
    fun createFileSmart(dirPath: String, name: String) {
        val path = backend.createFileSmart(dirPath, name) ?: return
        refreshTree()
        open(path, name.substringAfterLast('/').substringAfterLast('\\'))
    }

    /** Create a typed source file ([template]) named [name] under [dirPath], refresh the tree, and open it. */
    fun createSourceFile(dirPath: String, name: String, template: UiNewFileTemplate) {
        val path = backend.createSourceFile(dirPath, name, template) ?: return
        refreshTree()
        open(path, path.substringAfterLast('/').substringAfterLast('\\'))
    }

    // ---- source-set / content-root management ----

    /** Source-set names declared on [moduleName] (for the Add-Source-Root selector). */
    fun moduleSourceSets(moduleName: String): List<String> = backend.moduleSourceSets(moduleName)

    /** Add a typed source root to [moduleName] and refresh the tree. Returns true on success. */
    fun addSourceRoot(moduleName: String, sourceSetName: String, dirName: String, role: UiSourceRootRole): Boolean {
        val created = backend.addSourceRoot(moduleName, sourceSetName, dirName, role) != null
        if (created) refreshTree()
        return created
    }

    /** Unmark a content root (model-only) and refresh the tree. Returns true on success. */
    fun removeSourceRoot(moduleName: String, sourceSetName: String, rootPath: String): Boolean {
        val ok = backend.removeSourceRoot(moduleName, sourceSetName, rootPath)
        if (ok) refreshTree()
        return ok
    }

    /** Create an empty source set on [moduleName] and refresh the tree. Returns true on success. */
    fun addSourceSet(moduleName: String, name: String): Boolean {
        val ok = backend.addSourceSet(moduleName, name)
        if (ok) refreshTree()
        return ok
    }
}
