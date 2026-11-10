package dev.ide.ui.editor.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextRange
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiTextEdit
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.core.RangeEdit
import dev.ide.ui.ext.EditorPreviewContext
import dev.ide.ui.ext.EditorPreviewContribution

/**
 * Hosts a plugin-contributed preview pane ([EditorPreviewContribution]).
 *
 * The pane itself is the plugin's `@Composable`; what this adds is the chrome the built-in previews already
 * have, so a contributed preview does not have to reinvent it and does not look foreign: the live buffer, the
 * surface's light/dark state, and the shared problem chip that a plugin reports into.
 *
 * Problems are held here rather than by the plugin so the chip behaves the same as everywhere else: the
 * plugin reports on every pass, an empty report clears it, and nothing the plugin does can leave a stale
 * problem on screen.
 */
@Composable
fun PluginPreviewPane(
    preview: EditorPreviewContribution,
    path: String,
    text: String,
    backend: IdeBackend,
    /** The surface's scheme, so a rendered scene can match it. Passed in rather than read off the theme here,
     *  which keeps this pane composable outside the app's theme. */
    dark: Boolean,
    onOpenFile: (String, Int) -> Unit,
    onOpenScreen: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** The tab's editor session, which a pane that edits writes through; null renders the pane read-only. */
    session: EditorSession? = null,
    /** Whether the pane is the preview half of the Split view. */
    split: Boolean = false,
    /** Reports the pane's progress, for a caller rendering it off screen (see [PreviewPaneStatus]). */
    onStatus: ((PreviewPaneStatus) -> Unit)? = null,
) {
    var problems by remember(preview.id, path) { mutableStateOf(emptyList<String>()) }
    // A pane that never reports is settled once composed; one that renders asynchronously says so.
    var rendering by remember(preview.id, path) { mutableStateOf(false) }
    val ctx = rememberPreviewContext(
        id = preview.id,
        path = path,
        text = text,
        backend = backend,
        dark = dark,
        onOpenFile = onOpenFile,
        onOpenScreen = onOpenScreen,
        // Structural equality on the state means an unchanged report (the common case, including the empty
        // one) is not a change, so a body that reports on every pass does not loop the pane.
        onReport = { problems = it },
        session = session,
        split = split,
        onRendering = { rendering = it },
    )
    if (onStatus != null) {
        val report by rememberUpdatedState(onStatus)
        val status = PreviewPaneStatus(
            settled = !rendering,
            label = path.substringAfterLast('/'),
            problems = problems,
        )
        LaunchedEffect(status) { report(status) }
    }
    Box(modifier.then(rememberPreviewCapture(path, preview.title))) {
        // A plugin body that throws must not take the editor down with it. There is no Compose equivalent of a
        // try/catch around a composition, so this is a real limit rather than something handled here: the
        // plugin owns its own failures, and the problem chip is how it reports them.
        preview.content(ctx)
        PreviewProblemChip(
            problems.map { PreviewIssue(PreviewIssueLevel.ERROR, preview.title, it) },
            Modifier.align(Alignment.BottomStart).padding(8.dp),
        )
    }
}

/**
 * The context a contributed preview body renders against, remembered across the recompositions that do not
 * change what it answers.
 *
 * Its own function so it can be exercised without composing the pane's layout, which is where the wiring
 * worth checking lives: the body must see the live buffer, and its navigation must reach the host's handlers
 * rather than the members that back them.
 */
@Composable
internal fun rememberPreviewContext(
    id: String,
    path: String,
    text: String,
    backend: IdeBackend,
    dark: Boolean,
    onOpenFile: (String, Int) -> Unit,
    onOpenScreen: (String) -> Unit,
    onReport: (List<String>) -> Unit,
    session: EditorSession? = null,
    split: Boolean = false,
    onRendering: (Boolean) -> Unit = {},
): EditorPreviewContext {
    val hostBackend = backend
    val filePath = path
    val buffer = text
    val isDark = dark
    // Read in composition, so a pane that follows the caret recomposes when the code editor moves it.
    val selection = session?.selection
    val caret = selection?.min ?: 0
    val selEnd = selection?.max ?: caret
    return remember(id, filePath, buffer, isDark, hostBackend, session, split, caret, selEnd) {
        object : EditorPreviewContext {
            override val backend: IdeBackend = hostBackend
            override val path: String = filePath
            override val text: String = buffer
            override val dark: Boolean = isDark
            override val caretOffset: Int = caret
            override val selectionEnd: Int = selEnd
            override val isSplit: Boolean = split
            override val canEdit: Boolean = session != null
            override fun reportProblems(problems: List<String>) = onReport(problems)
            override fun reportRendering(rendering: Boolean) = onRendering(rendering)
            override fun openFile(path: String, offset: Int) = onOpenFile(path, offset)
            override fun openScreen(id: String) = onOpenScreen(id)

            override fun applyEdits(edits: List<UiTextEdit>) {
                val s = session ?: return
                if (edits.isEmpty()) return
                val length = s.doc.length
                val ranges = edits.map {
                    val from = it.start.coerceIn(0, length)
                    RangeEdit(from, it.end.coerceIn(from, length), it.newText, from + it.newText.length)
                }
                s.applyEdits(ranges, TextRange(ranges.minOf { it.start }))
            }

            override fun select(start: Int, end: Int) {
                val s = session ?: return
                val length = s.doc.length
                s.setSelectionRange(start.coerceIn(0, length), end.coerceIn(0, length))
            }
        }
    }
}
