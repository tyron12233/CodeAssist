// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.platform.LocalLayoutDirection

@Composable
internal actual fun appLanguageLocals(tag: String?): List<ProvidedValue<*>> {
    // Without a chosen language the page's own direction already applies. With one, only the layout direction
    // is provided: a browser build has no process-wide default locale to set.
    if (tag == null) return emptyList()
    return listOf(LocalLayoutDirection provides layoutDirectionOf(tag.substringBefore('-')))
}
