package dev.ide.ui.editor.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ide.preview.LayoutPreviewBackend
import dev.ide.preview.LayoutPreviewResult
import dev.ide.preview.PreviewEngine
import dev.ide.preview.PreviewRequest
import dev.ide.preview.SimpleRenderContext
import dev.ide.ui.backend.EditorService
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.editor.CodeEditor
import dev.ide.ui.editor.CodeLanguage
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.screens.CodeSample
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.delay
import kotlin.math.min

/** Lesson previews render at this logical device width (dp) and density — a clean phone screen, chrome off. */
private const val LESSON_DEVICE_WDP = 360
private const val LESSON_DEVICE_HDP = 900
private const val LESSON_DENSITY = 2f

/** On-screen width (dp) the phone frame is drawn at inside a lesson (shrinks to fit a narrow column). */
private const val FRAME_WIDTH_DP = 268f

/** Debounce a typing burst in the interactive XML field into a single re-render. */
private const val LESSON_PREVIEW_DEBOUNCE_MS = 220L

@Composable
actual fun LessonLayoutPreview(
    xml: String,
    backend: IdeBackend,
    interactive: Boolean,
    caption: String,
    modifier: Modifier,
) {
    val lpBackend = backend as? LayoutPreviewBackend
    if (lpBackend == null) {
        // Host without Android support: fall back to the XML read-only so the lesson still reads.
        CodeSample(xml.trim(), "xml", modifier.fillMaxWidth())
        return
    }

    // The editable buffer (interactive lessons only) — reset when the authored xml changes.
    val session = remember(xml) { EditorSession(xml.trim(), CodeLanguage.Xml) }
    // A no-op editor service so the embedded XML field is a plain, distraction-free playground.
    val editorBackend = remember(backend) { PlaygroundBackend(backend) }

    val widthPx = (LESSON_DEVICE_WDP * LESSON_DENSITY).toInt()
    val heightPx = (LESSON_DEVICE_HDP * LESSON_DENSITY).toInt()
    val request = remember { PreviewRequest(widthPx, heightPx, LESSON_DENSITY, showChrome = false) }

    // Re-render on each settled edit (interactive) or once (static). Reading `textRevision` in composition
    // observes edits; the buffer text is pulled inside the effect (the editor never materializes it per key).
    val rev = if (interactive) session.textRevision else 0
    var result by remember(xml) { mutableStateOf<LayoutPreviewResult?>(null) }
    LaunchedEffect(xml, rev, request, lpBackend, interactive) {
        if (interactive) delay(LESSON_PREVIEW_DEBOUNCE_MS)
        val src = if (interactive) session.doc.text else xml
        result = lpBackend.layoutPreviewStandalone(src, request)
    }

    val fontResolver = LocalFontFamilyResolver.current
    val measurer = remember(fontResolver) { TextMeasurer(fontResolver, Density(1f), LayoutDirection.Ltr) }
    val gfx = remember(measurer) { ComposeGraphics(measurer) }

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (interactive) {
            val editorShape = RoundedCornerShape(Ca.radius.md)
            Box(
                Modifier.fillMaxWidth().height(180.dp).clip(editorShape)
                    .background(Ca.colors.editorBg).border(1.dp, Ca.colors.hairline, editorShape),
            ) {
                CodeEditor(
                    path = "preview.xml",
                    session = session,
                    backend = editorBackend,
                    modifier = Modifier.fillMaxSize(),
                    completionAutoPopup = false,
                )
            }
        }

        DeviceFrame(result, measurer, gfx, widthPx, heightPx)

        val cap = caption.ifBlank { if (interactive) "Edit the XML above and watch it update" else "" }
        if (cap.isNotBlank()) {
            Text(cap, color = Ca.colors.textTertiary, style = Ca.type.caption, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * The phone-screen card: renders [result]'s owned tree scaled to fit the lesson column (device pixels mapped
 * onto the frame width, so text + geometry stay proportional at any host density). Null/empty shows a subtle
 * placeholder rather than a blank card.
 */
@Composable
private fun DeviceFrame(
    result: LayoutPreviewResult?,
    measurer: TextMeasurer,
    gfx: ComposeGraphics,
    widthPx: Int,
    heightPx: Int,
) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val frameWdp = min(FRAME_WIDTH_DP, maxWidth.value - 4f).coerceAtLeast(120f)
        val r = result
        if (r == null) {
            PlaceholderCard(frameWdp)
            return@BoxWithConstraints
        }
        val resources = remember(r) { UiPreviewResources(r.resources, r.imageFile) }
        val engine = remember(r) { PreviewEngine(SimpleRenderContext(gfx, resources, LESSON_DENSITY, LESSON_DENSITY)) }
        // Measure once to learn the content's natural (wrap) height in device px, then size the frame to it.
        val contentH = remember(r, widthPx, heightPx) {
            runCatching { engine.measureAndLayout(r.root, widthPx, heightPx); r.root.measured.height }.getOrDefault(0)
        }.coerceIn(1, heightPx)
        // Proportional on-screen frame height (dp), capped so a tall layout doesn't dominate the lesson.
        val frameHdp = (frameWdp * contentH / widthPx).coerceIn(48f, 560f)
        val shape = RoundedCornerShape(Ca.radius.lg)
        Box(
            Modifier.width(frameWdp.dp).height(frameHdp.dp)
                .shadow(12.dp, shape).clip(shape)
                .background(Color.White).border(1.dp, Ca.colors.separator, shape).clipToBounds(),
        ) {
            val hostDensity = LocalDensity.current.density
            // Device px → frame px: the frame is `frameWdp` dp = `frameWdp*hostDensity` px wide; map the
            // device's `widthPx` onto it so the layout fills the frame width at any host density.
            val k = (frameWdp * hostDensity) / widthPx
            Canvas(Modifier.fillMaxSize()) {
                withTransform({ scale(k, k, pivot = Offset.Zero) }) {
                    engine.render(r.root, widthPx, contentH, ComposeRCanvas(this, measurer))
                }
            }
        }
    }
}

@Composable
private fun PlaceholderCard(frameWdp: Float) {
    val shape = RoundedCornerShape(Ca.radius.lg)
    Box(
        Modifier.width(frameWdp.dp).height((frameWdp * 1.2f).dp)
            .clip(shape).background(Ca.colors.surface2).border(1.dp, Ca.colors.separator, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text("Rendering preview…", color = Ca.colors.textTertiary, style = Ca.type.caption)
    }
}

/**
 * Wraps the real backend but swaps in a no-op [EditorService] so the lesson's XML playground editor is a
 * plain highlighted text field (no completion / diagnostics against the open project, which may be absent).
 * Mirrors the interactive-exercise editor's standalone-editor pattern.
 */
private class PlaygroundBackend(real: IdeBackend) : IdeBackend by real {
    override val editor: EditorService = object : EditorService {
        override fun updateDocument(path: String, text: String) {}
        override fun saveFile(path: String, text: String) {}
        override suspend fun complete(path: String, text: String, offset: Int): UiCompletionResult =
            UiCompletionResult(emptyList(), offset, offset)
        override suspend fun analyze(path: String, text: String): List<UiDiagnostic> = emptyList()
    }
}
