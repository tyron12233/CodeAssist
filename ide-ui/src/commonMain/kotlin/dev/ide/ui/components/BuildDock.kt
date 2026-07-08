package dev.ide.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Motion
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.buildc_build_succeeded
import dev.ide.ui.generated.resources.buildc_build_failed
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

/** The dock's collapsed (bottom-nav) height. */
val DockBarHeight = 60.dp

/** Fraction of the available height the half (console) detent rests at. */
private const val HalfDetentFraction = 0.6f

/** Fling speed (px/s, in dp) past which a release commits toward the flung direction's next detent. */
private val DockFlingCommit = 320.dp

/**
 * The compact layout's bottom **dock**: the bottom navigation bar is the collapsed state of a draggable
 * panel whose expanded state is the build console. Swiping the bar up (or tapping the build-status chip /
 * the top-bar console toggle) expands it; the nav items fade and sink away as the console content fades
 * in under a sheet-style grab handle.
 *
 * Detents: bar → half (console over ~60% of the screen) → full. Drags and flings settle to the nearest;
 * from the console's own scrollables the drag is nested-scroll aware — scrolling up grows the dock to
 * full before the log scrolls, and dragging down past the log's top collapses it.
 *
 * While a build runs, the collapsed bar carries a thin accent progress line on its top edge and a compact
 * status chip (spinner → ✓/✗) at its right end — build state at a glance without expanding.
 *
 * [open] is the hoisted expanded/collapsed state of record ([onOpenChange] reports gesture settles; the
 * full detent is a gesture-only refinement of "open"). [hidden] removes the dock entirely (the soft
 * keyboard's symbol bar owns the bottom slot while typing).
 */
@Composable
fun BuildDock(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    buildState: BuildState,
    modifier: Modifier = Modifier,
    hidden: Boolean = false,
    /** One-shot teaching bounce: when this flips true with the dock collapsed, the bar peeks up and
     *  springs back (the swipe affordance made physical), then [onHintShown] fires so the host can
     *  persist that the hint has been seen. */
    hint: Boolean = false,
    onHintShown: () -> Unit = {},
    bar: @Composable () -> Unit,
    console: @Composable ColumnScope.() -> Unit,
) {
    if (hidden) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val barPx = with(density) { DockBarHeight.toPx() }
        val fullPx = constraints.maxHeight.toFloat()
        val halfPx = fullPx * HalfDetentFraction
        val flingPx = with(density) { DockFlingCommit.toPx() }
        val scope = rememberCoroutineScope()
        val height = remember { Animatable(barPx) }
        height.updateBounds(barPx, fullPx)

        fun dragBy(deltaY: Float) {
            // Finger down (positive delta) shrinks the dock; up grows it.
            val target = (height.value - deltaY).coerceIn(barPx, fullPx)
            scope.launch { height.snapTo(target) }
        }

        // Settle on release: a fling commits one detent in its direction, else snap to the nearest.
        // The hoisted state is reported after the animation so an interrupted settle stays gesture-owned —
        // but in a `finally`, so it still reports the committed detent when the animation is cancelled. A
        // drag delta launches its `snapTo` on a separate scope, and on release that queued snapTo can grab
        // the Animatable's mutex just after this `animateTo` starts, cancelling it; without the `finally`
        // the dock shrinks to the bar while `open` stays stuck true, and a later relayout (the keyboard
        // closing changes `halfPx` and re-runs the effect below) then re-expands the "closed" console.
        suspend fun settle(velocityY: Float) {
            val target = when {
                velocityY < -flingPx -> if (height.value < halfPx) halfPx else fullPx
                velocityY > flingPx -> if (height.value > halfPx) halfPx else barPx
                else -> floatArrayOf(barPx, halfPx, fullPx).minBy { abs(it - height.value) }
            }
            try {
                height.animateTo(target, tween(Motion.BASE, easing = Motion.quiet), initialVelocity = -velocityY)
            } finally {
                onOpenChange(target > barPx + 1f)
            }
        }

        // External toggles (top-bar console button, Run auto-open, back press) animate the same height.
        // Opening lands on half; a gesture-reached full detent is left where the user put it.
        LaunchedEffect(open, halfPx) {
            val target = when {
                !open -> barPx
                height.value > halfPx + 1f -> return@LaunchedEffect
                else -> halfPx
            }
            if (height.value != target) height.animateTo(target, tween(Motion.BASE, easing = Motion.quiet))
        }

        // The teaching peek: rise a little, hang for a beat, ease back down. Runs only from the resting
        // bar; a grab mid-bounce cancels it (the user is already doing the gesture it teaches).
        LaunchedEffect(hint) {
            if (!hint) return@LaunchedEffect
            if (!open && height.value <= barPx + 0.5f) {
                val peekPx = with(density) { 18.dp.toPx() }
                height.animateTo(barPx + peekPx, tween(220, easing = Motion.quiet))
                delay(120.milliseconds)
                height.animateTo(barPx, tween(320, easing = Motion.quiet))
            }
            onHintShown()
        }

        // Cooperates with the console's scrollables (log/problems lists): scrolling up grows the dock to
        // full before the list scrolls; a downward drag the list didn't consume (it's at its top) shrinks
        // the dock; release settles between detents.
        val settleState = rememberUpdatedState<suspend (Float) -> Unit> { v -> settle(v) }
        val nested = remember(barPx, halfPx, fullPx) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput) return Offset.Zero
                    val dy = available.y
                    if (dy < 0 && height.value < fullPx) {
                        val next = (height.value - dy).coerceAtMost(fullPx)
                        val grown = next - height.value
                        dragBy(-grown)
                        return Offset(0f, -grown)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput) return Offset.Zero
                    val dy = available.y
                    if (dy > 0 && height.value > barPx) {
                        val next = (height.value - dy).coerceAtLeast(barPx)
                        val shrunk = height.value - next
                        dragBy(shrunk)
                        return Offset(0f, shrunk)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    val h = height.value
                    val atDetent = abs(h - barPx) < 0.5f || abs(h - halfPx) < 0.5f || abs(h - fullPx) < 0.5f
                    if (!atDetent) {
                        settleState.value(available.y)
                        return Velocity(0f, available.y)
                    }
                    return Velocity.Zero
                }
            }
        }

        // Bar → half progress: drives the crossfade (nav out, console + handle in) and the corner morph.
        val p = ((height.value - barPx) / (halfPx - barPx)).coerceIn(0f, 1f)
        val corner = lerp(0.dp, Ca.radius.sheet, p)
        val shape = RoundedCornerShape(topStart = corner, topEnd = corner)
        val edge = Ca.colors.glassEdgeTop
        Box(
            Modifier
                .fillMaxWidth()
                .height(with(density) { height.value.toDp() })
                .background(Ca.colors.glassThick, shape)
                .drawBehind { drawLine(edge, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f) }
                // A tap on the dock body must not fall through to the editor behind it.
                .pointerInput(Unit) { detectTapGestures { } }
                .draggable(
                    rememberDraggableState { dy -> dragBy(dy) },
                    Orientation.Vertical,
                    onDragStopped = { velocity -> settle(velocity) },
                ),
        ) {
            // Collapsed face: the nav items (+ build strip/chip), fading and sinking as the dock leaves
            // the bar detent. Gone from composition at half so it can't catch taps under the console.
            if (p < 1f) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(DockBarHeight)
                        .graphicsLayer {
                            alpha = 1f - (p * 2.5f).coerceAtMost(1f)
                            translationY = p * 18.dp.toPx()
                        },
                ) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { bar() }
                        BuildStatusChip(buildState.status, onClick = { onOpenChange(true) })
                    }
                    // The persistent drag affordance: a small grab notch on the bar's top edge, warming
                    // to the accent while a build runs (the moment swiping up is most worth knowing).
                    val notch by animateColorAsState(
                        if (buildState.status == RunStatus.Running) Ca.colors.accent.copy(alpha = 0.75f)
                        else Ca.colors.textTertiary.copy(alpha = 0.4f),
                        tween(Motion.BASE, easing = Motion.soft),
                        label = "dockNotch",
                    )
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 5.dp)
                            .width(28.dp)
                            .height(4.dp)
                            .background(notch, RoundedCornerShape(Ca.radius.pill)),
                    )
                    if (buildState.status == RunStatus.Running) {
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter),
                            color = Ca.colors.accent,
                            trackColor = androidx.compose.ui.graphics.Color.Transparent,
                        )
                    }
                }
            }
            // Expanded face: grab handle + the console, fading in over the outgoing bar.
            if (p > 0f) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .nestedScroll(nested)
                        .graphicsLayer { alpha = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f) },
                ) {
                    Box(
                        Modifier.fillMaxWidth().padding(top = 9.dp, bottom = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .width(38.dp)
                                .height(5.dp)
                                .background(
                                    Ca.colors.textTertiary.copy(alpha = 0.5f),
                                    RoundedCornerShape(Ca.radius.pill),
                                ),
                        )
                    }
                    console()
                }
            }
        }
    }
}

