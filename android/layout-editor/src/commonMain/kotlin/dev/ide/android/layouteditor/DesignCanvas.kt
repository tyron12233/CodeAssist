package dev.ide.android.layouteditor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.ide.android.layout.LayoutDocument
import dev.ide.android.layout.LayoutElement
import dev.ide.android.layout.LayoutRenderResult
import dev.ide.android.layout.LayoutViewNode
import dev.ide.android.layouteditor.generated.resources.Res
import dev.ide.android.layouteditor.generated.resources.le_empty_hint
import org.jetbrains.compose.resources.stringResource
import kotlin.math.min
import kotlin.math.roundToInt

/** Blueprint ground + line, fixed rather than themed: a blueprint reads as one on any app theme. */
private val BlueprintGround = Color(0xFF0F2A44)
private val BlueprintLine = Color(0xFF7FC7E8)

/**
 * The canvas: the rendered screen on a dotted ground, pannable and zoomable, with the selection drawn over it
 * and its action bar floating beside it.
 *
 * Coordinates: the render is an image in the device's pixels; the card shows it at the device's size in dp,
 * scaled by the zoom. A node's bounds become card pixels through [k] (card px per image px), and card pixels
 * become canvas pixels through the zoom and pan around the card's centre (the graphics layer's origin), which
 * is how the action bar is placed outside the scaled card at a constant size.
 */
@Composable
internal fun DesignCanvas(
    state: DesignerState,
    document: LayoutDocument,
    render: LayoutRenderResult?,
    selected: LayoutElement?,
    onSelect: (LayoutElement?) -> Unit,
    actions: DesignActions,
    onOpenAttributes: () -> Unit,
    obscuredBottom: Dp,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val obscuredPx = with(density) { obscuredBottom.toPx() }
    val image = remember(render) { render?.image?.toImageBitmap() }
    val tree = render?.root
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val cardW = with(density) { state.wdp.dp.toPx() }
    val cardH = with(density) { state.hdp.dp.toPx() }
    // Leave room for the top bar and the bottom toolbar when fitting.
    val reservedV = with(density) { 150.dp.toPx() }
    val fit = if (canvasSize == IntSize.Zero) 1f else min(
        canvasSize.width * 0.9f / cardW,
        (canvasSize.height - reservedV).coerceAtLeast(canvasSize.height * 0.4f) / cardH,
    ).coerceIn(0.05f, 4f)
    val scale = if (state.zoom <= 0f) fit else state.zoom
    val fitNow by rememberUpdatedState(fit)
    val imageW = render?.image?.width ?: state.widthPx
    val k = cardW / imageW.coerceAtLeast(1)

    val accent = MaterialTheme.colorScheme.primary
    val dot = MaterialTheme.colorScheme.outlineVariant
    val handleFill = MaterialTheme.colorScheme.surface
    val selectedNode = remember(tree, selected) { if (tree != null && selected != null) nodeFor(tree, selected) else null }
    val parentNode = remember(tree, selected) {
        val p = selected?.parent
        if (tree != null && p != null) nodeFor(tree, p) else null
    }
    // Keep the selection in view above a sheet: pan just enough to centre it in what is left, and give the pan
    // back when the sheet closes, so opening and closing the sheet leaves the canvas where the user had it.
    LaunchedEffect(selectedNode, obscuredPx > 0f, canvasSize) {
        if (obscuredPx <= 0f) {
            if (state.sheetPan != 0f) {
                state.pan = state.pan.copy(y = state.pan.y - state.sheetPan)
                state.sheetPan = 0f
            }
            return@LaunchedEffect
        }
        val node = selectedNode ?: return@LaunchedEffect
        if (canvasSize == IntSize.Zero) return@LaunchedEffect
        val r = node.rect(k)
        val top = canvasSize.height / 2f + state.pan.y + (r.top - cardH / 2f) * scale
        val bottom = canvasSize.height / 2f + state.pan.y + (r.bottom - cardH / 2f) * scale
        val margin = with(density) { 72.dp.toPx() }
        val visibleBottom = canvasSize.height - obscuredPx - with(density) { 64.dp.toPx() }
        if (top >= margin && bottom <= visibleBottom) return@LaunchedEffect
        val delta = (margin + visibleBottom) / 2f - (top + bottom) / 2f
        state.pan = state.pan.copy(y = state.pan.y + delta)
        state.sheetPan += delta
    }
    // The gesture handlers below are installed once; read everything they use through updated state.
    val currentDoc by rememberUpdatedState(document)
    val currentTree by rememberUpdatedState(tree)
    val currentSelected by rememberUpdatedState(selected)
    val currentK by rememberUpdatedState(k)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnOpenAttributes by rememberUpdatedState(onOpenAttributes)

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { canvasSize = it }
            .drawBehind {
                if (state.blueprint) drawRect(BlueprintGround.copy(alpha = 0.92f))
                else drawDots(dot, 16.dp.toPx(), 1.dp.toPx())
            }
            .pointerInput(state) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val base = if (state.zoom <= 0f) fitNow else state.zoom
                    state.zoom = (base * zoom).coerceIn(0.1f, 6f)
                    state.pan += pan
                }
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { currentOnSelect(null) }) },
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(18.dp)
        Box(
            Modifier
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = state.pan.x; translationY = state.pan.y
                }
                .requiredSize(state.wdp.dp, state.hdp.dp)
                .shadow(if (state.blueprint) 0.dp else 18.dp, shape)
                .clip(shape)
                .background(if (state.blueprint) BlueprintGround else Color.White)
                .border(1.dp, if (state.blueprint) BlueprintLine.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant, shape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { p ->
                            val t = currentTree ?: return@detectTapGestures
                            val hit = elementAtPoint(t, currentDoc, p.x / currentK, p.y / currentK)
                            // A second tap on the selected view opens its attributes.
                            if (hit != null && hit === currentSelected) currentOnOpenAttributes() else currentOnSelect(hit)
                        },
                        onDoubleTap = { state.resetView() },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                if (state.blueprint) {
                    tree?.let { drawBlueprint(it, k) }
                } else if (image != null) {
                    drawImage(
                        image,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                }
                parentNode?.let { drawDashed(it.rect(k), accent.copy(alpha = 0.55f), 1.dp.toPx()) }
                selectedNode?.let { drawSelection(it.rect(k), accent, handleFill, 1.dp.toPx()) }
            }
            if (document.root != null && document.root?.children?.isEmpty() == true) {
                EmptyLayoutHint(document.root!!, Modifier.fillMaxSize().padding(12.dp))
            }
        }

        // The action bar: canvas pixels, constant size, under the selection (above it when there is no room).
        val node = selectedNode
        if (node != null && selected != null && canvasSize != IntSize.Zero) {
            val r = node.rect(k)
            fun toCanvas(p: Offset) = Offset(
                canvasSize.width / 2f + state.pan.x + (p.x - cardW / 2f) * scale,
                canvasSize.height / 2f + state.pan.y + (p.y - cardH / 2f) * scale,
            )
            val bottom = toCanvas(Offset(r.center.x, r.bottom))
            val top = toCanvas(Offset(r.center.x, r.top))
            val barH = with(density) { 48.dp.toPx() }
            val gap = with(density) { 10.dp.toPx() }
            // Under the view when it fits, else over it; a view too big for either (the root) gets the bar at the
            // bottom of the visible area, clear of the top bar, the chip under it, and the bottom toolbar.
            val minY = with(density) { 108.dp.toPx() }
            val maxY = canvasSize.height - obscuredPx - barH - with(density) { 84.dp.toPx() }
            val anchorY = when {
                bottom.y + gap <= maxY -> bottom.y + gap
                top.y - gap - barH >= minY -> top.y - gap - barH
                else -> maxY.coerceAtLeast(minY)
            }
            SelectionActionBar(
                element = selected,
                actions = actions,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(0, anchorY.roundToInt()) }
                    .centeredAt(bottom.x, canvasSize.width),
            )
        }
    }
}

