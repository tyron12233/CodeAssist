package dev.ide.ui.editor.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.theme.Ide

/**
 * The XML layout preview renders through the owned `PreviewEngine`, a plain-JVM artifact, so iOS shows the
 * same empty state the JVM hosts show for a project whose backend implements no `LayoutPreviewBackend`.
 */
@Composable
actual fun LayoutPreviewPane(
    path: String,
    text: String,
    backend: IdeBackend,
    session: EditorSession,
    modifier: Modifier,
) {
    Box(modifier.fillMaxSize().background(Ide.colors.editorBg)) {
        Text(
            "Layout preview isn't available for this project",
            color = MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}
