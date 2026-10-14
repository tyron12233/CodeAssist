package dev.ide.ui.ext

import android.view.KeyCharacterMap
import androidx.compose.ui.input.key.KeyEvent

// An IME that sends key events builds them itself, and a KeyEvent built without a device is the virtual
// keyboard's; the framework's own helper (InputMethodService.sendDownUpKeyEvents) also flags them.
internal actual fun KeyEvent.isFromSoftKeyboard(): Boolean =
    nativeKeyEvent.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD ||
        nativeKeyEvent.flags and android.view.KeyEvent.FLAG_SOFT_KEYBOARD != 0
