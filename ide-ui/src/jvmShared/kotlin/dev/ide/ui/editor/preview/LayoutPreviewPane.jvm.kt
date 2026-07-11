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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ide.preview.LayoutPreviewBackend
import dev.ide.preview.LayoutPreviewResult
import dev.ide.preview.PreviewEngine
import dev.ide.preview.PreviewRequest
import dev.ide.preview.PreviewViewNode
import dev.ide.preview.RCanvas
import dev.ide.preview.RImage
import dev.ide.preview.RPaint
import dev.ide.preview.RPath
import dev.ide.preview.RenderNode
import dev.ide.preview.SimpleRenderContext
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.UiLayoutElement
import dev.ide.ui.backend.UiTextEdit
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.core.RangeEdit
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.TextRange
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * The layout Preview view: renders the layout on a [PreviewSurface] device card — the same chrome (device /
 * orientation / night / pan-zoom) as the Compose preview — plus layout-only extras: a component tree, a
 * blueprint wireframe, tap-to-select with an attribute inspector, the system-UI toggle, and a render-problem
 * chip. The inflated tree is drawn through the owned `PreviewEngine` over a Compose-backed `RCanvas`.
 */
/** Debounce before fetching a layout render, so a fast typing burst coalesces into one render request. */
private const val PREVIEW_DEBOUNCE_MS = 200L

/** Floating render-pipeline status chip (spinner + stage label), shown top-right while a real-view render
 *  runs — the "Merging resources" / "Linking resources" / "Rendering" stages from the engine. */
@Composable
private fun PreviewStatusChip(stage: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Ca.colors.surface.copy(alpha = 0.92f))
            .border(1.dp, Ca.colors.separator, RoundedCornerShape(50))
            .padding(horizontal = Ca.spacing.s3, vertical = Ca.spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
    ) {
        CircularProgressIndicator(Modifier.size(13.dp), color = Ca.colors.accent, strokeWidth = 2.dp)
        Text(stage, style = Ca.type.footnote, color = Ca.colors.textSecondary)
    }
}

@Composable
actual fun LayoutPreviewPane(path: String, text: String, backend: IdeBackend, session: EditorSession, modifier: Modifier) {
    val state = rememberPreviewSurfaceState(path)
    val scope = rememberCoroutineScope()
    var showChrome by remember { mutableStateOf(true) }
    var blueprint by remember { mutableStateOf(false) }
    // Real-view ("layoutlib-on-device") is the default renderer; owned rendering is the fallback (and the
    // desktop path, where no real-view runtime is wired). The toggle stays as an escape hatch.
    var realViews by remember { mutableStateOf(true) }
    var treeOpen by remember { mutableStateOf(false) }
    // Owned-render selection (a RenderNode) vs. real-view selection (a captured PreviewViewNode) — only one is
    // in play per result (the real-view path carries `viewTree`, the owned path carries `root`).
    var selected by remember(path) { mutableStateOf<RenderNode?>(null) }
    var selectedView by remember(path) { mutableStateOf<PreviewViewNode?>(null) }
    // The selection's stable identity, so it survives a re-render (the tree is rebuilt from new text after an
    // edit): re-resolved by id when the view has one, else by its pre-order child-index path.
    var selKey by remember(path) { mutableStateOf<SelKey?>(null) }
    // The editable attribute model for the selected view + the text it was fetched from (so edits are computed
    // against a consistent buffer; the sheet is gated stale until they realign after an edit).
    var element by remember(path) { mutableStateOf<UiLayoutElement?>(null) }
    var elementText by remember(path) { mutableStateOf("") }

    // Apply the attribute editor's edits to the shared session — the same surgical write-back the block editor
    // uses, so the Code view + preview both update. Descending order is handled by applyEdits.
    fun applyEdits(edits: List<UiTextEdit>) {
        if (edits.isEmpty()) return
        val ranges = edits.map { RangeEdit(it.start, it.end, it.newText, it.start + it.newText.length) }
        session.applyEdits(ranges, TextRange(edits.minOf { it.start }.coerceAtLeast(0)))
    }

    val request = PreviewRequest(state.widthPx, state.heightPx, state.device.density, showChrome, state.night, realViews = realViews)
    val lpBackend = backend as? LayoutPreviewBackend
    var result by remember { mutableStateOf<LayoutPreviewResult?>(null) }
    // Re-fetch when dependency resolution settles: a re-resolve changes the module classpath the real-view
    // render dexes against, so the preview must re-render rather than keep a stale (pre-resolve) result.
    val depsResolving = backend.deps.depsState.collectAsState().value.resolving
    // Live render-pipeline stage (relink → render) for the floating status chip; null when idle.
    val renderProgress = backend.preview.previewProgress.collectAsState().value
    // Build status — so a "prepare libraries" (dex) build that finishes flips the readiness gate: re-fetch when
    // the status changes (notably Running → Succeeded), at which point the libraries are dexed and the real
    // render proceeds instead of the build-required prompt.
    val buildStatus = backend.build.buildState.collectAsState().value.status
    LaunchedEffect(path, text, request, lpBackend, depsResolving, buildStatus) {
        // The effect re-launches (cancelling the prior) on every keystroke; this delay coalesces a typing
        // burst into a single render. The fetch runs on the backend's preview lane, off the UI thread.
        delay(PREVIEW_DEBOUNCE_MS)
        result = lpBackend?.layoutPreview(path, text, request)
    }

    val fontResolver = LocalFontFamilyResolver.current
    val measurer = remember(fontResolver) { TextMeasurer(fontResolver, Density(1f), LayoutDirection.Ltr) }
    val gfx = remember(measurer) { ComposeGraphics(measurer) }

    val r = result
    if (lpBackend == null || r == null) {
        Box(modifier.fillMaxSize().background(Ca.colors.editorBg)) {
            Text(
                if (lpBackend == null) "Layout preview isn't available for this project" else "No layout preview for this file",
                color = Ca.colors.textTertiary, style = Ca.type.footnote,
                modifier = Modifier.align(Alignment.Center),
            )
            // First render (no prior result yet): still show the pipeline status chip.
            renderProgress?.let { PreviewStatusChip(it.stage, Modifier.align(Alignment.TopEnd).padding(Ca.spacing.s3)) }
        }
        return
    }

    val resources = remember(r) { UiPreviewResources(r.resources, r.imageFile) }
    val engine = remember(r) { PreviewEngine(SimpleRenderContext(gfx, resources, state.device.density, state.device.density)) }
    // Real-view ("layoutlib-on-device") render, when the backend produced one: the live native Bitmap (device,
    // no PNG round-trip) if present, else PNG bytes (portable form / desktop).
    val realImage = remember(r.renderedNativeImage, r.renderedImage) {
        r.renderedNativeImage?.let { nativeImageToBitmap(it) } ?: r.renderedImage?.let { decodeImageBytes(it) }
    }
    val accent = Ca.colors.accent
    val surfaceColor = Ca.colors.surface
    val separatorColor = Ca.colors.separator

    // Re-resolve the real-view selection against the freshly-rendered tree (a new object every render, e.g.
    // after an attribute edit) by its stable [SelKey], so the attribute editor stays open on the same view.
    LaunchedEffect(r.viewTree) {
        val vt = r.viewTree
        if (vt != null) selectedView = selKey?.let { nodeByKey(vt, it) }
    }
    // Fetch the editable model for the selected view. Keyed on text so it realigns after every edit; the sheet's
    // controls stay disabled (its per-element `busy`) between a commit and the fresh model arriving.
    val selOffset = selectedView?.sourceOffset
    val selId = selectedView?.id
    LaunchedEffect(selOffset, selId, text) {
        element = if (selOffset != null) backend.preview.layoutElementAt(path, text, selOffset, selId) else null
        elementText = text
    }

    Box(modifier.fillMaxSize()) {
    PreviewSurface(
        modifier = Modifier.fillMaxSize(),
        state = state,
        cardColor = if (blueprint) BlueprintGround else Color.White,
        cardBorderColor = if (blueprint) BlueprintLine.copy(alpha = 0.4f) else Ca.colors.separator,
        blueprint = blueprint,
        // Deselect on a background tap — but NOT while the editable sheet is open, so a tap that reaches the
        // surface (e.g. leaking past the sheet) can't dismiss it. Close it with its own button, or tap another
        // view (the card's own tap-select). Tapping to deselect stays live for the read-only/owned paths.
        onSurfaceTap = {
            val sheetOpen = selectedView?.sourceOffset != null && element != null
            if (!sheetOpen) { selected = null; selectedView = null; selKey = null }
        },
        topBarExtras = {
            Divider()
            PillButton({ treeOpen = !treeOpen }) {
                Icon(CaIcons.panelRight, "Component tree", Modifier.size(16.dp), tint = if (treeOpen) accent else Ca.colors.textSecondary)
            }
            PillButton({ blueprint = !blueprint }) {
                Icon(CaIcons.box, "Blueprint", Modifier.size(16.dp), tint = if (blueprint) accent else Ca.colors.textSecondary)
            }
            PillButton({ realViews = !realViews }) {
                Icon(CaIcons.androidLogo, "Real views (beta)", Modifier.size(16.dp), tint = if (realViews) accent else Ca.colors.textSecondary)
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

            // Render-pipeline status (top-end): a floating spinner + stage label while a real-view render runs.
            renderProgress?.let { PreviewStatusChip(it.stage, Modifier.align(Alignment.TopEnd).padding(Ca.spacing.s3)) }

            // Component tree (start edge): selecting a node drives the canvas highlight + the inspector. The
            // real-view path shows the REAL inflated hierarchy (`viewTree`); the owned path shows `root`.
            val vt = r.viewTree
            if (treeOpen) {
                if (vt != null) {
                    RealViewTreePanel(
                        root = vt, selected = selectedView, onSelect = { selectedView = it },
                        surfaceColor = surfaceColor, separatorColor = separatorColor, accent = accent,
                        modifier = Modifier.align(Alignment.CenterStart).padding(Ca.spacing.s3),
                        onClose = { treeOpen = false },
                    )
                } else {
                    ComponentTreePanel(
                        root = r.root, selected = selected, onSelect = { selected = it },
                        surfaceColor = surfaceColor, separatorColor = separatorColor, accent = accent,
                        modifier = Modifier.align(Alignment.CenterStart).padding(Ca.spacing.s3),
                        onClose = { treeOpen = false },
                    )
                }
            }

            // Read-only inspector, for a selected node that ISN'T an editable element (window decor /
            // synthesized internals), and the owned path. The EDITABLE sheet is rendered OUTSIDE PreviewSurface
            // (as a top-level sibling below) so its taps aren't intercepted by the surface's subcompose/overlay
            // stack — see the `LayoutAttributeSheet` call after this PreviewSurface.
            val node = selectedView
            when {
                vt != null && node != null && node.sourceOffset == null ->
                    RealViewInspectorPanel(
                        node = node,
                        modifier = Modifier.align(Alignment.TopEnd).padding(Ca.spacing.s3),
                        onClose = { selectedView = null; selKey = null },
                    )

                vt == null -> selected?.let { n ->
                    InspectorPanel(
                        node = n, density = state.device.density,
                        modifier = Modifier.align(Alignment.TopEnd).padding(Ca.spacing.s3),
                        onClose = { selected = null },
                    )
                }
            }
        },
    ) { widthPx, heightPx, density ->
        Box(
            Modifier.fillMaxSize().pointerInput(r) {
                detectTapGestures(
                    // Tap = select. Real-view path hit-tests the captured hierarchy (bounds line up with the
                    // rendered bitmap); owned path hit-tests the render tree.
                    onTap = { p ->
                        val vt = r.viewTree
                        if (vt != null) {
                            val hit = vt.hitTest(p.x, p.y)
                            // Selecting a different view clears the stale model so the sheet never shows another
                            // view's attributes while the new one is fetched.
                            if (hit !== selectedView) element = null
                            selectedView = hit
                            selKey = hit?.let { SelKey(it.id, pathTo(vt, it) ?: emptyList()) }
                        } else selected = engine.hitTest(r.root, p.x, p.y)
                    },
                    onDoubleTap = { if (state.userScale <= 0f) state.userScale = 1f else { state.userScale = 0f; state.offset = Offset.Zero } },
                )
            },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val img = realImage
                when {
                    // Real-view path: paint the device-rendered PNG 1:1 (it's already device px from the origin).
                    img != null -> drawImage(img)
                    blueprint -> {
                        // Wireframe: every node as a thin cyan outline — no real fills (the LayoutLib "blueprint"
                        // idiom). The engine still measures/lays out via render() into a throwaway pass so bounds
                        // are populated; we then stroke the tree ourselves.
                        engine.render(r.root, widthPx, heightPx, NullRCanvas)
                        drawWireframe(r.root, BlueprintLine, density)
                    }
                    else -> engine.render(r.root, widthPx, heightPx, ComposeRCanvas(this, measurer))
                }
                // Selection chrome: the owned tree over the owned render, or the captured hierarchy over the
                // real-view bitmap (both in device px from the origin, so they overlay 1:1).
                if (img == null) selected?.let { n -> drawSelectionBox(n.left.toFloat(), n.top.toFloat(), n.right.toFloat(), n.bottom.toFloat(), accent, surfaceColor, density) }
                else selectedView?.let { n -> drawSelectionBox(n.left.toFloat(), n.top.toFloat(), n.right.toFloat(), n.bottom.toFloat(), accent, surfaceColor, density) }
            }
        }
    }

        // Build-required prompt: the real-view preview no longer dexes libraries itself. When they aren't
        // prepared yet (fresh project, or a newly-added dependency), the backend returns `buildRequired` (with an
        // owned fallback render behind) — show an Android-Studio-style one-time "prepare libraries" panel + a dex
        // build button. When the build finishes, `buildStatus` changes → the effect re-fetches → the gate passes.
        if (r.buildRequired) {
            BuildRequiredPanel(
                undexedCount = r.undexedCount,
                running = buildStatus == RunStatus.Running,
                onPrepare = {
                    val module = backend.files.moduleNameForFile(path)
                    if (module != null) {
                        val variant = backend.build.activeVariant(module) ?: "debug"
                        backend.build.runTask("prepareDex:$module:$variant")
                    }
                },
                modifier = Modifier.align(Alignment.Center).padding(Ca.spacing.s4),
            )
        }

        // The EDITABLE attribute sheet — a TOP-LEVEL sibling of PreviewSurface (not one of its overlays), so its
        // controls' taps are hit-tested cleanly on top of the whole surface with nothing intercepting them. NOT
        // gated on `r.viewTree` (the render result): the sheet model comes from parsing the buffer, so an edit
        // that breaks the render (empty/invalid value) must NOT drop the sheet — it stays open so the user can
        // fix the value. `selectedView` is only ever set on the real-view path and retains its value when a later
        // render fails (its re-resolve only runs on a non-null tree).
        val sheetNode = selectedView
        val sheetEl = element
        if (sheetNode != null && sheetNode.sourceOffset != null && sheetEl != null) {
            LayoutAttributeSheet(
                element = sheetEl, node = sheetNode, path = path, text = text, backend = backend,
                onEdit = { applyEdits(it) },
                onClose = { selectedView = null; selKey = null; element = null },
            )
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

/** The selection chrome: an accent outline plus the design's four corner handles, over the given bounds
 *  (device px). Shared by the owned-render ([RenderNode]) and real-view ([PreviewViewNode]) selection. */
private fun DrawScope.drawSelectionBox(l: Float, t: Float, rr: Float, b: Float, accent: Color, handleFill: Color, density: Float) {
    drawRect(accent, Offset(l, t), Size(rr - l, b - t), style = Stroke(width = 1.5f * density))
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

/**
 * The real-view (device "layoutlib") hierarchy panel — the actual inflated [PreviewViewNode] tree flattened to
 * indented rows. Mirrors [ComponentTreePanel] but over the captured hierarchy (which includes the window decor
 * chrome the real framework builds around the layout), so selecting a row highlights the view on the rendered
 * bitmap and opens its attributes.
 */
@Composable
private fun RealViewTreePanel(
    root: PreviewViewNode,
    selected: PreviewViewNode?,
    onSelect: (PreviewViewNode) -> Unit,
    surfaceColor: Color,
    separatorColor: Color,
    accent: Color,
    modifier: Modifier,
    onClose: () -> Unit,
) {
    val rows = remember(root) { flattenViewTree(root) }
    Column(
        modifier.widthIn(min = 190.dp, max = 260.dp)
            .shadow(10.dp, RoundedCornerShape(Ca.radius.md))
            .clip(RoundedCornerShape(Ca.radius.md)).background(surfaceColor)
            .border(1.dp, separatorColor, RoundedCornerShape(Ca.radius.md)),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = Ca.spacing.s3, end = Ca.spacing.s1, top = Ca.spacing.s1, bottom = Ca.spacing.s1), verticalAlignment = Alignment.CenterVertically) {
            Text("View hierarchy", color = Ca.colors.textTertiary, style = Ca.type.caption, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            PillButton(onClose) { Icon(CaIcons.close, "Close", Modifier.size(14.dp), tint = Ca.colors.textTertiary) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(separatorColor))
        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()).padding(vertical = Ca.spacing.s1)) {
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
                        node.simpleName.ifEmpty { "View" },
                        color = if (on) accent else Ca.colors.textPrimary,
                        style = Ca.type.caption, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                    node.id?.let {
                        Text("@id/$it", color = Ca.colors.textTertiary, style = Ca.type.caption2, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Pre-order flatten of a captured [PreviewViewNode] tree to (node, depth) rows for the hierarchy panel. */
private fun flattenViewTree(
    node: PreviewViewNode,
    depth: Int = 0,
    out: MutableList<Pair<PreviewViewNode, Int>> = ArrayList(),
): List<Pair<PreviewViewNode, Int>> {
    out.add(node to depth)
    for (child in node.children) flattenViewTree(child, depth + 1, out)
    return out
}

/**
 * The real-view attribute inspector: the selected view's class + id, then its captured attributes bucketed by
 * group (Layout / Appearance / Text / …). Read-only for now — a faithful "what the framework actually inflated"
 * view of the node, distinct from the owned-render [InspectorPanel].
 */
@Composable
private fun RealViewInspectorPanel(node: PreviewViewNode, modifier: Modifier, onClose: () -> Unit) {
    val groups = remember(node) { node.properties.groupBy { it.group } }
    Column(
        modifier.widthIn(min = 220.dp, max = 300.dp)
            .shadow(10.dp, RoundedCornerShape(Ca.radius.md))
            .clip(RoundedCornerShape(Ca.radius.md)).background(Ca.colors.surface)
            .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.md)),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = Ca.spacing.s3, end = Ca.spacing.s1, top = Ca.spacing.s1, bottom = Ca.spacing.s1), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(node.simpleName.ifEmpty { "View" }, color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(node.className, color = Ca.colors.textTertiary, style = Ca.type.caption2, maxLines = 1)
            }
            PillButton(onClose) { Icon(CaIcons.close, "Close", Modifier.size(14.dp), tint = Ca.colors.textTertiary) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(Ca.spacing.s3), verticalArrangement = Arrangement.spacedBy(Ca.spacing.s2)) {
            node.id?.let { InspectorAttrRow("id", "@id/$it") }
            for ((group, props) in groups) {
                Text(group.uppercase(), color = Ca.colors.textTertiary, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = Ca.spacing.s1))
                for (p in props) InspectorAttrRow(p.name, p.value)
            }
        }
    }
}

/** One attribute row (name / value) in the real-view inspector. */
@Composable
private fun InspectorAttrRow(name: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2)) {
        Text(name, color = Ca.colors.textTertiary, style = Ca.type.caption, modifier = Modifier.width(96.dp), maxLines = 1)
        Text(value, color = Ca.colors.textPrimary, style = Ca.type.caption, modifier = Modifier.weight(1f))
    }
}

/** The stable identity of a selected view, so the selection survives a re-render (a new tree object): the
 *  `@id` entry name when it has one (re-resolved by id), else the pre-order child-index path from the root. */
internal data class SelKey(val id: String?, val path: List<Int>)

/** The pre-order child-index path from [root] to [target] (empty = root itself), or null if not found. */
private fun pathTo(root: PreviewViewNode, target: PreviewViewNode): List<Int>? {
    if (root === target) return emptyList()
    root.children.forEachIndexed { i, c -> pathTo(c, target)?.let { return listOf(i) + it } }
    return null
}

/** Re-find the node for [key] in [root] — by id (pre-order) when set, else by walking its child-index path. */
private fun nodeByKey(root: PreviewViewNode, key: SelKey): PreviewViewNode? {
    if (key.id != null) findViewById(root, key.id)?.let { return it }
    var node: PreviewViewNode? = root
    for (i in key.path) node = node?.children?.getOrNull(i)
    return node
}

private fun findViewById(root: PreviewViewNode, id: String): PreviewViewNode? {
    if (root.id == id) return root
    for (c in root.children) findViewById(c, id)?.let { return it }
    return null
}

/**
 * The one-time "prepare libraries" prompt shown when the real-view preview's libraries aren't dexed yet
 * (Android-Studio-style). Explains WHY (real libraries must be dexed once; fast afterward) and HOW MANY
 * libraries need it, and offers a dex-only build (`prepareDex:` — no APK packaging). While the build runs it
 * shows a spinner; when it finishes the preview re-checks readiness and renders.
 */
@Composable
private fun BuildRequiredPanel(undexedCount: Int, running: Boolean, onPrepare: () -> Unit, modifier: Modifier) {
    Column(
        modifier.widthIn(max = 380.dp)
            .shadow(16.dp, RoundedCornerShape(Ca.radius.lg))
            .clip(RoundedCornerShape(Ca.radius.lg))
            .background(Ca.colors.surface)
            .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.lg))
            .padding(Ca.spacing.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Ca.spacing.s3),
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(Ca.radius.md)).background(Ca.colors.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Icon(CaIcons.hammer, null, Modifier.size(24.dp), tint = Ca.colors.accent) }

        Text("Prepare the preview", style = Ca.type.headline, color = Ca.colors.textPrimary)
        Text(
            buildString {
                append("The real-view preview renders with your project's real libraries, which have to be prepared (dexed) once. ")
                append("It's a one-time step per library set — after it, editing and previewing are fast. ")
                if (undexedCount > 0) append("$undexedCount ${if (undexedCount == 1) "library needs" else "libraries need"} preparing.")
            },
            style = Ca.type.footnote, color = Ca.colors.textSecondary, textAlign = TextAlign.Center,
        )

        Row(
            Modifier.clip(RoundedCornerShape(Ca.radius.md))
                .background(if (running) Ca.colors.surface2 else Ca.colors.accent)
                .clickable(enabled = !running) { onPrepare() }
                .padding(horizontal = Ca.spacing.s4, vertical = Ca.spacing.s3),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
        ) {
            if (running) {
                CircularProgressIndicator(Modifier.size(15.dp), color = Ca.colors.accent, strokeWidth = 2.dp)
                Text("Preparing libraries…", color = Ca.colors.textSecondary, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold)
            } else {
                Icon(CaIcons.hammer, null, Modifier.size(16.dp), tint = Color.White)
                Text("Prepare libraries", color = Color.White, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold)
            }
        }
        Text(
            "Runs a build up to dexing — it doesn't package an APK.",
            style = Ca.type.caption2, color = Ca.colors.textTertiary, textAlign = TextAlign.Center,
        )
    }
}
