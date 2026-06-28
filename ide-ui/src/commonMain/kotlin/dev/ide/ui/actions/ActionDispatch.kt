package dev.ide.ui.actions

import dev.ide.ui.IdeUiState
import dev.ide.ui.backend.UiActionContext
import dev.ide.ui.backend.UiActionEffect

/**
 * Run a registry action by id and apply the [UiActionEffect]s it returns. The single place the UI action
 * seams (toolbar, file-tree context menu, command palette) route an invocation through, so a contributed
 * action's "open this file" / "refresh the tree" behaves identically wherever it was invoked from.
 *
 * `Navigate` is honored once the screen / tool-window registry lands (Phase B); until then it is ignored.
 */
suspend fun IdeUiState.dispatchAction(id: String, ctx: UiActionContext) {
    val result = runCatching { backend.actions.invokeAction(id, ctx) }.getOrNull() ?: return
    for (effect in result.effects) when (effect) {
        is UiActionEffect.OpenFile -> openAt(effect.path, effect.offset ?: 0)
        is UiActionEffect.ReloadFile -> refreshTree()
        UiActionEffect.RefreshTree -> refreshTree()
        is UiActionEffect.Navigate -> {} // screen / tool-window registry (Phase B)
    }
}
