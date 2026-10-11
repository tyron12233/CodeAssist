package dev.ide.ui.platform

import androidx.compose.runtime.Composable

// No reliable signal here yet; an iPad keyboard user loses only the hints, not the shortcuts.
@Composable
actual fun hasHardwareKeyboard(): Boolean = false
