package dev.ide.ui.platform

import androidx.compose.ui.Modifier

/**
 * Show a vertical (north/south) resize cursor while the pointer hovers a horizontal splitter — the mouse
 * affordance for a draggable row divider, on desktop and on Android with a mouse. A no-op on iOS.
 */
expect fun Modifier.verticalResizeCursor(): Modifier

/**
 * Show a horizontal (east/west) resize cursor while the pointer hovers a vertical splitter, such as the edge of
 * a docked tool-window pane. A no-op on iOS, like [verticalResizeCursor].
 */
expect fun Modifier.horizontalResizeCursor(): Modifier
