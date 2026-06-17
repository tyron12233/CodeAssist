package dev.ide.ui.editor.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ide.preview.LayoutPreviewBackend
import dev.ide.preview.LayoutPreviewResult
import dev.ide.preview.PreviewEngine
import dev.ide.preview.PreviewRequest
import dev.ide.preview.RenderNode
import dev.ide.preview.SimpleRenderContext
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlin.math.min
import kotlin.math.roundToInt

/** A previewed device profile (logical size in dp + density). Landscape swaps width/height. */
private data class DeviceProfile(val label: String, val wdp: Int, val hdp: Int, val density: Float)

private val DEVICES = listOf(
    DeviceProfile("Phone", 360, 800, 2f),
    DeviceProfile("Compact", 320, 568, 2f),
    DeviceProfile("Tablet", 600, 960, 2f),
)

/**
 * The layout Preview view: renders the layout on a device card floating over the editor surface with free
 * pan + pinch/zoom (drag to scroll, pinch or the toolbar to zoom, double-tap to fit). A top bar switches
 * device / orientation / night; the bottom bar holds zoom + the System-UI toggle. Tapping a view selects it
 * (highlight + attribute inspector); render problems surface in a chip. Styled with CodeAssist tokens.
 */
@Composable
actual fun LayoutPreviewPane(path: String, text: String, backend: IdeBackend, modifier: Modifier) {
    var deviceIndex by remember { mutableStateOf(0) }
    var landscape by remember { mutableStateOf(false) }
    var night by remember { mutableStateOf(false) }
    var showChrome by remember { mutableStateOf(true) }

    val device = DEVICES[deviceIndex]
    val wdp = if (landscape) device.hdp else device.wdp
    val hdp = if (landscape) device.wdp else device.hdp
    val widthPx = (wdp * device.density).toInt()
    val heightPx = (hdp * device.density).toInt()
    val request = PreviewRequest(widthPx, heightPx, device.density, showChrome, night)

    val lpBackend = backend as? LayoutPreviewBackend
    var result by remember { mutableStateOf<LayoutPreviewResult?>(null) }
    LaunchedEffect(path, text, request, lpBackend) {
        result = lpBackend?.layoutPreview(path, text, request)
    }

    val fontResolver = LocalFontFamilyResolver.current
    val measurer = remember(fontResolver) { TextMeasurer(fontResolver, Density(1f), LayoutDirection.Ltr) }
    val gfx = remember(measurer) { ComposeGraphics(measurer) }

    var selected by remember(path) { mutableStateOf<RenderNode?>(null) }
    var showProblems by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize().background(Ca.colors.editorBg)) {
        val r = result
        if (lpBackend == null || r == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (lpBackend == null) "Layout preview isn't available for this project" else "No layout preview for this file",
                    color = Ca.colors.textTertiary, style = Ca.type.footnote,
                )
            }
            return@Box
        }

        val resources = remember(r) { UiPreviewResources(r.resources, r.imageFile) }
        val engine = remember(r) { PreviewEngine(SimpleRenderContext(gfx, resources, device.density, device.density)) }
        val accent = Ca.colors.accent

        BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
            val hostDensity = LocalDensity.current.density
            val devWdp = widthPx / hostDensity
            val devHdp = heightPx / hostDensity
            val fit = min((maxWidth.value * 0.9f) / devWdp, (maxHeight.value * 0.86f) / devHdp).coerceIn(0.1f, 4f)

            var userScale by remember(path) { mutableStateOf(0f) }
            var offset by remember(path) { mutableStateOf(Offset.Zero) }
            val scale = if (userScale <= 0f) fit else userScale

            // Re-fit (and recentre) whenever the device viewport changes — rotation or device switch.
            LaunchedEffect(widthPx, heightPx) { userScale = 0f; offset = Offset.Zero }

            Box(
                Modifier.fillMaxSize()
                    .pointerInput(path) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val base = if (userScale <= 0f) fit else userScale
                            userScale = (base * zoom).coerceIn(0.2f, 5f)
                            offset += pan
                        }
                    }
                    .pointerInput(path) { detectTapGestures(onTap = { selected = null }) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .graphicsLayer {
                            scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                        }
                        .size(devWdp.dp, devHdp.dp)
                        .shadow(16.dp, RoundedCornerShape(Ca.radius.lg))
                        .clip(RoundedCornerShape(Ca.radius.lg))
                        .background(Color.White)
                        .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.lg))
                        .pointerInput(r) {
                            detectTapGestures(
                                onTap = { p -> selected = engine.hitTest(r.root, p.x, p.y) },
                                onDoubleTap = { if (userScale <= 0f) userScale = 1f else { userScale = 0f; offset = Offset.Zero } },
                            )
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val rc = ComposeRCanvas(this, measurer)
                        engine.render(r.root, widthPx, heightPx, rc)
                        selected?.let { n ->
                            drawRect(
                                color = accent,
                                topLeft = Offset(n.left.toFloat(), n.top.toFloat()),
                                size = Size(n.width.toFloat(), n.height.toFloat()),
                                style = Stroke(width = 2f * device.density),
                            )
                        }
                    }
                }
            }

            // Top bar: device / orientation / night.
            GlassBar(Modifier.align(Alignment.TopCenter).padding(Ca.spacing.s3)) {
                PillButton({ deviceIndex = (deviceIndex + 1) % DEVICES.size }) {
                    Text("${device.label} · ${wdp}×$hdp", color = Ca.colors.textSecondary, style = Ca.type.caption, modifier = Modifier.padding(horizontal = Ca.spacing.s2))
                }
                Divider()
                PillButton({ landscape = !landscape }) {
                    Icon(CaIcons.refresh, "Rotate", Modifier.size(15.dp), tint = if (landscape) Ca.colors.accent else Ca.colors.textSecondary)
                }
                Divider()
                PillButton({ night = !night }) {
                    Text("Night", color = if (night) Ca.colors.accent else Ca.colors.textTertiary, style = Ca.type.caption, modifier = Modifier.padding(horizontal = Ca.spacing.s1))
                }
            }

            // Bottom bar: zoom / fit / chrome.
            GlassBar(Modifier.align(Alignment.BottomCenter).padding(Ca.spacing.s4)) {
                PillButton({ val b = if (userScale <= 0f) fit else userScale; userScale = (b / 1.25f).coerceIn(0.2f, 5f) }) { MinusGlyph() }
                Text("${(scale * 100f).roundToInt()}%", color = Ca.colors.textSecondary, style = Ca.type.caption, modifier = Modifier.width(44.dp), textAlign = TextAlign.Center)
                PillButton({ val b = if (userScale <= 0f) fit else userScale; userScale = (b * 1.25f).coerceIn(0.2f, 5f) }) { Icon(CaIcons.plus, "Zoom in", Modifier.size(16.dp), tint = Ca.colors.textPrimary) }
                Divider()
                PillButton({ userScale = 0f; offset = Offset.Zero }) { Icon(CaIcons.refresh, "Fit", Modifier.size(15.dp), tint = Ca.colors.textSecondary) }
                Divider()
                PillButton({ showChrome = !showChrome }) { Icon(CaIcons.eye, "Toggle system UI", Modifier.size(16.dp), tint = if (showChrome) Ca.colors.accent else Ca.colors.textTertiary) }
            }

            // Problem chip (top-start), expandable.
            if (r.problems.isNotEmpty()) {
                Column(Modifier.align(Alignment.TopStart).padding(Ca.spacing.s3), horizontalAlignment = Alignment.Start) {
                    GlassBar(Modifier) {
                        PillButton({ showProblems = !showProblems }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(CaIcons.warning, "Render problems", Modifier.size(15.dp), tint = Ca.colors.warning)
                                Text(" ${r.problems.size}", color = Ca.colors.warning, style = Ca.type.caption)
                            }
                        }
                    }
                    if (showProblems) {
                        Column(
                            Modifier.padding(top = Ca.spacing.s2).widthIn(max = 320.dp)
                                .clip(RoundedCornerShape(Ca.radius.md)).background(Ca.colors.surface)
                                .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.md))
                                .padding(Ca.spacing.s3),
                            verticalArrangement = Arrangement.spacedBy(Ca.spacing.s1),
                        ) {
                            for (p in r.problems) Text("<${p.tag.substringAfterLast('.')}> — ${p.message}", color = Ca.colors.textSecondary, style = Ca.type.caption2)
                        }
                    }
                }
            }

            // Inspector (top-end), when a node is selected.
            selected?.let { node ->
                InspectorPanel(
                    node = node, density = device.density,
                    modifier = Modifier.align(Alignment.TopEnd).padding(Ca.spacing.s3),
                    onClose = { selected = null },
                )
            }
        }
    }
}

