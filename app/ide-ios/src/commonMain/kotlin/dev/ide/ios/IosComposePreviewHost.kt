package dev.ide.ios

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import dev.ide.ui.ComposePreviewHost
import dev.ide.ui.backend.UiComposePreview
import dev.ide.ui.editor.preview.PreviewIssue
import dev.ide.ui.editor.preview.PreviewIssueLevel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/**
 * The Compose preview on iOS: a live session in the VM ([IosBackend.openComposePreview]) whose frames are
 * shown as images. Touches on the image are sent into the session, and frames keep coming while the
 * preview has work pending (a ripple, an animation, state an effect changed), so the preview behaves like
 * the desktop and Android ones, at the frame rate interpreting allows.
 *
 * A new session is opened when the text, the variant, the size or the theme changes; the old one is closed.
 */
internal class IosComposePreviewHost(private val backend: IosBackend) : ComposePreviewHost {

    @Composable
    override fun Preview(
        path: String,
        preview: UiComposePreview,
        text: String,
        dark: Boolean,
        onProblems: (List<PreviewIssue>) -> Unit,
        onBusy: (Boolean) -> Unit,
        modifier: Modifier,
    ) {
        val report by rememberUpdatedState(onProblems)
        val busy by rememberUpdatedState(onBusy)
        BoxWithConstraints(modifier) {
            val density = LocalDensity.current.density
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else (360 * density).toInt()
            val height = if (constraints.hasBoundedHeight) constraints.maxHeight else (640 * density).toInt()
            var frame by remember { mutableStateOf<ImageBitmap?>(null) }
            var session by remember { mutableStateOf<Int?>(null) }
            // Each poke asks for frames until the session settles; extra pokes while one is running merge.
            val pokes = remember { Channel<Unit>(Channel.CONFLATED) }

            fun show(result: IosPreviewFrame) {
                result.png?.let { frame = org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }
                if (result.problems.isNotEmpty()) report(result.problems.map { PreviewIssue(PreviewIssueLevel.ERROR, "Preview", it) })
            }

            LaunchedEffect(path, text, preview.variantId, width, height, dark) {
                // Opening cannot be stopped once it is on the preview thread, so wait out a burst of keystrokes
                // first: a newer key cancels this effect during the delay, before any work is queued.
                delay(DEBOUNCE_MS)
                busy(true)
                try {
                    val config = preview.config
                    val background = if (config.showBackground) config.backgroundColor?.takeIf { it != 0L } ?: 0xFFFFFFFFL else 0L
                    val opened = backend.openComposePreview(
                        path, text, preview.functionName, width, height, density,
                        fontScale = config.fontScale ?: 1f,
                        dark = dark || config.nightMode == true,
                        background = background,
                    )
                    session?.let { backend.closeComposePreview(it) }
                    session = opened.session
                    report(emptyList())
                    show(opened.frame)
                    if (opened.frame.animating) pokes.trySend(Unit)
                } finally {
                    busy(false)
                }
            }

            LaunchedEffect(session) {
                val id = session ?: return@LaunchedEffect
                for (poke in pokes) {
                    do {
                        delay(FRAME_MS)
                        val next = backend.composePreviewFrame(id)
                        show(next)
                    } while (next.animating)
                }
            }

            DisposableEffect(Unit) {
                onDispose { session?.let { backend.closeComposePreviewLater(it) } }
            }

            // What the preview is doing while it works: the first render of a project resolves its libraries
            // and loads Compose into the VM, which takes long enough that a bare spinner looks stuck.
            val stage by backend.previewStage.collectAsState()
            stage?.let { label ->
                Text(
                    label + "...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomCenter).zIndex(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            frame?.let { image ->
                Image(
                    image,
                    contentDescription = preview.label,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().pointerInput(session) {
                        val id = session ?: return@pointerInput
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                for (change in event.changes) {
                                    val kind = when {
                                        change.pressed && !change.previousPressed -> 0
                                        !change.pressed && change.previousPressed -> 2
                                        else -> 1
                                    }
                                    // The frame is drawn at the slot's pixel size, so a touch's position IS a
                                    // position in the scene.
                                    backend.composePreviewPointer(id, kind, change.position.x, change.position.y)
                                }
                                pokes.trySend(Unit)
                            }
                        }
                    },
                )
            }
        }
    }
}

private const val DEBOUNCE_MS = 300L
private const val FRAME_MS = 16L

/** A rendered preview frame, or why there is none; [animating] when the preview has more frames to show. */
internal class IosPreviewFrame(val png: ByteArray?, val problems: List<String>, val animating: Boolean = false)

/** A live preview session and its first frame. */
internal class IosPreviewSession(val session: Int?, val frame: IosPreviewFrame)
