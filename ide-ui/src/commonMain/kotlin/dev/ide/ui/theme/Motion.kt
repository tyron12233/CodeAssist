package dev.ide.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/** Motion tokens from tokens.css. Honor reduce-motion by gating entrance animations at call sites. */
object Motion {
    /** calm decel */
    val quiet: Easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
    /** liquid overshoot */
    val spring: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
    val soft: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    const val FAST = 160
    const val BASE = 260
    const val SLOW = 420

    /** Press feedback scale. */
    const val PRESS_SCALE = 0.96f
}
