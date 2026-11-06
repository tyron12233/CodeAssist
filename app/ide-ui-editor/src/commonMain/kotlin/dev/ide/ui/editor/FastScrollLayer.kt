package dev.ide.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiFileSymbol
import dev.ide.ui.editor.core.EditorSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

/**
 * The outline scrubber: a fast-scroll thumb on the right edge of a long file. It shows while the code scrolls and
 * fades ~1s after; ticks along its track mark where the file's classes and functions are. Dragging the thumb jumps
 * through the file, and a bubble beside it names the declaration the view has reached
 * (`MainActivity › onCreate · 120`), so a long file can be crossed by name instead of by flinging.
 *
 * Only the thumb takes touches (its own hit box, moved in the layout phase), so the code beside the track keeps its
 * taps; the track and ticks are drawn without input. Scroll-dependent geometry is read in layout and draw, so a
 * fling repaints this layer without recomposing it.
 */
@Composable
internal fun BoxScope.FastScrollLayer(
    session: EditorSession,
    geometry: EditorGeometry,
    thumbColor: Color,
    trackColor: Color,
    classTickColor: Color,
    memberTickColor: Color,
) {
    val maxV = geometry.maxV()
    val viewportHeight = geometry.viewport.value.height.toFloat()
    // Only for a file several screens long: on a short one the scrubber would be a slow way to do a small scroll.
    if (viewportHeight <= 0f || maxV < viewportHeight * 1.5f) return

    var dragging by remember { mutableStateOf(false) }
    var recentlyScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(geometry) {
        var first = true
        snapshotFlow { geometry.vOffset.floatValue }.collectLatest {
            if (first) { first = false; return@collectLatest } // not on open: only once the user scrolls
            recentlyScrolled = true
            delay(FastScrollLingerMs)
            recentlyScrolled = false
        }
    }
    val shown = dragging || recentlyScrolled
    val appear by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 120 else 260), label = "fastScroll")
    if (appear <= 0f && !shown) return

    val density = LocalDensity.current
    val thumbHeightPx = with(density) { ThumbHeight.toPx() }
    val trackHeight = remember { mutableFloatStateOf(0f) }
    val structure = geometry.editorStructure.value

    // Track + ticks: drawn, never hit-tested.
    Spacer(
        Modifier
            .align(Alignment.TopEnd)
            .fillMaxHeight()
            .padding(top = TrackInsetTop, bottom = TrackInsetBottom)
            .width(TrackWidth)
            .onSizeChanged { trackHeight.floatValue = it.height.toFloat() }
            .drawBehind {
                val a = appear
                val trackX = size.width - RailInset.toPx() - RailWidth.toPx()
                drawRoundRect(
                    trackColor.copy(alpha = trackColor.alpha * a * 0.6f), Offset(trackX, 0f),
                    Size(RailWidth.toPx(), size.height), CornerRadius(RailWidth.toPx() / 2f),
                )
                // Declarations along the track, where they fall in the file: classes long, members short.
                val doc = session.doc
                val rows = geometry.vlayout.totalRows.coerceAtLeast(1).toFloat()
                for (item in structure) {
                    if (item.depth > 1) continue
                    val line = doc.lineForOffset(item.nameOffset.coerceIn(0, doc.length))
                    val y = geometry.vlayout.topRow(line) / rows * size.height
                    val isType = item.kind in TYPE_KINDS
                    val w = (if (isType) 12.dp else 7.dp).toPx()
                    drawRect(
                        (if (isType) classTickColor else memberTickColor).copy(alpha = a * (if (isType) 0.9f else 0.6f)),
                        Offset(trackX + RailWidth.toPx() - w, y), Size(w, 1.5.dp.toPx()),
                    )
                }
                // The thumb.
                val top = fastScrollThumbTop(size.height, thumbHeightPx, geometry.vOffset.floatValue, geometry.maxV())
                val thumbW = (if (dragging) ThumbWidthDragging else ThumbWidth).toPx()
                drawRoundRect(
                    thumbColor.copy(alpha = thumbColor.alpha * a), Offset(size.width - RailInset.toPx() - thumbW, top),
                    Size(thumbW, thumbHeightPx), CornerRadius(thumbW / 2f),
                )
            },
    )

    // The thumb's hit box, a little wider and taller than what is drawn, following the thumb in the layout phase.
    if (shown) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = TrackInsetTop)
                .offset {
                    IntOffset(0, fastScrollThumbTop(trackHeight.floatValue, thumbHeightPx, geometry.vOffset.floatValue, geometry.maxV()).roundToInt())
                }
                .width(TrackWidth)
                .height(ThumbHeight)
                .pointerInput(geometry) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        dragging = true
                        // Moved by the finger's travel, not to the finger, so the thumb keeps where it was grabbed.
                        var thumbTop = fastScrollThumbTop(trackHeight.floatValue, thumbHeightPx, geometry.vOffset.floatValue, geometry.maxV())
                        try {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                // The box moves with the thumb, so the finger's position is relative to where the
                                // thumb was when this event's layout ran: add the movement since then.
                                thumbTop += change.position.y - change.previousPosition.y
                                thumbTop = thumbTop.coerceIn(0f, (trackHeight.floatValue - thumbHeightPx).coerceAtLeast(0f))
                                geometry.vOffset.floatValue =
                                    fastScrollOffsetFor(thumbTop, trackHeight.floatValue, thumbHeightPx, geometry.maxV())
                            }
                        } finally {
                            dragging = false
                        }
                    }
                },
        )
    }

    // The bubble naming where the drag has reached.
    AnimatedVisibility(
        visible = dragging,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = TrackInsetTop, end = TrackWidth + 4.dp)
            .offset {
                val top = fastScrollThumbTop(trackHeight.floatValue, thumbHeightPx, geometry.vOffset.floatValue, geometry.maxV())
                IntOffset(0, (top + thumbHeightPx / 2f - BubbleHeight.toPx() / 2f).roundToInt().coerceAtLeast(0))
            },
        enter = fadeIn(tween(120)) + slideInHorizontally(tween(160)) { it / 4 },
        exit = fadeOut(tween(160)) + slideOutHorizontally(tween(160)) { it / 4 },
    ) {
        val doc = session.doc
        // The line at the top of the view, just under the (up to two) sticky headers that cover it. Reading the
        // scroll here recomposes the bubble per drag step, and only while it shows.
        val topLine = geometry.lineAtY(geometry.render.metrics.lineHeight * 2.5f)
        val chain = enclosingDeclarations(structure, doc.lineStart(topLine)) { doc.lineStart(doc.lineForOffset(it.coerceIn(0, doc.length))) }
        FastScrollBubble(chain, topLine + 1)
    }
}

