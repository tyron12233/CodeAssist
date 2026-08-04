package dev.ide.ui.components

import dev.ide.ui.theme.Ide
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Motion
import kotlinx.coroutines.launch

/**
 * An iOS-style bottom sheet — the `Sheet` primitive from the design (primitives.jsx). The scrim
 * **fades** in/out (opacity, `base`/`soft`) while the sheet body **slides** up from below
 * (transform-only, `slow`/`quiet`) so its content is never gated behind opacity — visible even if
 * motion is disabled. Drag handle + glass-thick fill + a `sheet`-radius rounded top edge.
 *
 * On phone, the build console docks here (the compact reflow; the file navigator is the left
 * [PushDrawer], not a sheet).
 *
 * The sheet opens at [heightFraction] but is **draggable**: dragging the handle up expands it (up to
 * full screen), dragging down collapses it back to the resting height or — past it — dismisses. On
 * release it settles to the nearest of resting / full, snapping past the midpoint.
 *
 * @param heightFraction resting sheet height as a fraction of the screen (~0.7 navigator, ~0.6 console).
 * @param content laid out in a [ColumnScope] below the drag handle — use `Modifier.weight(1f)` to
 *   fill the remaining sheet height.
 */
@Composable
fun BottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    heightFraction: Float = 0.6f,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(Motion.BASE, easing = Motion.soft)),
            exit = fadeOut(tween(Motion.BASE, easing = Motion.soft)),
        ) {
            Scrim(onDismiss)
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val heightPx = constraints.maxHeight.toFloat()
            val scope = rememberCoroutineScope()
            // The live sheet height, as a fraction of the screen. Driven by the drag gesture and
            // animated on settle; reset to the resting height each time the sheet reopens.
            val fraction = remember { Animatable(heightFraction) }
            LaunchedEffect(visible) { if (visible) fraction.snapTo(heightFraction) }

            val dragState = rememberDraggableState { delta ->
                // delta > 0 is a downward drag → shrink the sheet; up → grow it. Floor below the
                // resting height so an over-drag down still reads as "dismiss" on release.
                val next = (fraction.value - delta / heightPx).coerceIn(0.2f, 1f)
                scope.launch { fraction.snapTo(next) }
            }
            val settle: suspend (Float) -> Unit = { velocity ->
                val midpoint = (heightFraction + 1f) / 2f
                when {
                    // Dragged (or flung) well below the resting height → close the sheet.
                    fraction.value < heightFraction * 0.7f || velocity > heightPx ->
                        onDismiss()
                    fraction.value >= midpoint || velocity < -heightPx ->
                        fraction.animateTo(1f, tween(Motion.BASE, easing = Motion.quiet))
                    else ->
                        fraction.animateTo(heightFraction, tween(Motion.BASE, easing = Motion.quiet))
                }
            }

            AnimatedVisibility(
                visible = visible,
                modifier = Modifier.align(Alignment.BottomCenter),
                // transform-only entrance: the body slides up from fully below its resting position.
                enter = slideInVertically(tween(Motion.SLOW, easing = Motion.quiet)) { it },
                exit = slideOutVertically(tween(Motion.SLOW, easing = Motion.quiet)) { it },
            ) {
                val shape = RoundedCornerShape(topStart = Ca.radius.sheet, topEnd = Ca.radius.sheet)
                Column(
                    modifier
                        .fillMaxWidth()
                        .fillMaxHeight(fraction.value)
                        .shadow(24.dp, shape, clip = false)
                        .background(Ide.colors.glassThick, shape)
                        .border(1.dp, Ide.colors.glassEdgeTop, shape)
                        // A tap on the sheet body shouldn't dismiss it; only a tap on the scrim above does.
                        // Kept outside the content inset below so the full glass surface (incl. the strip
                        // behind the nav bar) swallows taps, not just the inset content area.
                        .swallowTaps()
                        // Keep the sheet's content (drag handle + body) above the system navigation bar and
                        // the soft keyboard, while the glass surface itself still runs edge-to-edge to the
                        // bottom of the screen. Applied INSIDE the background so only the content is inset,
                        // not the fill. `union` takes the larger of the two per side, so an open keyboard and
                        // the nav bar never double-count. When the sheet is hosted inside the app root's
                        // consumed `safeDrawing` inset (the docked navigator/console) both insets read zero
                        // here, so this is a no-op there; it does the work for the sheets overlaid OUTSIDE
                        // that box (onboarding/consent/migration), whose bottom buttons used to slip under
                        // the nav bar. Scrollable content (e.g. the More sheet) handles the rest (issue #994).
                        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
                ) {
                    DragHandle(
                        Modifier.draggable(
                            state = dragState,
                            orientation = Orientation.Vertical,
                            onDragStopped = { velocity -> settle(velocity) },
                        ),
                    )
                    content()
                }
            }
        }
    }
}

