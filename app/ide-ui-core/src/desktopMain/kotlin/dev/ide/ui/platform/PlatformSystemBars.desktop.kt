package dev.ide.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Desktop draws its own window chrome; there are no app-controlled system bars, so this is a no-op. */
@Composable
actual fun PlatformSystemBars(darkTheme: Boolean) {
}

@Composable
actual fun HideSystemBars(hidden: Boolean) {
}

@Composable
actual fun hiddenSystemBarsWidth(): Dp = 0.dp
