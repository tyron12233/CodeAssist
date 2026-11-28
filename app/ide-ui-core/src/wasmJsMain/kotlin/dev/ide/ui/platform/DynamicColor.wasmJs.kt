// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.ui.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

// There is no wallpaper palette to draw from in a browser.
@Composable
actual fun dynamicColorSchemeOrNull(dark: Boolean): ColorScheme? = null