/** A floating attribute inspector for the selected node. */
@Composable
private fun InspectorPanel(node: RenderNode, density: Float, modifier: Modifier, onClose: () -> Unit) {
    fun dp(px: Int) = "${(px / density).roundToInt()}dp"
    val rows = buildList {
        add("Tag" to node.tag.substringAfterLast('.'))
        node.props.id?.let { add("id" to it) }
        add("Bounds" to "${dp(node.left)}, ${dp(node.top)}")
        add("Size" to "${dp(node.width)} × ${dp(node.height)}")
        if (node.props.hPadding != 0 || node.props.vPadding != 0) add("Padding" to "${dp(node.props.paddingLeft)} ${dp(node.props.paddingTop)} ${dp(node.props.paddingRight)} ${dp(node.props.paddingBottom)}")
        if (node.props.text.isNotEmpty()) add("Text" to node.props.text.toString().take(60))
        node.props.backgroundColor?.let { add("Background" to "#%08X".format(it)) }
    }
    Column(
        modifier.widthIn(min = 200.dp, max = 280.dp)
            .shadow(10.dp, RoundedCornerShape(Ca.radius.md))
            .clip(RoundedCornerShape(Ca.radius.md)).background(Ca.colors.surface)
            .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.md)),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = Ca.spacing.s3, end = Ca.spacing.s1, top = Ca.spacing.s1, bottom = Ca.spacing.s1), verticalAlignment = Alignment.CenterVertically) {
            Text(node.tag.substringAfterLast('.'), color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            PillButton(onClose) { Icon(CaIcons.close, "Close", Modifier.size(14.dp), tint = Ca.colors.textTertiary) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))
        Column(Modifier.verticalScroll(rememberScrollState()).padding(Ca.spacing.s3), verticalArrangement = Arrangement.spacedBy(Ca.spacing.s2)) {
            for ((label, value) in rows) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2)) {
                Text(label, color = Ca.colors.textTertiary, style = Ca.type.caption, modifier = Modifier.width(72.dp))
                Text(value, color = Ca.colors.textPrimary, style = Ca.type.caption)
            }
        }
    }
}

