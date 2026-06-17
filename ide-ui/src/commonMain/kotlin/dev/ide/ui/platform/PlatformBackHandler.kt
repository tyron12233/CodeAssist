package dev.ide.ui.platform

import androidx.compose.runtime.Composable

/**
 * Handle the platform's "back" affordance (the Android system back gesture / button) while [enabled].
 * When the user navigates back and [enabled] is true, [onBack] runs and the event is consumed instead of
 * propagating to the host — so back closes an open sheet/dialog or pops a screen rather than killing the app
 * (issue #997). Nested handlers stack: the innermost enabled one wins, so an editor overlay's handler takes
 * priority over the screen-navigation handler above it.
 *
 * On desktop there is no system back gesture, so the actual is a no-op (window chrome handles close).
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
