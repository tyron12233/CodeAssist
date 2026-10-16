package dev.ide.ui.platform

import android.app.Activity
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
actual fun PlatformSystemBars(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = (view.context as? Activity)?.window ?: return
    // `isAppearanceLight*Bars = true` asks the system for DARK icons (for a light background). So a LIGHT app
    // theme → light bars (dark icons); a DARK app theme → dark bars (light icons). Re-applied on every [darkTheme]
    // change, since the flag is otherwise sticky from the last time it was set (e.g. by enableEdgeToEdge).
    SideEffect {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !darkTheme
        controller.isAppearanceLightNavigationBars = !darkTheme
    }
}

@Composable
actual fun HideSystemBars(hidden: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = (view.context as? Activity)?.window ?: return
    DisposableEffect(hidden, window) {
        val controller = WindowCompat.getInsetsController(window, view)
        if (hidden) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (hidden) controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
actual fun hiddenSystemBarsWidth(): Dp {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    fun WindowInsets.horizontal() = getLeft(density, direction) + getRight(density, direction)
    val hiddenPx = WindowInsets.systemBarsIgnoringVisibility.horizontal() - WindowInsets.systemBars.horizontal()
    return if (hiddenPx <= 0) 0.dp else with(density) { hiddenPx.toDp() }
}