/**
 * The collapsed bar's build-status glance: a spinner while running, ✓/✗ once finished (also the tap
 * target that expands the dock). Grows in/out horizontally so the nav items glide rather than jump.
 */
@Composable
private fun BuildStatusChip(status: RunStatus, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = status != RunStatus.Idle,
        enter = fadeIn(tween(Motion.BASE, easing = Motion.soft)) + expandHorizontally(tween(Motion.BASE, easing = Motion.spring)),
        exit = fadeOut(tween(Motion.FAST, easing = Motion.soft)) + shrinkHorizontally(tween(Motion.FAST, easing = Motion.soft)),
    ) {
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .padding(end = 14.dp)
                .size(32.dp)
                .pressScale(interaction)
                .background(
                    when (status) {
                        RunStatus.Failed -> Ca.colors.error.copy(alpha = 0.16f)
                        RunStatus.Succeeded -> Ca.colors.success.copy(alpha = 0.16f)
                        else -> Ca.colors.accentSoft
                    },
                    RoundedCornerShape(Ca.radius.pill),
                )
                .clickable(interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            when (status) {
                RunStatus.Running ->
                    CircularProgressIndicator(Modifier.size(15.dp), color = Ca.colors.accent, strokeWidth = 2.dp)
                RunStatus.Succeeded ->
                    Icon(CaIcons.check, stringResource(Res.string.buildc_build_succeeded), Modifier.size(15.dp), tint = Ca.colors.success)
                RunStatus.Failed ->
                    Icon(CaIcons.close, stringResource(Res.string.buildc_build_failed), Modifier.size(15.dp), tint = Ca.colors.error)
                RunStatus.Idle -> {}
            }
        }
    }
}
