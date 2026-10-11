package dev.ide.ui.platform

import androidx.compose.ui.Modifier

/**
 * Open a context affordance via the platform's secondary action: a right-click with a mouse, on desktop and on
 * Android (Chromebooks, tablets with a mouse). Touch reaches the same menu through long-press instead. [onClick]
 * runs when the secondary action fires while [enabled].
 */
expect fun Modifier.secondaryClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier
