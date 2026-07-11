package dev.ide.ui.editor.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.editor.core.EditorSession

/**
 * True for an Android layout XML (`res/layout/…`), which the Preview view renders through the owned layout
 * engine (built-in widgets + system chrome), distinct from the drawable/color/bitmap resource previews.
 */
fun isLayoutPreviewable(path: String): Boolean {
    val p = path.replace('\\', '/')
    return p.contains("/res/layout") && p.endsWith(".xml")
}

/**
 * Renders a layout XML preview. The actual lives in the `jvmShared` source set (it needs `layout-preview-api`,
 * a plain-JVM artifact commonMain can't depend on) and drives the owned `PreviewEngine` over a Compose-backed
 * `RCanvas`; the backend is cast to `LayoutPreviewBackend` to fetch the inflated tree. A host without Android
 * support renders an empty state.
 *
 * [session] is the tab's shared editor session: the real-view attribute editor applies its edits to it (so the
 * Code view + preview stay in sync), exactly as the block editor does.
 */
@Composable
expect fun LayoutPreviewPane(path: String, text: String, backend: IdeBackend, session: EditorSession, modifier: Modifier)
