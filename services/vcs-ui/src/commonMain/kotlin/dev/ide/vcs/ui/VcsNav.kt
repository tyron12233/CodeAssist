package dev.ide.vcs.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * What the diff screen should show. Either a working-tree comparison ([staged] picks the side of the index)
 * or one commit against its first parent.
 */
internal data class DiffTarget(
    val path: String,
    val staged: Boolean = false,
    val commitId: String? = null,
    /** Shown in the screen's subtitle when the diff comes from history. */
    val commitLabel: String = "",
)

/**
 * State the Git screens keep outside any one composition. Screen arguments are not here: they travel as
 * `ScreenContext.argument` ([DiffTarget] for the diff screen, a path or null for history), which the host
 * restores on Back. A global used to carry them, and Back then showed whichever file was opened last.
 */
internal object VcsNav {
    private val commitDrafts = HashMap<String, MutableState<String>>()

    /** The unsent commit message for the working copy at [root], kept for the life of the process. */
    fun commitDraft(root: String): MutableState<String> = commitDrafts.getOrPut(root) { mutableStateOf("") }
}
