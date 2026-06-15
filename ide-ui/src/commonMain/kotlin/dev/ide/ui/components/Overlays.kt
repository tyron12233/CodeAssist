package dev.ide.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.dp
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Motion

/**
 * An iOS-style bottom sheet — the `Sheet` primitive from the design (primitives.jsx). The scrim
 * **fades** in/out (opacity, `base`/`soft`) while the sheet body **slides** up from below
 * (transform-only, `slow`/`quiet`) so its content is never gated behind opacity — visible even if
 * motion is disabled. Drag handle + glass-thick fill + a `sheet`-radius rounded top edge.
 *
 * On phone, the file navigator and build console dock here (the README's "compact" reflow:
 * navigator/console are **sheets**, nav at the bottom).
 *
 * @param heightFraction sheet height as a fraction of the screen (~0.7 navigator, ~0.6 console).
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
                    .fillMaxHeight(heightFraction)
                    .shadow(24.dp, shape, clip = false)
                    .background(Ca.colors.glassThick, shape)
                    .border(1.dp, Ca.colors.glassEdgeTop, shape),
            ) {
                DragHandle()
                content()
            }
        }
    }
}

/**
 * A glass popover that **drops from the top** — the command-palette transition (panels.jsx):
 * scrim fades, the body slides down from a small negative offset and scales from 0.97 about its top
 * edge (transform-only). Anchored top-center under [topPadding].
 */
@Composable
fun DropdownOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    topPadding: androidx.compose.ui.unit.Dp = 60.dp,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(Motion.BASE, easing = Motion.soft)),
            exit = fadeOut(tween(Motion.BASE, easing = Motion.soft)),
        ) {
            Scrim(onDismiss)
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = topPadding),
            enter = slideInVertically(tween(Motion.BASE, easing = Motion.quiet)) { -it / 6 } +
                scaleIn(tween(Motion.BASE, easing = Motion.quiet), initialScale = 0.97f, transformOrigin = TopOrigin),
            exit = slideOutVertically(tween(Motion.FAST, easing = Motion.soft)) { -it / 6 } +
                scaleOut(tween(Motion.FAST, easing = Motion.soft), targetScale = 0.97f, transformOrigin = TopOrigin),
        ) {
            content()
        }
    }
}

private val TopOrigin = TransformOrigin(0.5f, 0f)

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
    Box(Modifier.fillMaxSize()) {
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
            content()
        }
    }
}

/** A tappable full-bleed scrim that dismisses on click (no ripple). */
@Composable
private fun BoxScope.Scrim(onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Ca.colors.scrim)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    )
}

/** The 38×5 grab handle centered at the top of a sheet. */
@Composable
private fun DragHandle() {
    Box(Modifier.fillMaxWidth().padding(top = 9.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .width(38.dp)
                .height(5.dp)
                .background(Ca.colors.textTertiary.copy(alpha = 0.5f), RoundedCornerShape(Ca.radius.pill)),
        )
    }
}
