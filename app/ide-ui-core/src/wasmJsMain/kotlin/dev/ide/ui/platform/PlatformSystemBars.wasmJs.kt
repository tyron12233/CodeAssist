// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// No system bars inside a page.
@Composable
actual fun PlatformSystemBars(darkTheme: Boolean) {
}

@Composable
actual fun HideSystemBars(hidden: Boolean) {
}

@Composable
actual fun hiddenSystemBarsWidth(): Dp = 0.dp

@Composable
actual fun transientStatusBarHeight(): Dp = 0.dp
