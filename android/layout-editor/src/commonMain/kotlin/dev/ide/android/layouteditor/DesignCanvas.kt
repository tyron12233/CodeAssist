package dev.ide.android.layouteditor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChanged
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
import kotlinx.coroutines.delay
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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

/** How the canvas settles after a programmatic zoom or pan: quick, with no bounce past the target. */
private val CanvasSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
private val CanvasOffsetSpring = spring<Offset>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

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
    /** Whether the selection chip sits under the top bar, which the visible area then starts below. */
    chipShown: Boolean,
    /** Whether the floating action bar shows; false where the property strip carries the actions instead. */
    showActionBar: Boolean,
    /** Whether an element holds children, which decides where a drag can drop. */
    isViewGroup: (LayoutElement) -> Boolean,
    /** A drag (from the palette, or a long-pressed view here) was released. */
    onDrop: (DragPayload, DropTarget?) -> Unit,
    modifier: Modifier,
    /** The pane has no chrome around the card (a narrow Split): fit it to the whole pane. */
    compact: Boolean = false,
) {
    val density = LocalDensity.current
    val obscuredPx = with(density) { obscuredBottom.toPx() }
    val image = remember(render) { render?.image?.toImageBitmap() }
    val tree = render?.root
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val cardW = with(density) { state.wdp.dp.toPx() }
    val cardH = with(density) { state.hdp.dp.toPx() }
    // Leave room for the top bar and the bottom toolbar when fitting; compact has neither.
    val reservedV = with(density) { (if (compact) 16.dp else 150.dp).toPx() }
    val fit = if (canvasSize == IntSize.Zero) 1f else min(
        canvasSize.width * 0.9f / cardW,
        (canvasSize.height - reservedV).coerceAtLeast(canvasSize.height * 0.4f) / cardH,
    ).coerceIn(0.05f, 4f)
    val scale = if (state.zoom <= 0f) fit else state.zoom
    state.fitScale = fit
    val fitNow by rememberUpdatedState(fit)
    // The tree's bounds are in the screen's pixels; the image may have been drawn smaller than the screen.
    val frameW = render?.widthPx?.takeIf { it > 0 } ?: state.widthPx
    val k = cardW / frameW.coerceAtLeast(1)

    // Ask the renderer for about the pixels the card shows on screen, once a gesture settles; render again at
    // once when the card is now shown sharper than the frame was drawn (zooming in), not when zooming out.
    LaunchedEffect(scale, cardW, state.widthPx, state.gesturing, render) {
        if (state.gesturing || canvasSize == IntSize.Zero) return@LaunchedEffect
        val needed = DesignerState.quantizeRenderScale(cardW * scale / state.widthPx.coerceAtLeast(1))
        state.renderScale = needed
        val r = render ?: return@LaunchedEffect
        val img = r.image ?: return@LaunchedEffect
        val drawn = img.width.toFloat() / (r.widthPx.takeIf { it > 0 } ?: img.width)
        if (needed > drawn + 0.01f) state.sharpenTick++
    }

    // What is drawn trails the target transform: a gesture moves it exactly, anything else springs to it. The
    // first placement (before the canvas has a size there is nothing to fit to) snaps rather than zooming in.
    val shownScale = remember { Animatable(scale) }
    val shownPan = remember { Animatable(state.pan, Offset.VectorConverter) }
    var placed by remember { mutableStateOf(false) }
    val sized = canvasSize != IntSize.Zero
    LaunchedEffect(scale, sized) {
        if (state.gesturing || !placed) shownScale.snapTo(scale)
        else shownScale.animateTo(scale, CanvasSpring)
        if (sized) placed = true
    }
    LaunchedEffect(state.pan) {
        if (state.gesturing || !placed) shownPan.snapTo(state.pan)
        else shownPan.animateTo(state.pan, CanvasOffsetSpring)
    }

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
    // The part of the canvas the selection can be seen in: under the top bar and the selection chip, above
    // whatever covers the bottom (the toolbar row, and the strip or sheet on a phone). The action bar and the
    // auto-pan below both use it, so the bar is never placed where the pan does not show.
    val visibleTop = with(density) { (if (compact) 8.dp else if (chipShown) 112.dp else 68.dp).toPx() }
    val visibleBottom = canvasSize.height - obscuredPx - with(density) { if (compact || obscuredPx > 0f) 12.dp.toPx() else 84.dp.toPx() }
    LaunchedEffect(selectedNode, (obscuredPx / 16f).toInt(), canvasSize) {
        if (obscuredPx <= 0f) {
            if (state.sheetPan != 0f) {
                state.pan = state.pan.copy(y = state.pan.y - state.sheetPan)
                state.sheetPan = 0f
            }
            return@LaunchedEffect
        }
        // The strip grows and shrinks animated, which relaunches this; act once it has settled.
        delay(220)
        val node = selectedNode ?: return@LaunchedEffect
        if (canvasSize == IntSize.Zero) return@LaunchedEffect
        val r = node.rect(k)
        val top = canvasSize.height / 2f + state.pan.y + (r.top - cardH / 2f) * scale
        val bottom = canvasSize.height / 2f + state.pan.y + (r.bottom - cardH / 2f) * scale
        val barRoom = with(density) { (if (showActionBar) 64.dp else 16.dp).toPx() }
        if (top >= visibleTop && bottom + barRoom <= visibleBottom) return@LaunchedEffect
        // Put the view just under the chips with room for its action bar beneath; a view too tall for that is
        // centred instead.
        val delta = if (bottom - top + barRoom <= visibleBottom - visibleTop) visibleTop + with(density) { 8.dp.toPx() } - top
        else (visibleTop + visibleBottom) / 2f - (top + bottom) / 2f
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
    val currentOnDrop by rememberUpdatedState(onDrop)

    // Drag and drop: the canvas resolves a root-coordinate point to a drop target in the render.
    var canvasOrigin by remember { mutableStateOf(Offset.Zero) }
    fun cardPointOf(root: Offset): Offset {
        val local = root - canvasOrigin
        val sc = shownScale.value.coerceAtLeast(0.01f)
        return Offset(
            (local.x - canvasSize.width / 2f - shownPan.value.x) / sc + cardW / 2f,
            (local.y - canvasSize.height / 2f - shownPan.value.y) / sc + cardH / 2f,
        )
    }
    fun rootPointOf(card: Offset): Offset = canvasOrigin + Offset(
        canvasSize.width / 2f + shownPan.value.x + (card.x - cardW / 2f) * shownScale.value,
        canvasSize.height / 2f + shownPan.value.y + (card.y - cardH / 2f) * shownScale.value,
    )
    val drag = state.drag
    drag.canvasTarget = { root ->
        val local = root - canvasOrigin
        val t = tree
        if (t == null || local.x < 0 || local.y < 0 || local.x > canvasSize.width || local.y > canvasSize.height) null
        else {
            val c = cardPointOf(root)
            val moving = (drag.payload as? DragPayload.Move)?.element
            dropTargetAt(t, document, c.x / k, c.y / k, isViewGroup, moving)
        }
    }
    val hover: DropTarget? = drag.payload?.let { drag.canvasTarget?.invoke(drag.position) }

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { canvasSize = it }
            .onGloballyPositioned { canvasOrigin = it.positionInRoot() }
            .drawBehind {
                if (state.blueprint) drawRect(BlueprintGround.copy(alpha = 0.92f))
                else drawDots(dot, 16.dp.toPx(), 1.dp.toPx())
            }
            .pointerInput(state) {
                // Pan and pinch, marked as a gesture for its whole length so the canvas tracks the fingers instead
                // of animating after them. Nothing moves until past touch slop, so a tap still reaches the card.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var engaged = false
                    var travel = Offset.Zero
                    try {
                        do {
                            val event = awaitPointerEvent()
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (!engaged) {
                                travel += pan
                                if (zoom != 1f || travel.getDistance() > viewConfiguration.touchSlop) {
                                    engaged = true
                                    state.gesturing = true
                                }
                            }
                            if (engaged && (zoom != 1f || pan != Offset.Zero)) {
                                val base = if (state.zoom <= 0f) fitNow else state.zoom
                                state.zoom = (base * zoom).coerceIn(0.1f, 6f)
                                state.pan += pan
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        state.gesturing = false
                    }
                }
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { currentOnSelect(null) }) },
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(18.dp)
        Box(
            Modifier
                .graphicsLayer {
                    val sc = shownScale.value
                    val p = shownPan.value
                    scaleX = sc; scaleY = sc
                    translationX = p.x; translationY = p.y
                }
                .requiredSize(state.wdp.dp, state.hdp.dp)
                .shadow(if (state.blueprint) 0.dp else 18.dp, shape)
                .clip(shape)
                .background(if (state.blueprint) BlueprintGround else Color.White)
                .border(1.dp, if (state.blueprint) BlueprintLine.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant, shape)
                .pointerInput(drag) {
                    // A long press on a view picks it up to move; the drag then follows the finger to its drop.
                    detectDragGesturesAfterLongPress(
                        onDragStart = { p ->
                            val t = currentTree ?: return@detectDragGesturesAfterLongPress
                            val hit = elementAtPoint(t, currentDoc, p.x / currentK, p.y / currentK)
                            if (hit != null && hit.parent != null) {
                                currentOnSelect(hit)
                                drag.position = rootPointOf(p)
                                drag.payload = DragPayload.Move(hit)
                            }
                        },
                        onDrag = { change, amount ->
                            if (drag.payload != null) {
                                change.consume()
                                // The card is scaled; a finger's travel in it is travel times the scale on screen.
                                drag.position += amount * shownScale.value
                            }
                        },
                        onDragEnd = {
                            val payload = drag.payload ?: return@detectDragGesturesAfterLongPress
                            val target = drag.targetAt(drag.position)
                            drag.payload = null
                            currentOnDrop(payload, target)
                        },
                        onDragCancel = { drag.payload = null },
                    )
                }
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
                selectedNode?.let { if (drag.payload == null) drawSelection(it.rect(k), accent, handleFill, 1.dp.toPx()) }
                hover?.let { t -> drawDropTarget(t, k, accent) }
            }
            if (document.root != null && document.root?.children?.isEmpty() == true) {
                EmptyLayoutHint(document.root!!, Modifier.fillMaxSize().padding(12.dp))
            }
        }

        // The action bar: canvas pixels, constant size, under the selection (above it when there is no room).
        val node = selectedNode
        if (showActionBar && drag.payload == null && node != null && selected != null && canvasSize != IntSize.Zero) {
            val r = node.rect(k)
            // Placed from the transform as drawn, so the bar travels with the card while it animates.
            val sc = shownScale.value
            val sp = shownPan.value
            fun toCanvas(p: Offset) = Offset(
                canvasSize.width / 2f + sp.x + (p.x - cardW / 2f) * sc,
                canvasSize.height / 2f + sp.y + (p.y - cardH / 2f) * sc,
            )
            val bottom = toCanvas(Offset(r.center.x, r.bottom))
            val top = toCanvas(Offset(r.center.x, r.top))
            val barH = with(density) { 48.dp.toPx() }
            val gap = with(density) { 10.dp.toPx() }
            // Under the view when it fits, else over it; a view too big for either (the root) gets the bar at the
            // bottom of the visible area, clear of the top bar, the chip under it, and the bottom toolbar.
            val minY = visibleTop
            val maxY = visibleBottom - barH
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

/** Where a drag would land: the receiving container washed and outlined, and the insertion line for a list. */
private fun DrawScope.drawDropTarget(t: DropTarget, k: Float, accent: Color) {
    t.area?.let { a ->
        val r = a.rect(k)
        drawRect(accent.copy(alpha = 0.08f), r.topLeft, r.size)
        drawRect(accent, r.topLeft, r.size, style = Stroke(2.dp.toPx()))
        val line = t.line
        if (line != null) {
            val w = 3.dp.toPx()
            drawLine(accent, Offset(line[0] * k, line[1] * k), Offset(line[2] * k, line[3] * k), strokeWidth = w)
            drawCircle(accent, 4.dp.toPx(), Offset(line[0] * k, line[1] * k))
            drawCircle(accent, 4.dp.toPx(), Offset(line[2] * k, line[3] * k))
        } else {
            // A free or constraint layout: mark the point it will be pinned at.
            val c = Offset((a.left + t.x) * k, (a.top + t.y) * k)
            val arm = 8.dp.toPx()
            drawLine(accent, c - Offset(arm, 0f), c + Offset(arm, 0f), strokeWidth = 2.dp.toPx())
            drawLine(accent, c - Offset(0f, arm), c + Offset(0f, arm), strokeWidth = 2.dp.toPx())
        }
    }
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