/** A horizontal glass pill container for control clusters. */
@Composable
private fun GlassBar(modifier: Modifier, content: @Composable () -> Unit) {
    Row(
        modifier.shadow(8.dp, RoundedCornerShape(Ca.radius.pill)).clip(RoundedCornerShape(Ca.radius.pill))
            .background(Ca.colors.glassReg).border(1.dp, Ca.colors.glassEdge, RoundedCornerShape(Ca.radius.pill))
            .padding(horizontal = Ca.spacing.s2, vertical = Ca.spacing.s1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s1),
        content = { content() },
    )
}

@Composable
private fun PillButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    // Stable gesture key (Unit) + always-current handler, so the tap detector isn't restarted every
    // recomposition (which could drop the tap — the cause of rotate/device taps not registering).
    val current by androidx.compose.runtime.rememberUpdatedState(onClick)
    Box(
        Modifier.height(32.dp).widthIn(min = 32.dp).clip(RoundedCornerShape(Ca.radius.sm))
            .pointerInput(Unit) { detectTapGestures { current() } },
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

@Composable
private fun MinusGlyph() {
    Box(Modifier.width(12.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Ca.colors.textPrimary))
}

@Composable
private fun Divider() {
    Box(Modifier.width(1.dp).height(18.dp).background(Ca.colors.separator))
}
