package dev.ide.ui.platform

import androidx.compose.ui.Modifier

/**
 * Show a vertical (north/south) resize cursor while the pointer hovers a horizontal splitter — the mouse
 * affordance for a draggable row divider on desktop. Touch hosts (Android) have no pointer cursor, so the
 * actual is a no-op.
 */
expect fun Modifier.verticalResizeCursor(): Modifier

/**
 * Show a horizontal (east/west) resize cursor while the pointer hovers a vertical splitter, such as the edge of
 * a docked tool-window pane. A no-op on touch hosts, like [verticalResizeCursor].
 */
expect fun Modifier.horizontalResizeCursor(): Modifier
