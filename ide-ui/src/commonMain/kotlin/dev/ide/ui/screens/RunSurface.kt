package dev.ide.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.RunFrameUi
import dev.ide.ui.editor.preview.imageFromRgba
import dev.ide.ui.editor.preview.readRunFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The window of a running program, inside the Run screen.
 *
 * A windowed program (a Swing app) is not text, so its output does not belong in the transcript: it arrives as
 * a stream of frames the program repainted, which this draws, and taps go the other way so the program's
 * buttons work. A console run publishes no frames and never shows this.
 *
 * The frame is delivered as a file path rather than pixels, because it crossed two process boundaries to get
 * here (see `ProgramIo.frame`), so reading it is IO and happens off the composition thread.
 */
@Composable
fun RunSurface(frame: RunFrameUi, onTap: (Float, Float) -> Unit, modifier: Modifier = Modifier) {
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var drawn by remember { mutableStateOf(IntSize.Zero) }

    // Keyed by seq: one decode per frame the program actually produced, not per recomposition.
    LaunchedEffect(frame.seq) {
        image = withContext(Dispatchers.Default) {
            readRunFrame(frame.path)?.let { imageFromRgba(it, frame.width, frame.height) }
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val shown = image ?: return@Box
        Image(
            bitmap = shown,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { drawn = it }
                .pointerInput(frame.width, frame.height, drawn) {
                    detectTapGestures { offset ->
                        // The surface is drawn scaled to fit, so a tap has to be mapped back into the
                        // program's own pixel space before it means anything to the program.
                        if (drawn.width <= 0 || drawn.height <= 0) return@detectTapGestures
                        val scale = minOf(
                            drawn.width.toFloat() / frame.width,
                            drawn.height.toFloat() / frame.height,
                        )
                        if (scale <= 0f) return@detectTapGestures
                        val insetX = (drawn.width - frame.width * scale) / 2f
                        val insetY = (drawn.height - frame.height * scale) / 2f
                        val x = (offset.x - insetX) / scale
                        val y = (offset.y - insetY) / scale
                        if (x in 0f..frame.width.toFloat() && y in 0f..frame.height.toFloat()) onTap(x, y)
                    }
                },
        )
    }
}
