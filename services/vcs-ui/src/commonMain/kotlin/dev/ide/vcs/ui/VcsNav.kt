package dev.ide.vcs.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
 * The arguments a version-control screen is opened with.
 *
 * Contributed screens are addressed by id alone, so there is no route to carry parameters on. Since exactly
 * one screen is visible at a time, the opener writes what the screen needs here and navigates; the screen
 * reads it on entry. Kept to this plugin, and small enough that the state cannot drift: each field is set
 * immediately before the navigation that consumes it.
 */
internal object VcsNav {
    /** The comparison the diff screen renders. */
    var diff: DiffTarget? by mutableStateOf(null)

    /** A file path to narrow the history screen to, or null for the whole repository. */
    var historyPath: String? by mutableStateOf(null)
}
