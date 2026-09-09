package dev.ide.ui.editor.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
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
) {
    var problems by remember(preview.id, path) { mutableStateOf(emptyList<String>()) }
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
    )
    Box(modifier) {
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
): EditorPreviewContext {
    val hostBackend = backend
    val filePath = path
    val buffer = text
    val isDark = dark
    return remember(id, filePath, buffer, isDark, hostBackend) {
        object : EditorPreviewContext {
            override val backend: IdeBackend = hostBackend
            override val path: String = filePath
            override val text: String = buffer
            override val dark: Boolean = isDark
            override fun reportProblems(problems: List<String>) = onReport(problems)
            override fun openFile(path: String, offset: Int) = onOpenFile(path, offset)
            override fun openScreen(id: String) = onOpenScreen(id)
        }
    }
}
