package dev.ide.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.core.RangeEdit

/**
 * The editor's in-file find/replace state + behaviour, pulled out of [CodeEditor] so the surface composable
 * isn't carrying eight `mutableStateOf`s and three edit routines. It owns only its own UI state and drives the
 * [EditorSession] (select-to-scroll, apply replacements); the composable reads [matches]/[currentIndex] to
 * paint highlights and renders the [FindReplaceBar] against these properties.
 *
 * Created per file via [rememberFindReplaceController] (keyed on the path so it resets when the tab changes).
 */
@Stable
internal class FindReplaceController(private val session: EditorSession) {
    var open by mutableStateOf(false)
    var replaceMode by mutableStateOf(false)
    var query by mutableStateOf("")
    var replaceWith by mutableStateOf("")
    var options by mutableStateOf(FindOptions())
    var matches by mutableStateOf<List<Match>>(emptyList())
        private set
    var currentIndex by mutableIntStateOf(0)
        private set
    var regexError by mutableStateOf(false)
        private set

    /** Open the bar (Ctrl-F / Ctrl-R or the toolbar button), optionally seeding the query from a selection. */
    fun openBar(replace: Boolean, seed: String? = null) {
        seed?.let { query = it }
        replaceMode = replace
        open = true
    }

    /** Move to the [index]-th match (wrapping), selecting it so the session scrolls it into view. */
    fun goto(index: Int) {
        if (matches.isEmpty()) return
        val i = ((index % matches.size) + matches.size) % matches.size
        currentIndex = i
        val m = matches[i]
        session.setSelectionRange(m.start, m.end) // selects + scrolls the match into view
    }

    fun replaceCurrent() {
        val m = matches.getOrNull(currentIndex) ?: return
        session.applyEdits(
            listOf(RangeEdit(m.start, m.end, replaceWith, m.start + replaceWith.length)),
            TextRange(m.start + replaceWith.length),
        )
        // matches recompute on the textRevision bump; currentIndex then points at the following occurrence
    }

    fun replaceAll() {
        if (matches.isEmpty()) return
        val edits = matches.map { RangeEdit(it.start, it.end, replaceWith, it.start + replaceWith.length) }
        session.applyEdits(edits, TextRange(matches.first().start + replaceWith.length))
        currentIndex = 0
    }

    /**
     * Recompute matches for the current [query]/[options] against the live buffer, selecting the one nearest
     * the caret so it scrolls into view. Driven from a debounced effect in the composable; a closed bar or an
     * empty query clears everything.
     */
    fun recompute() {
        if (!open || query.isEmpty()) { matches = emptyList(); regexError = false; return }
        regexError = options.regex && runCatching { Regex(query) }.isFailure
        val found = findMatches(session.doc.text, query, options)
        matches = found
        if (found.isEmpty()) {
            currentIndex = 0
        } else {
            currentIndex = matchIndexFrom(found, session.selection.start).coerceIn(0, found.size - 1)
            session.setSelectionRange(found[currentIndex].start, found[currentIndex].end)
        }
    }
}

@Composable
internal fun rememberFindReplaceController(path: String, session: EditorSession): FindReplaceController =
    remember(path) { FindReplaceController(session) }
