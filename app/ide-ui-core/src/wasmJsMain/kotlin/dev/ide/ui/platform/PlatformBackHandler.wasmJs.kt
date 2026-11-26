// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.ui.platform

import androidx.compose.runtime.Composable

// A browser's back button navigates the page, not the app's own stack.
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
}
