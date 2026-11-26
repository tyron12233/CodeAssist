package dev.ide.ui.editor.preview

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.components.CodeSample

/**
 * Same fallback the JVM hosts take when the backend is not a `LayoutPreviewBackend`: show the lesson's XML
 * read-only so the lesson still reads, rather than a live frame.
 */
@Composable
actual fun LessonLayoutPreview(
    xml: String,
    backend: IdeBackend,
    interactive: Boolean,
    caption: String,
    modifier: Modifier,
) {
    CodeSample(xml.trim(), "xml", modifier.fillMaxWidth())
}
