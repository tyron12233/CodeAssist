package dev.ide.ui.platform

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import java.awt.Cursor

/** A north/south resize cursor over a horizontal splitter. */
actual fun Modifier.verticalResizeCursor(): Modifier =
    this.pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))

/** An east/west resize cursor over a vertical splitter. */
actual fun Modifier.horizontalResizeCursor(): Modifier =
    this.pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
