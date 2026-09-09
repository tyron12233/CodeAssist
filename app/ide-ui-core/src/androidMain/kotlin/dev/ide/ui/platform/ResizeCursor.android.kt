package dev.ide.ui.platform

import androidx.compose.ui.Modifier

/** Touch hosts have no pointer cursor; the row divider is dragged by touch instead. */
actual fun Modifier.verticalResizeCursor(): Modifier = this
