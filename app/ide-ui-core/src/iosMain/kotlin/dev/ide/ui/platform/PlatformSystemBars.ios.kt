package dev.ide.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The status-bar appearance on iOS is owned by the hosting `UIViewController`
 *  (`preferredStatusBarStyle`), not by the composition, so there is nothing to set from here. The host
 *  controller reads the same theme flag when it is built. */
@Composable
actual fun PlatformSystemBars(darkTheme: Boolean) {
}

@Composable
actual fun HideSystemBars(hidden: Boolean) {
}

@Composable
actual fun hiddenSystemBarsWidth(): Dp = 0.dp
