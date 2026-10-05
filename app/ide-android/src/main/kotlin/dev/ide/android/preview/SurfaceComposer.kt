package dev.ide.android.preview

import android.content.Context
import androidx.compose.runtime.Composable
import dev.ide.ui.editor.preview.OffscreenComposer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [OffscreenComposer] on Android: the same [OffscreenComposeSurface] the preview process renders `@Preview`s into
 * (a ComposeView in a Presentation on a private VirtualDisplay), here in the IDE's own process, so the agent can
 * render a preview pane that is not on screen. The pane takes its own frame through its capture layer; the
 * surface only has to keep the composition alive and drawing.
 */
class SurfaceComposer(private val context: Context) : OffscreenComposer {

    override suspend fun <T> compose(
        widthPx: Int,
        heightPx: Int,
        density: Float,
        content: @Composable () -> Unit,
        block: suspend () -> T,
    ): T {
        // start() and close() hand their work to the main thread and wait for it, so they run off it.
        val surface = withContext(Dispatchers.IO) {
            OffscreenComposeSurface(context, widthPx, heightPx, (density * 160f).toInt().coerceAtLeast(1))
                .also { it.start(content) }
        }
        try {
            return block()
        } finally {
            withContext(Dispatchers.IO) { surface.close() }
        }
    }
}
