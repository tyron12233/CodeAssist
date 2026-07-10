package dev.ide.ui.editor.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ide.ui.backend.IdeBackend

/**
 * A compact, live Android layout preview embedded in a Learn lesson (a [dev.ide.ui.backend.UiContentBlock.LayoutPreview]).
 * Renders the self-contained [xml] on a small phone-frame card through the owned layout engine, so an Android
 * lesson can *show* the UI it teaches. When [interactive] the learner gets an editable XML field above the
 * frame that re-renders as they type. [caption] labels the frame.
 *
 * The actual lives in the `jvmShared` source set (it drives the `layout-preview-api` `PreviewEngine`, which
 * commonMain can't depend on) and casts [backend] to [dev.ide.preview.LayoutPreviewBackend]. A host without
 * Android support renders a plain read-only XML fallback.
 */
@Composable
expect fun LessonLayoutPreview(
    xml: String,
    backend: IdeBackend,
    interactive: Boolean,
    caption: String,
    modifier: Modifier,
)
