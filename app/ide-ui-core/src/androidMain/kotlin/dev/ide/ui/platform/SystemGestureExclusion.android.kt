package dev.ide.ui.platform

import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.Modifier

actual fun Modifier.excludeSystemGestures(): Modifier = systemGestureExclusion()
