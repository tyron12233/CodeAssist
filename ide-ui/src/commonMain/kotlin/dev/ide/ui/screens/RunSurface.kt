package dev.ide.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventType
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
 * a stream of frames the program repainted, which this draws, and input goes the other way so its buttons and
 * key handlers work. A console run publishes no frames and never shows this.
 *
 * The surface tells the program what size it is being drawn at, so the program lays out and paints at exactly
 * that size rather than being scaled to fit. That makes the mapping from a touch to the program's own
 * coordinates the identity in the steady state, and it means a resized pane reflows the UI the way resizing a
 * real window would, instead of stretching a stale picture.
 */
@Composable
fun RunSurface(
    frame: RunFrameUi,
    onPointer: (action: Int, x: Float, y: Float) -> Unit,
    onKey: (action: Int, keyCode: Int, keyChar: Char) -> Unit,
    onScroll: (x: Float, y: Float, notches: Int) -> Unit,
    onSurfaceSize: (widthPx: Int, heightPx: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var drawn by remember { mutableStateOf(IntSize.Zero) }
    val focus = remember { FocusRequester() }

    // Keyed by seq: one decode per frame the program actually produced, not per recomposition. Reading the
    // file is IO (the pixels crossed a process boundary to get here), so it stays off the composition thread.
    LaunchedEffect(frame.seq) {
        image = withContext(Dispatchers.Default) {
            readRunFrame(frame.path)?.let { imageFromRgba(it, frame.width, frame.height) }
        }
    }

    // Ask for the keyboard once the surface exists, so a program that reads keys gets them without the user
    // having to find something to tap first.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onSizeChanged { size ->
                if (size.width > 0 && size.height > 0 && size != drawn) {
                    drawn = size
                    onSurfaceSize(size.width, size.height)
                }
            }
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                val action = when (event.type) {
                    KeyEventType.KeyDown -> RUN_KEY_DOWN
                    KeyEventType.KeyUp -> RUN_KEY_UP
                    else -> return@onKeyEvent false
                }
                val char = event.utf16CodePoint.takeIf { it in PRINTABLE }?.toChar() ?: CHAR_UNDEFINED
                onKey(action, event.key.keyCode.toInt(), char)
                true
            }
            .pointerInput(frame.width, frame.height, drawn) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val position = event.changes.firstOrNull()?.position ?: continue
                        val (x, y) = toProgramSpace(position.x, position.y, frame, drawn) ?: continue
                        if (event.type == PointerEventType.Scroll) {
                            // Compose reports a positive delta when the content should move down, which is
                            // also AWT's sign convention, so it passes straight through.
                            val notches = event.changes.firstOrNull()?.scrollDelta?.y?.toInt() ?: 0
                            if (notches != 0) onScroll(x, y, notches)
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        val action = when (event.type) {
                            PointerEventType.Press -> RUN_POINTER_DOWN
                            PointerEventType.Move -> RUN_POINTER_MOVE
                            PointerEventType.Release -> RUN_POINTER_UP
                            else -> continue
                        }
                        onPointer(action, x, y)
                        event.changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val shown = image ?: return@Box
        Image(
            bitmap = shown,
            contentDescription = null,
            // Fit, not Crop: while the program is still repainting at the new size, a frame of the OLD size is
            // shown whole and letterboxed rather than silently cropped.
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Map a touch on the drawn surface back into the program's own pixel space, or null when it landed on the
 * letterboxing outside the frame.
 *
 * This is the identity once the program has repainted at the surface's size; it matters in the window between
 * a resize and the frame that answers it, and it is what keeps a press and its release consistent.
 */
private fun toProgramSpace(x: Float, y: Float, frame: RunFrameUi, drawn: IntSize): Pair<Float, Float>? {
    if (drawn.width <= 0 || drawn.height <= 0 || frame.width <= 0 || frame.height <= 0) return null
    val scale = minOf(drawn.width.toFloat() / frame.width, drawn.height.toFloat() / frame.height)
    if (scale <= 0f) return null
    val px = (x - (drawn.width - frame.width * scale) / 2f) / scale
    val py = (y - (drawn.height - frame.height * scale) / 2f) / scale
    if (px < 0f || py < 0f || px > frame.width || py > frame.height) return null
    return px to py
}

// Mirrors dev.ide.build.engine.RunPointer / RunKey, which this multiplatform module cannot depend on.
private const val RUN_POINTER_DOWN = 0
private const val RUN_POINTER_MOVE = 1
private const val RUN_POINTER_UP = 2
private const val RUN_KEY_DOWN = 0
private const val RUN_KEY_UP = 1
private const val CHAR_UNDEFINED = '￿'

/** Code points that are an actual character rather than a control or a surrogate. */
private val PRINTABLE = 0x20..0xFFFD
