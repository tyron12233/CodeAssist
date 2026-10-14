package dev.ide.ui.ext

import androidx.compose.ui.input.key.KeyEvent

internal actual fun KeyEvent.isFromSoftKeyboard(): Boolean = false
