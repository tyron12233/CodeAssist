package dev.ide.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ide.ui.Screen
import dev.ide.ui.platform.isMobilePlatform
import dev.ide.ui.theme.Motion

/**
 * Animates between top-level [Screen]s with a **platform-differentiated** feel:
 *
 * - **Mobile** — a Material *shared-axis X*: the incoming screen slides in from the side and fades while
 *   the outgoing one slides out and fades, the direction following [Screen] ordinal (deeper = from the
 *   right, back = from the left). Spatial and directional, the native phone-navigation feel.
 * - **Desktop** — a subtler *fade-through + slight scale*, faster and non-directional, matching how
 *   desktop windows swap content without big spatial motion.
 *
 * Transform-heavy but content stays legible mid-transition; honors the motion tokens in [Motion].
 */
@Composable
fun ScreenHost(
    screen: Screen,
    modifier: Modifier = Modifier,
    content: @Composable (Screen) -> Unit,
) {
    AnimatedContent(
        targetState = screen,
        modifier = modifier,
        transitionSpec = { if (isMobilePlatform) mobileSharedAxis() else desktopFade() },
        label = "screen",
    ) { target -> content(target) }
}

/** Mobile shared-axis X — directional slide + fade. `targetState`/`initialState` come from the scope. */
private fun androidx.compose.animation.AnimatedContentTransitionScope<Screen>.mobileSharedAxis(): ContentTransform {
    val forward = targetState.ordinal >= initialState.ordinal
    val dir = if (forward) 1 else -1
    val enter = slideInHorizontally(tween(Motion.BASE, easing = Motion.quiet)) { w -> dir * w / 4 } +
        fadeIn(tween(Motion.BASE, easing = Motion.soft))
    val exit = slideOutHorizontally(tween(Motion.BASE, easing = Motion.quiet)) { w -> -dir * w / 4 } +
        fadeOut(tween(Motion.BASE, easing = Motion.soft))
    return enter togetherWith exit
}

/** Desktop fade-through + slight scale — non-directional, quick. */
private fun desktopFade(): ContentTransform {
    val enter = fadeIn(tween(Motion.FAST, easing = Motion.soft)) +
        scaleIn(tween(Motion.FAST, easing = Motion.soft), initialScale = 0.98f)
    val exit = fadeOut(tween(Motion.FAST, easing = Motion.soft)) +
        scaleOut(tween(Motion.FAST, easing = Motion.soft), targetScale = 0.98f)
    return enter togetherWith exit
}
