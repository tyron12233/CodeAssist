package dev.ide.ui.editor.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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
import dev.ide.preview.RCanvas
import dev.ide.preview.RImage
import dev.ide.preview.RPaint
import dev.ide.preview.RPath
import dev.ide.preview.RenderNode
import dev.ide.preview.SimpleRenderContext
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlin.math.roundToInt

/**
 * The layout Preview view: renders the layout on a [PreviewSurface] device card — the same chrome (device /
 * orientation / night / pan-zoom) as the Compose preview — plus layout-only extras: a component tree, a
 * blueprint wireframe, tap-to-select with an attribute inspector, the system-UI toggle, and a render-problem
 * chip. The inflated tree is drawn through the owned `PreviewEngine` over a Compose-backed `RCanvas`.
 */
@Composable
actual fun LayoutPreviewPane(path: String, text: String, backend: IdeBackend, modifier: Modifier) {
    val state = rememberPreviewSurfaceState(path)
    var showChrome by remember { mutableStateOf(true) }
    var blueprint by remember { mutableStateOf(false) }
    var treeOpen by remember { mutableStateOf(false) }
    var selected by remember(path) { mutableStateOf<RenderNode?>(null) }

    val request = PreviewRequest(state.widthPx, state.heightPx, state.device.density, showChrome, state.night)
    val lpBackend = backend as? LayoutPreviewBackend
    var result by remember { mutableStateOf<LayoutPreviewResult?>(null) }
    LaunchedEffect(path, text, request, lpBackend) {
        result = lpBackend?.layoutPreview(path, text, request)
    }

    val fontResolver = LocalFontFamilyResolver.current
    val measurer = remember(fontResolver) { TextMeasurer(fontResolver, Density(1f), LayoutDirection.Ltr) }
    val gfx = remember(measurer) { ComposeGraphics(measurer) }

    val r = result
    if (lpBackend == null || r == null) {
        Box(modifier.fillMaxSize().background(Ca.colors.editorBg), contentAlignment = Alignment.Center) {
            Text(
                if (lpBackend == null) "Layout preview isn't available for this project" else "No layout preview for this file",
                color = Ca.colors.textTertiary, style = Ca.type.footnote,
            )
        }
        return
    }

    val resources = remember(r) { UiPreviewResources(r.resources, r.imageFile) }
    val engine = remember(r) { PreviewEngine(SimpleRenderContext(gfx, resources, state.device.density, state.device.density)) }
    val accent = Ca.colors.accent
    val surfaceColor = Ca.colors.surface
    val separatorColor = Ca.colors.separator

    PreviewSurface(
        modifier = modifier,
        state = state,
        cardColor = if (blueprint) BlueprintGround else Color.White,
        cardBorderColor = if (blueprint) BlueprintLine.copy(alpha = 0.4f) else Ca.colors.separator,
        blueprint = blueprint,
        onSurfaceTap = { selected = null },
        topBarExtras = {
            Divider()
            PillButton({ treeOpen = !treeOpen }) {
                Icon(CaIcons.panelRight, "Component tree", Modifier.size(16.dp), tint = if (treeOpen) accent else Ca.colors.textSecondary)
            }
            PillButton({ blueprint = !blueprint }) {
                Icon(CaIcons.box, "Blueprint", Modifier.size(16.dp), tint = if (blueprint) accent else Ca.colors.textSecondary)
            }
        },
        bottomBarExtras = {
            Divider()
            PillButton({ showChrome = !showChrome }) {
                Icon(CaIcons.eye, "Toggle system UI", Modifier.size(16.dp), tint = if (showChrome) accent else Ca.colors.textTertiary)
            }
        },
        overlays = {
            // Render problems (top-start) — the shared tappable chip both Preview views use.
            PreviewProblemChip(
                issues = remember(r) { r.problems.map { PreviewIssue(PreviewIssueLevel.WARNING, "<${it.tag.substringAfterLast('.')}>", it.message) } },
                modifier = Modifier.align(Alignment.TopStart).padding(Ca.spacing.s3),
            )

            // Component tree (start edge): selecting a node drives the canvas highlight + the inspector.
            if (treeOpen) {
                ComponentTreePanel(
                    root = r.root,
                    selected = selected,
                    onSelect = { selected = it },
                    surfaceColor = surfaceColor,
                    separatorColor = separatorColor,
                    accent = accent,
                    modifier = Modifier.align(Alignment.CenterStart).padding(Ca.spacing.s3),
                    onClose = { treeOpen = false },
                )
            }

            // Inspector (top-end), when a node is selected.
            selected?.let { node ->
                InspectorPanel(
                    node = node, density = state.device.density,
                    modifier = Modifier.align(Alignment.TopEnd).padding(Ca.spacing.s3),
                    onClose = { selected = null },
                )
            }
        },
    ) { widthPx, heightPx, density ->
        Box(
            Modifier.fillMaxSize().pointerInput(r) {
                detectTapGestures(
                    onTap = { p -> selected = engine.hitTest(r.root, p.x, p.y) },
                    onDoubleTap = { if (state.userScale <= 0f) state.userScale = 1f else { state.userScale = 0f; state.offset = Offset.Zero } },
                )
            },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                if (blueprint) {
                    // Wireframe: every node as a thin cyan outline — no real fills (the LayoutLib "blueprint"
                    // idiom). The engine still measures/lays out via render() into a throwaway pass so bounds
                    // are populated; we then stroke the tree ourselves.
                    engine.render(r.root, widthPx, heightPx, NullRCanvas)
                    drawWireframe(r.root, BlueprintLine, density)
                } else {
                    engine.render(r.root, widthPx, heightPx, ComposeRCanvas(this, measurer))
                }
                selected?.let { n -> drawSelection(n, accent, surfaceColor, density) }
            }
        }
    }
}

