package dev.ide.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ide.ui.backend.UiComposePreview
import dev.ide.ui.editor.preview.PreviewIssue

/**
 * Renders a Compose `@Preview` from the open editor file as live UI. Platform-provided: `:ide-android` wires
 * the real on-device renderer (the interpreter composing into the IDE's own composition); the desktop
 * launcher provides the JVM counterpart. The UI only sees this interface — the interpreter/Compose-runtime
 * types stay in the platform layer.
 */
interface ComposePreviewHost {
    /**
     * Compose the [preview] variant from [path]'s live buffer [text] into the given [modifier] slot. The
     * variant carries the `@Preview` arguments to honor (size already applied to the surface by the pane; the
     * host applies the composition-level ones: `uiMode`, `locale`, `fontScale`, `@PreviewParameter`). [dark]
     * is the surface's Night toggle; the host renders night when either [dark] or the variant's `uiMode` asks
     * for it. Interpret/render problems are reported through [onProblems] (empty when clean) so the pane can
     * show them in the shared problem chip rather than over the device frame, and [onBusy] reports whether the
     * host is currently lowering/interpreting vs. settled so the pane can show a loading badge.
     */
    @Composable
    fun Preview(
        path: String,
        preview: UiComposePreview,
        text: String,
        dark: Boolean,
        onProblems: (List<PreviewIssue>) -> Unit,
        onBusy: (Boolean) -> Unit,
        modifier: Modifier
    )

    /**
     * Compose a self-contained Learn-lesson snippet [code] (its own imports + a `@Preview @Composable` entry)
     * as live UI, WITHOUT an open project: the host lowers it through the backend's hidden Compose scratch
     * (`lowerLessonComposePreview`) and renders the result against the bundled Compose runtime — so standard
     * material3/foundation composables render everywhere the launcher runs. [dark] selects the Night scheme;
     * [onProblems]/[onBusy] mirror [Preview] (problems for a chip, busy while lowering). The default is a no-op
     * so a host that doesn't support lesson previews leaves the block to its read-only code-sample fallback.
     */
    @Composable
    fun LessonPreview(
        code: String,
        dark: Boolean,
        onProblems: (List<PreviewIssue>) -> Unit,
        onBusy: (Boolean) -> Unit,
        modifier: Modifier,
    ) {
        // Default: unsupported on this host — the caller shows the code-sample fallback instead.
    }
}
