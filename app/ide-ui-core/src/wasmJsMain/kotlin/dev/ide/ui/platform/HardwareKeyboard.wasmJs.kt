// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.ui.platform

import androidx.compose.runtime.Composable

// A browser cannot tell; the Playground is a keyboard-and-mouse surface first.
@Composable
actual fun hasHardwareKeyboard(): Boolean = true
