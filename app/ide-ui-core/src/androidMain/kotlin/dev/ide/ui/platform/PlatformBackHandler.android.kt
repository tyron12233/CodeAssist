package dev.ide.ui.platform

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)
