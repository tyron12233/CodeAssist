package dev.ide.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ide.ui.editor.preview.PreviewIssue

/**
 * Renders a Compose `@Preview` from the open editor file as live UI. Platform-provided: `:ide-android` wires
 * the real on-device renderer (the interpreter composing into the IDE's own composition); the desktop
 * launcher provides the JVM counterpart. The UI only sees this interface — the interpreter/Compose-runtime
 * types stay in the platform layer.
 */
interface ComposePreviewHost {
    /**
     * Compose the `@Preview` [functionName] from [path]'s live buffer [text] into the given [modifier] slot.
     * [dark] forces the night `uiMode` so the same preview can be rendered in either colour scheme via the
     * surface's Night toggle, mirroring `@Preview(uiMode = UI_MODE_NIGHT_YES)`. The host reports its current
     * interpret/render problems through [onProblems] (empty when the preview renders cleanly) so the pane can
     * surface them in the shared problem chip rather than burying them inside the device frame.
     */
    @Composable
    fun Preview(path: String, functionName: String, text: String, dark: Boolean, onProblems: (List<PreviewIssue>) -> Unit, modifier: Modifier)
}
