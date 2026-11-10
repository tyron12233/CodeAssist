package dev.ide.android.layouteditor

import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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

    // ---- From the last frame to this one, when a designer edit made it (see Transition.kt) ------------------
    val transition = state.transition?.takeIf { it.to === render }
    val fromImage = remember(transition) { transition?.from?.image?.toImageBitmap() }
    val motions = remember(transition) {
        val t = transition ?: return@remember emptyList()
        val oldRoot = t.from.root ?: return@remember emptyList()
        val newRoot = t.to.root ?: return@remember emptyList()
        val matched = HashMap(matchViews(oldRoot, newRoot, t.edits))
        // A dropped view grows out of its ghost; a moved one travels from where it was drawn.
        val fromGhost = HashSet<LayoutViewNode>()
        for (pin in t.pins) {
            val node = pin.to.resolve(document)?.let { nodeFor(newRoot, it) } ?: continue
            matched[node] = pin.from
            if (!pin.fromPicture) fromGhost += node
        }
        planMotion(newRoot, matched).map { if (it.node in fromGhost) Motion(it.node, it.from, it.scaled, fromPicture = false) else it }
    }
    val progress = remember(transition) { Animatable(if (transition == null) 1f else 0f) }
    LaunchedEffect(transition) {
        if (transition == null) return@LaunchedEffect
        progress.animateTo(1f, tween(TRANSITION_MS, easing = FastOutSlowInEasing))
        if (state.transition === transition) state.transition = null
    }
    val labels = rememberTextMeasurer()
    val tree = render?.root
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val cardW = with(density) { state.wdp.dp.toPx() }
    val cardH = with(density) { state.hdp.dp.toPx() }
    // The chrome is docked outside the canvas now: fit leaves a margin, and room for the selection's tab.
    val reservedV = with(density) { (if (compact) 16.dp else 40.dp).toPx() }
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
    // A view just dropped is selected before any frame has it; until one does, its ghost is where it is, and
    // mapping it into the old frame would outline whatever view used to start at its place in the text.
    val ghostSelected = state.ghost?.let { it.becomes == state.selection } == true
    val selectedNode = remember(tree, selected, ghostSelected) {
        if (tree != null && selected != null && !ghostSelected) nodeFor(tree, selected) else null
    }
    val parentNode = remember(tree, selected) {
        val p = selected?.parent
        if (tree != null && p != null) nodeFor(tree, p) else null
    }
    // Keep the selection in view above a sheet: pan just enough to centre it in what is left, and give the pan
    // back when the sheet closes, so opening and closing the sheet leaves the canvas where the user had it.
    // The part of the canvas the selection can be seen in: under the top bar and the selection chip, above
    // whatever covers the bottom (the toolbar row, and the strip or sheet on a phone). The action bar and the
    // auto-pan below both use it, so the bar is never placed where the pan does not show.
    val visibleTop = with(density) { (if (compact) 8.dp else 28.dp).toPx() }
    val visibleBottom = canvasSize.height - obscuredPx - with(density) { 12.dp.toPx() }
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
        val barRoom = with(density) { 16.dp.toPx() }
        if (top >= visibleTop && bottom + barRoom <= visibleBottom) return@LaunchedEffect
        // Move the canvas only as far as it takes to show the view (keeping as much of the layout around it in
        // sight as before); a view too tall for the space is centred in it.
        val delta = when {
            bottom - top + barRoom > visibleBottom - visibleTop -> (visibleTop + visibleBottom) / 2f - (top + bottom) / 2f
            bottom + barRoom > visibleBottom -> visibleBottom - barRoom - bottom
            else -> visibleTop - top
        }
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
                    val t = transition
                    val from = fromImage
                    val p = progress.value
                    if (t != null && from != null && p < 1f) {
                        drawTransition(from, t.from.widthPx, image, frameW, motions, p, k, accent)
                    } else {
                        drawImage(
                            image,
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                            filterQuality = FilterQuality.Medium,
                        )
                    }
                }
                state.ghost?.let { drawGhost(it, k, accent, labels) }
                parentNode?.let { drawDashed(it.rect(k), accent.copy(alpha = 0.55f), 1.dp.toPx()) }
                selectedNode?.let { if (drag.payload == null) drawSelection(it.rect(k), accent, handleFill, 1.dp.toPx()) }
                hover?.let { t -> drawDropTarget(t, k, accent) }
            }
            if (document.root != null && document.root?.children?.isEmpty() == true) {
                EmptyLayoutHint(document.root!!, Modifier.fillMaxSize().padding(12.dp))
            }
        }

        // The selection's tab: canvas pixels, constant size, on the outline's top edge (its bottom edge when the
        // view reaches the top of the canvas), so it never covers the views around the selection.
        val node = selectedNode
        if (showActionBar && drag.payload == null && node != null && selected != null && canvasSize != IntSize.Zero) {
            val r = node.rect(k)
            // Placed from the transform as drawn, so the tab travels with the card while it animates.
            val sc = shownScale.value
            val sp = shownPan.value
            fun toCanvas(p: Offset) = Offset(
                canvasSize.width / 2f + sp.x + (p.x - cardW / 2f) * sc,
                canvasSize.height / 2f + sp.y + (p.y - cardH / 2f) * sc,
            )
            val topLeft = toCanvas(Offset(r.left, r.top))
            val bottomLeft = toCanvas(Offset(r.left, r.bottom))
            val tabH = with(density) { SELECTION_TAB_HEIGHT.toPx() }
            val below = topLeft.y - tabH < 0f
            val y = if (below) bottomLeft.y else topLeft.y - tabH
            SelectionTab(
                element = selected,
                actions = actions,
                below = below,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(0, y.roundToInt()) }
                    .startingAt(topLeft.x, canvasSize.width),
            )
        }
    }
}

