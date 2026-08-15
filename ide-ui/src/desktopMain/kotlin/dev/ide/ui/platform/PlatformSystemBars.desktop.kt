package dev.ide.ui.platform

import androidx.compose.runtime.Composable

/** Desktop draws its own window chrome; there are no app-controlled system bars, so this is a no-op. */
@Composable
actual fun PlatformSystemBars(darkTheme: Boolean) {
}
