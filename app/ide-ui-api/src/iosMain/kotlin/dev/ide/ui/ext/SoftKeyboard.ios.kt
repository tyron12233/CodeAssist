package dev.ide.ui.ext

import androidx.compose.ui.input.key.KeyEvent

// UIKit reports presses only from a hardware keyboard; the on-screen one types through text input.
internal actual fun KeyEvent.isFromSoftKeyboard(): Boolean = false
