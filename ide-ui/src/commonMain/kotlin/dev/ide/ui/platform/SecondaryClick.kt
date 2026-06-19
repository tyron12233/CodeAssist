package dev.ide.ui.platform

import androidx.compose.ui.Modifier

/**
 * Open a context affordance via the platform's secondary action: a right-click on desktop. On touch hosts
 * (Android) there is no secondary mouse button, so the actual is a no-op — those hosts reach the same menu
 * through long-press instead. [onClick] runs when the secondary action fires while [enabled].
 */
expect fun Modifier.secondaryClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier
