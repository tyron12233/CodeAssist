package dev.ide.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

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

/**
 * Hide the status and navigation bars while [hidden] (zen mode), bringing them back when it turns false or
 * the caller leaves the composition. A swipe in from the edge shows them for a moment, as in any immersive app.
 * Android only; the desktop and iOS hosts have no bars the app hides, so their actuals do nothing.
 */
@Composable
expect fun HideSystemBars(hidden: Boolean)

/**
 * The width the hidden system bars give back to the window right now: the left and right system-bar insets the
 * window has with every bar shown, less the ones it has. Nonzero only while bars on a side are hidden (zen mode
 * with the navigation bar on the side, in landscape).
 *
 * A layout chosen by width subtracts this so it does not depend on whether those bars happen to show. Android
 * shows the navigation bar again while the soft keyboard is up, even in immersive mode, so a width measured
 * inside the bars changes as the keyboard opens and closes. Android only; elsewhere it is zero.
 */
@Composable
expect fun hiddenSystemBarsWidth(): Dp
