// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.ui.ext

import androidx.compose.ui.input.key.KeyEvent

// A browser delivers key events from a physical or an on-screen keyboard alike, with nothing that tells them apart.
internal actual fun KeyEvent.isFromSoftKeyboard(): Boolean = false