/**
 * A glass popover that **drops from the top** — the command-palette transition (panels.jsx):
 * scrim fades, the body slides down from a small negative offset and scales from 0.97 about its top
 * edge (transform-only). Anchored top-center under [topPadding].
 *
 * **Keyboard-safe.** The body is height-capped to the space actually available (the app root consumes
 * the IME inset, so an open soft keyboard already shrinks this box; `imePadding()` is a belt-and-braces
 * no-op there but keeps it correct if ever hosted outside that box), and the top gap is clamped so the
 * dialog never gets pushed off-screen. When the space runs short the body is capped rather than letting a
 * `Column` cram its fields — pass [scrollableContent] = true for a plain (non-list) panel so the whole
 * card scrolls instead; content that already hosts its own scroll region (a `LazyColumn`) should leave it
 * false and simply scroll within the cap.
 */
@Composable
fun DropdownOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    topPadding: androidx.compose.ui.unit.Dp = 60.dp,
    scrollableContent: Boolean = false,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        // maxHeight already reflects the keyboard; clamp the top gap so a tall dialog stays on-screen with
        // the keyboard up, and cap the body to what's left (minus a 12dp breathing gap above the bottom).
        val available = maxHeight
        val effectiveTop = topPadding.coerceAtMost(available * 0.18f)
        val bodyMax = (available - effectiveTop - 12.dp).coerceAtLeast(160.dp)
        val scroll = rememberScrollState()
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(Motion.BASE, easing = Motion.soft)),
            exit = fadeOut(tween(Motion.BASE, easing = Motion.soft)),
        ) {
            Scrim(onDismiss)
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = effectiveTop),
            enter = slideInVertically(tween(Motion.BASE, easing = Motion.quiet)) { -it / 6 } +
                scaleIn(tween(Motion.BASE, easing = Motion.quiet), initialScale = 0.97f, transformOrigin = TopOrigin),
            exit = slideOutVertically(tween(Motion.FAST, easing = Motion.soft)) { -it / 6 } +
                scaleOut(tween(Motion.FAST, easing = Motion.soft), targetScale = 0.97f, transformOrigin = TopOrigin),
        ) {
            Box(
                Modifier
                    .heightIn(max = bodyMax)
                    .then(if (scrollableContent) Modifier.verticalScroll(scroll) else Modifier)
                    .swallowTaps(),
            ) { content() }
        }
    }
}

private val TopOrigin = TransformOrigin(0.5f, 0f)

/**
 * Swallow tap gestures so a click on the dialog body (its padding/background, not an interactive child)
 * doesn't fall through to the dismiss [Scrim] behind it — the dialog then closes only on a tap truly
 * *outside* it. Children still get the gesture first (their buttons/fields work), and non-tap gestures
 * (scroll/drag) pass through untouched.
 */
private fun Modifier.swallowTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }

/**
 * A centered modal dialog — scrim fade + a gentle scale/fade pop of the body about its center. The
 * desktop counterpart to [BottomSheet] for content that should read as a floating panel rather than a
 * docked sheet (e.g. onboarding on a wide window). [content] supplies its own card surface.
 */
@Composable
fun CenteredDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    // `imePadding()` keeps a centered dialog above the soft keyboard (recenters into the space left over)
    // — a no-op inside the app root's consumed IME inset, load-bearing for dialogs overlaid outside it.
    Box(Modifier.fillMaxSize().imePadding()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(Motion.BASE, easing = Motion.soft)),
            exit = fadeOut(tween(Motion.BASE, easing = Motion.soft)),
        ) {
            Scrim(onDismiss)
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(tween(Motion.BASE, easing = Motion.quiet)) +
                scaleIn(tween(Motion.BASE, easing = Motion.quiet), initialScale = 0.94f),
            exit = fadeOut(tween(Motion.FAST, easing = Motion.soft)) +
                scaleOut(tween(Motion.FAST, easing = Motion.soft), targetScale = 0.94f),
        ) {
            Box(Modifier.swallowTaps()) { content() }
        }
    }
}

/** A tappable full-bleed scrim that dismisses on click (no ripple). */
@Composable
private fun BoxScope.Scrim(onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Ide.colors.scrim)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    )
}

/** The 38×5 grab handle centered at the top of a sheet; [modifier] carries the drag gesture. */
@Composable
private fun DragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().padding(top = 9.dp, bottom = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(38.dp)
                .height(5.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(Ca.radius.pill)),
        )
    }
}
