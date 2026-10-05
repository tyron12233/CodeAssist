package dev.ide.ui.editor.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.ide.ui.ComposePreviewHost
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.languageFor
import dev.ide.ui.ext.HeadlessPreview
import dev.ide.ui.ext.HeadlessPreviewRenderer
import dev.ide.ui.ext.PreviewSnapshot
import dev.ide.ui.ext.PreviewSnapshots
import dev.ide.ui.theme.CodeAssistTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * The platform half of a headless preview: composes content on a surface nobody sees, keeps it alive and drawing
 * while [compose]'s block runs, then disposes it.
 */
interface OffscreenComposer {
    suspend fun <T> compose(
        widthPx: Int,
        heightPx: Int,
        density: Float,
        content: @Composable () -> Unit,
        block: suspend () -> T,
    ): T
}

/**
 * Renders a file's preview with no pane on screen, for the AI agent's `screenshot_preview` on a file the user has
 * not opened. It composes the same pane the editor would show (the Compose `@Preview` pane, or the layout pane for
 * a layout XML) on an [OffscreenComposer], so discovery, device sizing, the readiness gate, lowering, resources and
 * the sandbox all behave exactly as on screen. Once the pane reports it has settled and its frame stops changing,
 * the frame is taken through the pane's own capture ([rememberPreviewCapture]).
 *
 * One render at a time: each one composes a whole pane and interprets the preview, which is not work to run twice
 * at once on a phone.
 */
class OffscreenPreviewRenderer(
    private val backend: IdeBackend,
    private val host: ComposePreviewHost?,
    private val composer: OffscreenComposer,
    private val timeoutMs: Long = RENDER_TIMEOUT_MS,
) : HeadlessPreviewRenderer {

    private val oneAtATime = Mutex()

    override suspend fun render(path: String, text: String, preview: String?): HeadlessPreview = oneAtATime.withLock {
        val name = path.substringAfterLast('/')
        val layout = isLayoutPreviewable(path)
        var selected: String? = null
        if (!layout) {
            val previews = runCatching { backend.preview.composePreviews(path, text) }.getOrDefault(emptyList())
            if (previews.isEmpty()) return HeadlessPreview(null, listOf("No @Preview function in $name."))
            if (preview != null) {
                val match = previews.firstOrNull { it.functionName == preview || it.label == preview || it.variantId == preview }
                    ?: return HeadlessPreview(
                        null,
                        listOf("No @Preview named $preview in $name. Its previews: ${previews.joinToString { it.functionName }}."),
                    )
                selected = match.variantId
            }
        }
        val probe = StatusProbe()
        composer.compose(SURFACE_WIDTH_PX, SURFACE_HEIGHT_PX, SURFACE_DENSITY, content = {
            CodeAssistTheme(dark = false) {
                Box(Modifier.fillMaxSize()) {
                    if (layout) {
                        val session = remember(path, text) { EditorSession(text, languageFor(name)) }
                        LayoutPreviewPane(path, text, backend, session, Modifier.fillMaxSize(), onStatus = probe::update)
                    } else {
                        ComposePreviewPane(
                            path, text, backend, host, Modifier.fillMaxSize(),
                            selected = selected, onStatus = probe::update,
                        )
                    }
                }
            }
        }) { awaitFrame(path, probe) }
    }

    /**
     * Waits for the pane to settle, then for two captures in a row to match, so an image still loading or an
     * entrance animation still running is not what gets reported. A preview that never stops changing (an
     * infinite animation) is taken as it is after [MAX_SETTLE_MS].
     */
    private suspend fun awaitFrame(path: String, probe: StatusProbe): HeadlessPreview {
        val started = TimeSource.Monotonic.markNow()
        var settledAt: TimeSource.Monotonic.ValueTimeMark? = null
        var previous: PreviewSnapshot? = null
        while (true) {
            val status = probe.status
            if (status != null && status.settled && status.failed) return HeadlessPreview(null, status.problems)
            if (status?.settled == true) {
                val since = settledAt ?: TimeSource.Monotonic.markNow().also { settledAt = it }
                if (since.elapsedNow().inWholeMilliseconds >= SETTLE_MS) {
                    val shot = PreviewSnapshots.capture(path)
                    if (shot != null) {
                        val stable = previous?.png?.contentEquals(shot.png) == true
                        if (stable || since.elapsedNow().inWholeMilliseconds >= MAX_SETTLE_MS) {
                            return HeadlessPreview(shot, status.problems)
                        }
                        previous = shot
                    }
                }
            } else {
                // Not settled (again): a classpath rebuild started, or the render is still interpreting.
                settledAt = null
                previous = null
            }
            if (started.elapsedNow().inWholeMilliseconds >= timeoutMs) {
                val why = "The preview did not finish rendering within ${timeoutMs / 1000}s" +
                    " (the project may still be indexing or resolving its libraries). Try again shortly."
                return HeadlessPreview(previous, listOf(why) + status?.problems.orEmpty())
            }
            delay(POLL_MS)
        }
    }

    /** The pane's latest status, written by its composition and read by the waiting coroutine. */
    private class StatusProbe {
        @Volatile var status: PreviewPaneStatus? = null
        fun update(next: PreviewPaneStatus) { status = next }
    }

    private companion object {
        // A phone-sized surface: the pane lays its chrome out in it, and the device card renders at its own size.
        const val SURFACE_WIDTH_PX = 1080
        const val SURFACE_HEIGHT_PX = 2160
        const val SURFACE_DENSITY = 2.5f

        const val POLL_MS = 150L
        /** How long a settled pane is left to draw before the first capture. */
        const val SETTLE_MS = 400L
        /** How long a settled pane may keep changing before its frame is taken as it is. */
        const val MAX_SETTLE_MS = 4_000L
        /** Long enough for a cold classpath; a preview that needs longer reports why it is not done. */
        const val RENDER_TIMEOUT_MS = 90_000L
    }
}
