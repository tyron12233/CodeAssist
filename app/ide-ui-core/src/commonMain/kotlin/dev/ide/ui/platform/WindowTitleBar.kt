package dev.ide.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * A desktop window whose title bar the app draws itself, IntelliJ-style: the window's native controls (the
 * macOS traffic lights, the Windows caption buttons) stay native and sit on top of the app's own bar, and the
 * bar's empty space drags the window like a native title bar. Provided by the host through
 * [LocalWindowTitleBar]; null where the system draws an ordinary title bar (Android, iOS, or a desktop runtime
 * without custom title bar support).
 *
 * One screen at a time can draw its bar into the title bar by claiming it ([claimWindowTitleBar]). While none
 * does, the app shell draws a plain title strip so the window can still be dragged.
 */
@Stable
interface WindowTitleBar {
    /** Height of the title bar row. The native window controls are centred vertically in it. */
    val height: Dp

    /** Space the native window controls take at the start (macOS traffic lights). Zero where there are none,
     *  such as a macOS window in full screen. */
    val startInset: Dp

    /** Space the native window controls take at the end (Windows caption buttons). */
    val endInset: Dp

    /** Whether a screen is currently drawing its own bar into the title bar. */
    val claimed: Boolean

    /** Makes this element behave as the title bar: dragging its empty space moves the window and a double
     *  click zooms it, while buttons inside it keep working. Apply it once, to the bar's background. */
    fun Modifier.titleBarArea(): Modifier

    /** Starts drawing a bar of [height] into the title bar; pair with [release]. */
    fun claim(height: Dp)

    /** Gives the title bar back to the shell's plain strip. */
    fun release()
}

/** The window title bar the app may draw into, or null when the system draws its own. */
val LocalWindowTitleBar = staticCompositionLocalOf<WindowTitleBar?> { null }

/**
 * Claims the window's title bar for a bar of [height] drawn by the calling screen, for as long as the caller
 * stays in composition. Returns the title bar to lay the bar out around (its insets), or null when the system
 * draws its own title bar and the caller should lay out as usual.
 */
@Composable
fun claimWindowTitleBar(height: Dp): WindowTitleBar? {
    val bar = LocalWindowTitleBar.current
    DisposableEffect(bar, height) {
        bar?.claim(height)
        onDispose { bar?.release() }
    }
    return bar
}
