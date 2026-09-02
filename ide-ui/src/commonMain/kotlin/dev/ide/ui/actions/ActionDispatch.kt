package dev.ide.ui.actions

import dev.ide.ui.IdeUiState
import dev.ide.ui.backend.UiActionContext
import dev.ide.ui.backend.UiActionEffect
import dev.ide.ui.backend.UiTextEdit

/**
 * Run a registry action by id and apply the [UiActionEffect]s it returns. The single place the UI action
 * seams (toolbar, file-tree context menu, command palette, the editor's action popup and overflow menu)
 * route an invocation through, so a contributed action's "open this file" / "rewrite this code" /
 * "refresh the tree" behaves identically wherever it was invoked from.
 *
 * [navigate] routes a `Navigate` effect to a contributed screen; callers inside the app pass the host's
 * navigator, and a caller with no navigation of its own leaves it at the default no-op.
 *
 * Effects are applied in the order the action listed them, which is what lets an action pair an edit with
 * a selection over the text it just wrote (the naming step of a refactor). An effect the current state
 * cannot honor (a caret move with no editor open, a create whose path is taken) is skipped rather than
 * aborting the rest.
 */
suspend fun IdeUiState.dispatchAction(
    id: String,
    ctx: UiActionContext,
    navigate: (String) -> Unit = {},
) {
    val result = runCatching { backend.actions.invokeAction(id, ctx) }.getOrNull() ?: return
    applyActionEffects(result.effects, navigate)
}

/** Apply [effects] to the running UI. Split out so the editor surfaces can reuse it for a result they
 *  already have in hand, without invoking the action a second time. */
suspend fun IdeUiState.applyActionEffects(
    effects: List<UiActionEffect>,
    navigate: (String) -> Unit = {},
) {
    for (effect in effects) when (effect) {
        is UiActionEffect.OpenFile -> openAt(effect.path, effect.offset ?: 0)
        is UiActionEffect.ReloadFile -> refreshTree()
        UiActionEffect.RefreshTree -> refreshTree()
        is UiActionEffect.Navigate -> navigate(effect.target)

        // Editing goes through the editor session, not the file on disk, so a plugin's rewrite joins the
        // same undo stack as typing and re-triggers analysis the ordinary way.
        is UiActionEffect.ApplyEdits -> applyEdits(effect.edits)
        is UiActionEffect.ApplyWorkspaceEdit -> applyWorkspaceEdits(effect.edits)
        is UiActionEffect.MoveCaret -> active?.session?.setCaret(effect.offset)
        is UiActionEffect.Select -> active?.session?.setSelectionRange(effect.start, effect.end)

        is UiActionEffect.CreateFile -> createFileFromEffect(effect)
        is UiActionEffect.RenameFile -> renameFileFromEffect(effect)
        is UiActionEffect.DeleteFile -> deletePath(effect.path)
    }
}

/**
 * Apply a multi-file edit. A file that is open is edited through its session (undoable, and the editor
 * shows it immediately); a file that is not is read, patched in memory, and saved.
 *
 * The active file is done last: editing another tab has to make it active to drive its session, so doing
 * the active one first would lose the caret the action may still want to place.
 */
private suspend fun IdeUiState.applyWorkspaceEdits(edits: Map<String, List<UiTextEdit>>) {
    val activePath = active?.path
    for ((path, fileEdits) in edits.entries.sortedBy { it.key == activePath }) {
        if (fileEdits.isEmpty()) continue
        val open = openFiles.firstOrNull { it.path == path }
        if (open != null) {
            // Suspending open: `openAt` launches, and applying to a tab that has not become active yet
            // would edit whichever buffer was active before.
            openSuspend(open.path, open.name)
            applyEdits(fileEdits)
        } else {
            val original = runCatching { backend.files.readFile(path) }.getOrNull() ?: continue
            backend.editor.saveFile(path, patch(original, fileEdits))
        }
    }
    refreshTree()
}

/** Apply [edits] to [text] off-buffer, back to front so an earlier edit cannot shift a later one. */
internal fun patch(text: String, edits: List<UiTextEdit>): String {
    val sb = StringBuilder(text)
    for (e in edits.sortedByDescending { it.start }) {
        val start = e.start.coerceIn(0, sb.length)
        val end = e.end.coerceIn(start, sb.length)
        sb.replace(start, end, e.newText)
    }
    return sb.toString()
}

private fun IdeUiState.createFileFromEffect(effect: UiActionEffect.CreateFile) {
    val dir = effect.path.substringBeforeLast('/', "").ifEmpty { return }
    val name = effect.path.substringAfterLast('/')
    if (name.isEmpty()) return
    createFile(dir, name, effect.text)
}

private suspend fun IdeUiState.renameFileFromEffect(effect: UiActionEffect.RenameFile) {
    val newName = effect.to.substringAfterLast('/')
    if (newName.isEmpty()) return
    renamePath(effect.from, newName)
}
