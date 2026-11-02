// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.ui.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiQuickDoc

/** Which prompts, menus and popups are open over one editor tab (created per file, like the state it replaced). */
@Stable
internal class EditorPopupState {
    var gotoLineOpen by mutableStateOf(false)
    var quickDoc by mutableStateOf<UiQuickDoc?>(null)
    var rename by mutableStateOf<RenameUiState?>(null)
    var renameBusy by mutableStateOf(false)
    var renameError by mutableStateOf<String?>(null)
    /** The source-navigation dropdown / target picker (Go to Declaration / Implementation / Type / Super). */
    var navMenu by mutableStateOf<NavMenuState?>(null)
    /** Where a right-click opened the context menu, in pane coordinates. */
    var contextMenuAt by mutableStateOf<Offset?>(null)
    /** The problem popup opened by resting the pointer on a problem, by the sheet group it opened (a click that
     *  opens another group replaces it, and must not be closed when the hover hides). */
    var hoverSheetGroup by mutableStateOf<List<UiDiagnostic>?>(null)
}
