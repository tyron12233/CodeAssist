package dev.ide.ui.platform

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

@Composable
actual fun hasHardwareKeyboard(): Boolean {
    val config = LocalConfiguration.current
    return config.keyboard != Configuration.KEYBOARD_NOKEYS &&
        config.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES
}
