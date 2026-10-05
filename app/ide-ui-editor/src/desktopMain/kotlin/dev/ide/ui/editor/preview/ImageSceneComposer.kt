package dev.ide.ui.editor.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * [OffscreenComposer] on the desktop: an [ImageComposeScene], driven at about 60 frames a second while the caller
 * waits. The scene, its frames and the caller's block all run on one thread of their own, so the composition's
 * coroutines (the pane's effects, the capture that reads its graphics layer) are serviced between frames without
 * ever touching the window's UI thread.
 */
class ImageSceneComposer : OffscreenComposer {

    private val thread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "headless-preview").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    override suspend fun <T> compose(
        widthPx: Int,
        heightPx: Int,
        density: Float,
        content: @Composable () -> Unit,
        block: suspend () -> T,
    ): T = withContext(thread) {
        val scene = ImageComposeScene(widthPx, heightPx, Density(density), content = content)
        try {
            coroutineScope {
                val frames = launch {
                    while (true) {
                        scene.render(System.nanoTime())
                        delay(FRAME_MS)
                    }
                }
                try {
                    block()
                } finally {
                    frames.cancel()
                }
            }
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val FRAME_MS = 16L
    }
}