/** Centre a child horizontally on [x] within [containerWidth], kept inside it. */
private fun Modifier.centeredAt(x: Float, containerWidth: Int): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minWidth = 0))
    layout(p.width, p.height) {
        val left = (x - p.width / 2f).roundToInt().coerceIn(0, (containerWidth - p.width).coerceAtLeast(0))
        p.place(left, 0)
    }
}

private fun LayoutViewNode.rect(k: Float) = Rect(left * k, top * k, right * k, bottom * k)

private fun DrawScope.drawDots(color: Color, step: Float, radius: Float) {
    var y = step / 2
    while (y < size.height) {
        var x = step / 2
        while (x < size.width) {
            drawCircle(color, radius, Offset(x, y))
            x += step
        }
        y += step
    }
}

private fun DrawScope.drawBlueprint(node: LayoutViewNode, k: Float) {
    val r = node.rect(k)
    drawRect(BlueprintLine.copy(alpha = if (node.sourceOffset != null) 0.9f else 0.35f), r.topLeft, r.size, style = Stroke(1.dp.toPx()))
    for (c in node.children) drawBlueprint(c, k)
}

private fun DrawScope.drawDashed(r: Rect, color: Color, w: Float) {
    drawRect(color, r.topLeft, r.size, style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
}

private fun DrawScope.drawSelection(r: Rect, accent: Color, fill: Color, w: Float) {
    drawRect(accent.copy(alpha = 0.08f), r.topLeft, r.size)
    drawRect(accent, r.topLeft, r.size, style = Stroke(1.5f * w))
    val hs = 7.dp.toPx()
    for (c in listOf(r.topLeft, Offset(r.right, r.top), Offset(r.left, r.bottom), Offset(r.right, r.bottom))) {
        val tl = Offset(c.x - hs / 2, c.y - hs / 2)
        drawRoundRect(fill, tl, Size(hs, hs), CornerRadius(hs / 2))
        drawRoundRect(accent, tl, Size(hs, hs), CornerRadius(hs / 2), style = Stroke(1.5f * w))
    }
}

/** An empty root: say what it is and how to fill it, inside a dashed drop area. */
@Composable
private fun EmptyLayoutHint(root: LayoutElement, modifier: Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier.drawBehind {
            drawRoundRect(
                outline.copy(alpha = 0.6f),
                cornerRadius = CornerRadius(12.dp.toPx()),
                style = Stroke(1.5f.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))),
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Symbol(Sym.addToPhotos, null, size = 28.dp, tint = Color(0xFF6B7280))
            Text(root.simpleName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Color(0xFF374151))
            Text(stringResource(Res.string.le_empty_hint), style = MaterialTheme.typography.bodySmall, color = Color(0xFF6B7280))
        }
    }
}
