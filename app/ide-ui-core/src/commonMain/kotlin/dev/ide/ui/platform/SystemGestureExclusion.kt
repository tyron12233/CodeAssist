package dev.ide.ui.platform

import androidx.compose.ui.Modifier

/**
 * Keep the system's edge gestures (Android's back swipe) off this element, so a control drawn against the
 * screen edge can still be tapped there. The alternative, insetting the control by the gesture zone, leaves an
 * empty strip beside it. Android caps the excluded height per edge (200 dp), so it is for small controls only.
 * A no-op where there are no edge gestures (desktop, iOS).
 */
expect fun Modifier.excludeSystemGestures(): Modifier