@Composable
private fun FastScrollBubble(chain: List<UiFileSymbol>, line: Int) {
    Row(
        Modifier
            .height(BubbleHeight)
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.inverseSurface)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val ink = MaterialTheme.colorScheme.inverseOnSurface
        Column(Modifier.weight(1f, fill = false)) {
            val inner = chain.lastOrNull()
            if (chain.size > 1) {
                Text(
                    chain.dropLast(1).joinToString(" › ") { it.name },
                    color = ink.copy(alpha = 0.65f), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                inner?.name ?: "",
                color = ink, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text("$line", color = ink.copy(alpha = 0.65f), style = MaterialTheme.typography.labelMedium)
    }
}

/** Where the thumb's top sits: its travel maps the whole scroll range. */
internal fun fastScrollThumbTop(trackPx: Float, thumbPx: Float, scrollPx: Float, maxScrollPx: Float): Float {
    val travel = (trackPx - thumbPx).coerceAtLeast(0f)
    if (travel <= 0f || maxScrollPx <= 0f) return 0f
    return travel * (scrollPx / maxScrollPx).coerceIn(0f, 1f)
}

/** The scroll offset for a thumb whose top is at [thumbTop] (the inverse of [fastScrollThumbTop]). */
internal fun fastScrollOffsetFor(thumbTop: Float, trackPx: Float, thumbPx: Float, maxScrollPx: Float): Float {
    val travel = (trackPx - thumbPx).coerceAtLeast(0f)
    if (travel <= 0f) return 0f
    return (thumbTop / travel).coerceIn(0f, 1f) * maxScrollPx
}

/**
 * The declarations enclosing [offset], outermost first (empty outside any). A declaration counts from the start of
 * its name's line ([lineStartOf]), so the `fun` keyword and modifiers before the name are inside it.
 */
internal fun enclosingDeclarations(structure: List<UiFileSymbol>, offset: Int, lineStartOf: (Int) -> Int): List<UiFileSymbol> =
    structure.filter { offset >= lineStartOf(it.nameOffset) && offset <= it.endOffset }.sortedBy { it.depth }

private val TYPE_KINDS = setOf("class", "interface", "enum", "object", "record", "annotation")

private const val FastScrollLingerMs = 1200L
private val TrackWidth = 28.dp
private val TrackInsetTop = 6.dp
private val TrackInsetBottom = 18.dp
private val RailWidth = 3.dp
private val RailInset = 4.dp
private val ThumbHeight = 44.dp
private val ThumbWidth = 4.dp
private val ThumbWidthDragging = 7.dp
private val BubbleHeight = 44.dp
