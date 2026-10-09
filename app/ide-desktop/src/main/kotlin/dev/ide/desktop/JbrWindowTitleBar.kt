package dev.ide.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import com.jetbrains.JBR
import com.jetbrains.WindowDecorations
import dev.ide.ui.components.WindowTitleStripHeight
import dev.ide.ui.platform.WindowTitleBar
import kotlinx.coroutines.currentCoroutineContext
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import kotlinx.coroutines.isActive

/**
 * The window's title bar drawn by the app, through the JetBrains Runtime's custom title bar (the mechanism
 * IntelliJ uses): the OS keeps drawing the window controls and handling drag, double-click zoom and snapping,
 * while the app paints the bar itself. Null on a JDK without the API, which keeps the ordinary title bar.
 */
@Composable
fun rememberJbrWindowTitleBar(window: ComposeWindow, state: WindowState): WindowTitleBar? {
    val bar = remember(window) {
        val supported = runCatching { JBR.isWindowDecorationsSupported() }.getOrDefault(false)
        if (supported) JbrWindowTitleBar(window) else null
    } ?: return null
    // The controls' insets change with the window's placement (none in macOS full screen), and the native bar
    // must match the height of whichever bar is drawn into it.
    LaunchedEffect(bar, bar.height, state.placement) { bar.apply() }
    // A title bar installed before the window is first shown does not take, and the first composition runs
    // before that; install it again once the window is open.
    DisposableEffect(window, bar) {
        val listener = object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) = bar.apply()
        }
        window.addWindowListener(listener)
        onDispose { window.removeWindowListener(listener) }
    }
    return bar
}

private class JbrWindowTitleBar(private val window: ComposeWindow) : WindowTitleBar {
    private var native: WindowDecorations.CustomTitleBar? = null
    private var claims by mutableStateOf(0)
    private var claimedHeight by mutableStateOf(WindowTitleStripHeight)

    override val height: Dp get() = if (claims > 0) claimedHeight else WindowTitleStripHeight
    override var startInset by mutableStateOf(0.dp)
        private set
    override var endInset by mutableStateOf(0.dp)
        private set
    override val claimed: Boolean get() = claims > 0

    override fun claim(height: Dp) {
        claimedHeight = height
        claims++
    }

    override fun release() {
        claims = (claims - 1).coerceAtLeast(0)
    }

    /** (Re)installs the native title bar at the current [height] and reads back the controls' insets. A JBR
     *  title bar is applied when set, so a change means installing a new one. AWT logical pixels are Compose
     *  dp on desktop. */
    fun apply() {
        val decorations = JBR.getWindowDecorations()
        val bar = decorations.createCustomTitleBar()
        bar.height = height.value
        decorations.setCustomTitleBar(window, bar)
        native = bar
        startInset = bar.leftInset.dp
        endInset = bar.rightInset.dp
    }

    /**
     * Tells the native title bar, per pointer event, whether it landed on the app's own controls. The handler
     * sees events after the bar's children (Main pass): a press a button consumed is client area and stays with
     * the app; anything else falls through to the native bar, which then drags or zooms the window.
     */
    override fun Modifier.titleBarArea(): Modifier = pointerInput(Unit) {
        val context = currentCoroutineContext()
        awaitPointerEventScope {
            var inControl = false
            while (context.isActive) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val consumed = event.changes.any { it.isConsumed }
                if (consumed || inControl) {
                    if (event.type == PointerEventType.Press) inControl = true
                    if (event.type == PointerEventType.Release) inControl = false
                    native?.forceHitTest(true)
                } else {
                    native?.forceHitTest(false)
                }
            }
        }
    }
}
