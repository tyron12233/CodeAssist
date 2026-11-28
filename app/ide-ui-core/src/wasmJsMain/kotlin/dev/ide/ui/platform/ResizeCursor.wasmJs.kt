// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.ui.platform

import androidx.compose.ui.Modifier

// Compose's common PointerIcon has no resize cursors (they are AWT's on desktop); the default arrow stands in.
actual fun Modifier.verticalResizeCursor(): Modifier = this

actual fun Modifier.horizontalResizeCursor(): Modifier = this
