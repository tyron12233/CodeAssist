package dev.ide.ui.screens

import dev.ide.ui.IdeUiState
import dev.ide.ui.OpenFile
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.TreeNode

/** Breadcrumb fallback: module › the last few path segments, used when the caret isn't inside a declaration. */
internal fun breadcrumbFor(state: IdeUiState, file: OpenFile): List<String> {
    val module = state.backend.files.moduleNameForFile(file.path)
    val segs = file.path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
    val tail = segs.takeLast(3)
    return (listOfNotNull(module) + tail).distinct()
}

/** The first Java/Kotlin source root's directory in the tree (depth-first), or null if there is none. */
private fun firstSourceRootDir(root: TreeNode): String? {
    root.sourceRootPath?.let { return it }
    for (c in root.children) firstSourceRootDir(c)?.let { return it }
    return null
}

/**
 * Launch the host's file picker to import external file(s) into the first source root, then refresh the
 * tree and open the first imported file. No-op when there's no source root to target.
 */
internal fun doImport(state: IdeUiState, fileActions: FileActions) {
    val dir = firstSourceRootDir(state.tree) ?: state.tree.dirPath ?: return
    doImportInto(state, fileActions, dir)
}

/**
 * Launch the host's file picker to import external file(s) into [dir] (a specific project directory chosen
 * from a tree row's context menu), then refresh the tree and open the first imported file.
 */
internal fun doImportInto(state: IdeUiState, fileActions: FileActions, dir: String) {
    fileActions.importInto(dir) { paths ->
        state.refreshTree()
        paths.firstOrNull()?.let { p -> state.open(p, p.substringAfterLast('/').substringAfterLast('\\')) }
    }
}

/** All openable (file-backed) nodes in the tree, flattened — feeds the command palette's file list. */
internal fun openableFiles(root: TreeNode): List<TreeNode> {
    val out = ArrayList<TreeNode>()
    fun walk(n: TreeNode) {
        if (n.filePath != null) out.add(n)
        n.children.forEach(::walk)
    }
    walk(root)
    return out
}
