package dev.ide.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.components.CodeMotifColors
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.back
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.platform.PlatformBackHandler
import dev.ide.ui.theme.Symbol
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Fullscreen screenshot viewer: one screenshot per page, as large as the screen allows, swiped between,
 * pinched or double-tapped to zoom.
 *
 * It replaced a viewer that drew every screenshot 340 dp tall whatever the screen, could only be stepped
 * with its buttons, and could not zoom, so a phone screenshot's text stayed as unreadable as it was in the
 * carousel. The buttons and dots stay, so the viewer can still be driven without a gesture.
 *
 * Swiping is off while the page is zoomed: a drag then pans the image, and swiping to the next screenshot
 * mid-pan would throw away the place the reader zoomed to. A page goes back to fit once it is swiped away.
 *
 * Fills the fixed dark editor ground rather than the theme's surface: what it shows is a screenshot of a
 * running app or a code panel, and both were composed against dark.
 */
@Composable
internal fun ScreenshotLightbox(
    backend: IdeBackend,
    item: UiStoreItem,
    resolved: List<String>,
    index: Int,
    onClose: () -> Unit,
    onIndex: (Int) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val shots: List<String?> = when {
        resolved.isNotEmpty() -> resolved
        hasSamplePreview(item.previewKey) -> listOf(item.previewKey!!)
        // Remote shots that have not been fetched yet: hold their places so the pager does not change
        // length under the reader when the images land.
        item.screenshots.isNotEmpty() -> List(item.screenshots.size) { null }
        else -> listOf(null, null)
    }
    val pager = rememberPagerState(initialPage = index.coerceIn(0, shots.lastIndex)) { shots.size }
    val scope = rememberCoroutineScope()
    // The page currently zoomed in, or -1. Swiping is suspended while it is the page on screen.
    var zoomedPage by remember { mutableIntStateOf(-1) }
    LaunchedEffect(pager.currentPage) { onIndex(pager.currentPage) }
    PlatformBackHandler(enabled = true, onBack = onClose)

    Box(
        Modifier.fillMaxSize().background(CodeMotifColors.Chrome)
            // Swallow taps so the content behind the overlay cannot be reached.
            .clickable(enabled = true, onClick = {}),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightboxButton(CaSymbols.close, stringResource(Res.string.back), Color.White.copy(alpha = 0.1f), Color.White, onClose)
                Text(
                    "${pager.currentPage + 1} / ${shots.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(46.dp))
            }
            HorizontalPager(
                state = pager,
                userScrollEnabled = zoomedPage != pager.currentPage,
                beyondViewportPageCount = 1,
                pageSpacing = 16.dp,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { page ->
                ZoomablePage(
                    current = page == pager.currentPage,
                    onZoomed = { zoomed ->
                        zoomedPage = if (zoomed) page else if (zoomedPage == page) -1 else zoomedPage
                    },
                ) {
                    val key = shots[page]
                    val fill = Modifier.fillMaxSize()
                    when {
                        key == null -> Box(fill, contentAlignment = Alignment.Center) { CodePanel(item, page) }
                        hasSamplePreview(key) -> SamplePreview(key, fill)
                        // Fit, not crop: the viewer is where the whole screenshot has to be legible.
                        else -> ShotImage(backend, key, ContentScale.Fit, fill)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                LightboxButton(CaSymbols.chevronLeft, null, Color.White.copy(alpha = 0.1f), Color.White) {
                    scope.launch { pager.animateScrollToPage((pager.currentPage - 1 + shots.size) % shots.size) }
                }
                Row(
                    Modifier.padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    shots.indices.forEach { i ->
                        // The active dot stretches into a bar rather than changing colour alone.
                        val width by animateDpAsState(if (i == pager.currentPage) 22.dp else 7.dp, label = "lightboxDot")
                        Box(
                            Modifier.width(width).height(7.dp).clip(CircleShape)
                                .background(if (i == pager.currentPage) Color.White else Color.White.copy(alpha = 0.35f)),
                        )
                    }
                }
                LightboxButton(CaSymbols.chevronRight, null, c.primaryContainer, c.onPrimaryContainer) {
                    scope.launch { pager.animateScrollToPage((pager.currentPage + 1) % shots.size) }
                }
            }
        }
    }
}

/** The most a screenshot can be zoomed: enough to read a phone screenshot's smallest text. */
private const val MAX_ZOOM = 4f

/** The zoom a double tap goes to. */
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * One page that can be zoomed and panned.
 *
 * A one-finger drag at fit is left alone (not consumed) so the pager underneath can swipe; it is only
 * claimed once a second finger is down or the page is already zoomed. Scroll-wheel zoom is for the
 * desktop IDE, where there is no pinch.
 */
@Composable
private fun ZoomablePage(
    current: Boolean,
    onZoomed: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    /** Zoom to [to], keeping the point under [focus] where it is. */
    fun zoomAround(focus: Offset, to: Float, pan: Offset = Offset.Zero) {
        val target = to.coerceIn(1f, MAX_ZOOM)
        val center = Offset(size.width / 2f, size.height / 2f)
        val fromCenter = focus - center
        offset = clamp(fromCenter - (fromCenter - offset) * (target / scale) + pan, target)
        scale = target
        if (scale <= 1.01f) { scale = 1f; offset = Offset.Zero }
        onZoomed(scale > 1f)
    }

    // Back to fit once this page is no longer the one on screen.
    LaunchedEffect(current) {
        if (!current && scale != 1f) { scale = 1f; offset = Offset.Zero; onZoomed(false) }
    }

    Box(
        Modifier.fillMaxSize().clipToBounds()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1f) zoomAround(tap, 1f) else zoomAround(tap, DOUBLE_TAP_ZOOM)
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || scale > 1f) {
                            zoomAround(
                                focus = event.calculateCentroid(useCurrent = false).takeIf { it != Offset.Unspecified }
                                    ?: Offset(size.width / 2f, size.height / 2f),
                                to = scale * event.calculateZoom(),
                                pan = event.calculatePan(),
                            )
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            val focus = event.changes.firstOrNull()?.position ?: Offset(size.width / 2f, size.height / 2f)
                            if (dy != 0f) {
                                zoomAround(focus, scale * (1f - dy * 0.12f))
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun LightboxButton(glyph: Char, description: String?, container: Color, content: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(CircleShape).background(container).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(glyph, contentDescription = description, size = 22.dp, tint = content)
    }
}
