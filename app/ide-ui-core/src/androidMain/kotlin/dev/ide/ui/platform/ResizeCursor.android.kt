package dev.ide.ui.platform

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon

/** A north/south resize cursor over a horizontal splitter, shown when a mouse is in use. */
actual fun Modifier.verticalResizeCursor(): Modifier =
    this.pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW))

/** An east/west resize cursor over a vertical splitter. */
actual fun Modifier.horizontalResizeCursor(): Modifier =
    this.pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW))
