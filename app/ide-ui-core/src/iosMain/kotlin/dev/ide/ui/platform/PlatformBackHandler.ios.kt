package dev.ide.ui.platform

import androidx.compose.runtime.Composable

/** iOS has no system back button; navigation is popped by the app's own affordances and the interactive
 *  edge-swipe, which Compose Multiplatform routes through the navigation host rather than a handler here. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
}
