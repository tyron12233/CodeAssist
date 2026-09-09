package dev.ide.ui.editor.preview

import dev.ide.ui.theme.Ide
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ide.ui.markdown.Markdown
import dev.ide.ui.theme.Ca

/**
 * True for a Markdown document (`.md` / `.markdown`), which the Preview view renders as formatted rich text
 * through the same view-mode toggle the Compose/layout/resource previews use.
 */
fun isMarkdownPreviewable(path: String): Boolean {
    val p = path.replace('\\', '/').lowercase()
    return p.endsWith(".md") || p.endsWith(".markdown")
}

/**
 * The Markdown Preview view — renders the document at [path] from its live buffer [text] as formatted rich
 * text (headings, emphasis, code, lists, quotes, rules) via the shared [Markdown] renderer. Read-only;
 * recomputes when the buffer changes, so the Split layout updates as you type. Rendering is fully
 * client-side (a pure text transform); no backend call is involved.
 */
@Composable
fun MarkdownPreviewPane(path: String, text: String, modifier: Modifier = Modifier) {
    Box(modifier.background(Ide.colors.editorBg)) {
        Markdown(
            text,
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Ca.spacing.s5, vertical = Ca.spacing.s4),
        )
    }
}
