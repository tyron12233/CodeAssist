package dev.ide.ui.platform

import androidx.compose.runtime.Composable

/**
 * Match the platform's system-bar (status / navigation bar) ICON appearance to the app theme: DARK icons on a
 * LIGHT theme, LIGHT icons on a DARK theme — so the clock/battery/back glyphs stay legible against the app's
 * edge-to-edge background. Reactive: it re-applies whenever [darkTheme] flips (the Settings theme toggle, or a
 * system dark-mode change while "system" is selected).
 *
 * Android only; desktop draws its own window chrome and has no app-controlled system bars, so the actual is a no-op.
 */
@Composable
expect fun PlatformSystemBars(darkTheme: Boolean)
