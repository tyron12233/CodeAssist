package dev.ide.ui.platform

import androidx.compose.ui.Modifier

/** Touch hosts have no secondary mouse button; the context menu is reached via long-press instead. */
actual fun Modifier.secondaryClickable(enabled: Boolean, onClick: () -> Unit): Modifier = this
