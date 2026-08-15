package dev.ide.ui.platform

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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