/**
 * The component-tree panel — the inflated render hierarchy flattened to indented rows. Selecting a row
 * highlights the node on the canvas and opens its attributes (matching the design's layout-editor tree).
 */
@Composable
private fun ComponentTreePanel(
    root: RenderNode,
    selected: RenderNode?,
    onSelect: (RenderNode) -> Unit,
    surfaceColor: Color,
    separatorColor: Color,
    accent: Color,
    modifier: Modifier,
    onClose: () -> Unit,
) {
    val rows = remember(root) { flattenTree(root) }
    Column(
        modifier.widthIn(min = 180.dp, max = 240.dp)
            .shadow(10.dp, RoundedCornerShape(Ca.radius.md))
            .clip(RoundedCornerShape(Ca.radius.md)).background(surfaceColor)
            .border(1.dp, separatorColor, RoundedCornerShape(Ca.radius.md)),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = Ca.spacing.s3, end = Ca.spacing.s1, top = Ca.spacing.s1, bottom = Ca.spacing.s1), verticalAlignment = Alignment.CenterVertically) {
            Text("Component tree", color = Ca.colors.textTertiary, style = Ca.type.caption, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            PillButton(onClose) { Icon(CaIcons.close, "Close", Modifier.size(14.dp), tint = Ca.colors.textTertiary) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(separatorColor))
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(vertical = Ca.spacing.s1)) {
            for ((node, depth) in rows) {
                val on = node === selected
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onSelect(node) }
                        .background(if (on) accent.copy(alpha = 0.14f) else Color.Transparent)
                        .padding(start = (8 + depth * 14).dp, end = Ca.spacing.s2, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
                ) {
                    Icon(
                        if (node.children.isNotEmpty()) CaIcons.layers else CaIcons.dot,
                        null, Modifier.size(14.dp),
                        tint = if (on) accent else Ca.colors.textSecondary,
                    )
                    Text(
                        node.tag.substringAfterLast('.').ifEmpty { "View" },
                        color = if (on) accent else Ca.colors.textPrimary,
                        style = Ca.type.caption, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                    node.props.id?.let {
                        Text("@$it", color = Ca.colors.textTertiary, style = Ca.type.caption2, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Pre-order flatten of the render tree to (node, depth) rows for the component tree. */
private fun flattenTree(
    node: RenderNode,
    depth: Int = 0,
    out: MutableList<Pair<RenderNode, Int>> = ArrayList(),
): List<Pair<RenderNode, Int>> {
    out.add(node to depth)
    for (child in node.children) flattenTree(child, depth + 1, out)
    return out
}

/** Stroke every node's bounds as a thin line — the blueprint wireframe (bounds are absolute device px). */
private fun DrawScope.drawWireframe(node: RenderNode, color: Color, density: Float) {
    drawRect(
        color = color,
        topLeft = Offset(node.left.toFloat(), node.top.toFloat()),
        size = Size(node.width.toFloat(), node.height.toFloat()),
        style = Stroke(width = 1f * density),
    )
    for (child in node.children) drawWireframe(child, color, density)
}

/** The selection chrome: an accent outline plus the design's four corner handles. */
private fun DrawScope.drawSelection(n: RenderNode, accent: Color, handleFill: Color, density: Float) {
    val l = n.left.toFloat(); val t = n.top.toFloat(); val rr = n.right.toFloat(); val b = n.bottom.toFloat()
    drawRect(accent, Offset(l, t), Size(n.width.toFloat(), n.height.toFloat()), style = Stroke(width = 1.5f * density))
    val hs = 7f * density
    for ((cx, cy) in listOf(l to t, rr to t, l to b, rr to b)) {
        val tl = Offset(cx - hs / 2f, cy - hs / 2f)
        val sz = Size(hs, hs)
        drawRect(handleFill, tl, sz)
        drawRect(accent, tl, sz, style = Stroke(width = 1.5f * density))
    }
}

/** A no-op canvas used to run measure/layout in blueprint mode without painting real fills. */
private object NullRCanvas : RCanvas {
    override fun save(): Int = 0
    override fun restore() {}
    override fun translate(dx: Float, dy: Float) {}
    override fun clipRect(l: Float, t: Float, r: Float, b: Float) {}
    override fun drawRect(l: Float, t: Float, r: Float, b: Float, paint: RPaint) {}
    override fun drawRoundRect(l: Float, t: Float, r: Float, b: Float, rx: Float, ry: Float, paint: RPaint) {}
    override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: RPaint) {}
    override fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, paint: RPaint) {}
    override fun drawPath(path: RPath, paint: RPaint) {}
    override fun drawImage(img: RImage, l: Float, t: Float, r: Float, b: Float, tintArgb: Int?) {}
    override fun drawText(text: CharSequence, x: Float, y: Float, paint: RPaint) {}
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
