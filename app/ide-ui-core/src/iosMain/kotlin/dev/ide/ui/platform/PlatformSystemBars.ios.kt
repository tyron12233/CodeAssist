package dev.ide.ui.platform

import androidx.compose.runtime.Composable

/** The status-bar appearance on iOS is owned by the hosting `UIViewController`
 *  (`preferredStatusBarStyle`), not by the composition, so there is nothing to set from here. The host
 *  controller reads the same theme flag when it is built. */
@Composable
actual fun PlatformSystemBars(darkTheme: Boolean) {
}
