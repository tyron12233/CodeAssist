package dev.ide.ui.platform

import androidx.compose.runtime.Composable

/** Desktop has no system back gesture; window chrome handles close, so this is a no-op. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
}