/** Place a child with its left edge at [x], kept inside [containerWidth]. */
private fun Modifier.startingAt(x: Float, containerWidth: Int): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minWidth = 0))
    layout(p.width, p.height) {
        p.place(x.roundToInt().coerceIn(0, (containerWidth - p.width).coerceAtLeast(0)), 0)
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

/** How long a designer edit's frame takes to arrive from the one before. */
private const val TRANSITION_MS = 240

/**
 * One step of a frame transition at [p] (0..1): the new frame with the old one fading out over it, and each
 * travelling view drawn on top as its piece of the pictures, moving from where it was to where it is and
 * dissolving from its old pixels into its new ones. Pictures may be drawn smaller than their frames (a render
 * scale), so pixel rectangles are scaled per picture.
 */
private fun DrawScope.drawTransition(
    from: ImageBitmap, fromFrameW: Int, to: ImageBitmap, toFrameW: Int,
    motions: List<Motion>, p: Float, k: Float, accent: Color,
) {
    val full = IntSize(size.width.roundToInt(), size.height.roundToInt())
    drawImage(to, dstSize = full, filterQuality = FilterQuality.Medium)
    drawImage(from, dstSize = full, alpha = 1f - p, filterQuality = FilterQuality.Medium)
    val sFrom = from.width.toFloat() / fromFrameW.coerceAtLeast(1)
    val sTo = to.width.toFloat() / toFrameW.coerceAtLeast(1)
    for (m in motions) {
        val target = m.node.frameRect()
        val at = lerp(m.from, target, p)
        val dstOffset = IntOffset((at.left * k).roundToInt(), (at.top * k).roundToInt())
        val dstSize = IntSize((at.width * k).roundToInt().coerceAtLeast(1), (at.height * k).roundToInt().coerceAtLeast(1))
        if (m.fromPicture) {
            piece(from, m.from, sFrom)?.let { (o, s) -> drawImage(from, o, s, dstOffset, dstSize, filterQuality = FilterQuality.Medium) }
            piece(to, target, sTo)?.let { (o, s) -> drawImage(to, o, s, dstOffset, dstSize, alpha = p, filterQuality = FilterQuality.Medium) }
        } else {
            // Out of a ghost: the ghost's box closes onto the view and fades, and the view comes in at its own size
            // (stretched to a ghost's typical size it would flash huge), gliding from the ghost's corner.
            drawRoundRect(accent.copy(alpha = 0.18f * (1f - p)), Offset(dstOffset.x.toFloat(), dstOffset.y.toFloat()),
                Size(dstSize.width.toFloat(), dstSize.height.toFloat()), CornerRadius(4.dp.toPx()))
            val own = IntSize((target.width * k).roundToInt().coerceAtLeast(1), (target.height * k).roundToInt().coerceAtLeast(1))
            piece(to, target, sTo)?.let { (o, s) -> drawImage(to, o, s, dstOffset, own, alpha = p, filterQuality = FilterQuality.Medium) }
        }
    }
}

/** [r] (frame pixels) as a pixel rectangle of [image] drawn at [scale] pixels per frame pixel, or null when empty. */
private fun piece(image: ImageBitmap, r: Rect, scale: Float): Pair<IntOffset, IntSize>? {
    val l = (r.left * scale).roundToInt().coerceIn(0, image.width)
    val t = (r.top * scale).roundToInt().coerceIn(0, image.height)
    val rr = (r.right * scale).roundToInt().coerceIn(l, image.width)
    val b = (r.bottom * scale).roundToInt().coerceIn(t, image.height)
    if (rr - l < 1 || b - t < 1) return null
    return IntOffset(l, t) to IntSize(rr - l, b - t)
}

/** A dropped view, where it goes, until the frame that has it lands: a tinted box named for the view. */
private fun DrawScope.drawGhost(g: Ghost, k: Float, accent: Color, labels: TextMeasurer) {
    val r = Rect(g.rect.left * k, g.rect.top * k, g.rect.right * k, g.rect.bottom * k)
    val corner = CornerRadius(4.dp.toPx())
    drawRoundRect(accent.copy(alpha = 0.18f), r.topLeft, r.size, corner)
    drawRoundRect(accent, r.topLeft, r.size, corner, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
    val label = labels.measure(g.tag.substringAfterLast('.'), TextStyle(color = accent, fontSize = 11.sp, fontWeight = FontWeight.Medium))
    if (label.size.width + 8.dp.toPx() <= r.width && label.size.height <= r.height) {
        drawText(label, topLeft = Offset(r.left + (r.width - label.size.width) / 2f, r.top + (r.height - label.size.height) / 2f))
    }
}
