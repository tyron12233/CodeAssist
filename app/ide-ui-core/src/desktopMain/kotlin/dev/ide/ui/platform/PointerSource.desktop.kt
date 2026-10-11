package dev.ide.ui.platform

import androidx.compose.ui.Modifier

/** The toolkit already reports a mouse as [androidx.compose.ui.input.pointer.PointerType.Mouse] here. */
actual fun Modifier.pointerSourceTracking(): Modifier = this
