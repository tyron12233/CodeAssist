// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.analysis

import dev.ide.lang.LanguageId
import dev.ide.lang.dom.TextRange
import dev.ide.platform.ExtensionPoint

/**
 * What the caret is on when the user asks to rename it: the current [name], the [range] it covers in the
 * buffer, and a [kind] for the dialog's title (`"function"`, `"class"`, or just `"symbol"`). Since SPI 3.3.0.
 */
class RenameTarget @JvmOverloads constructor(
    val name: String,
    val range: TextRange,
    val kind: String = "symbol",
) {
    override fun toString(): String = "$kind '$name' at ${range.start}..${range.end}"
}

/**
 * The answer to a rename: the [edit] to apply across the project, or the [error] to show instead (a name the
 * language rejects, a symbol declared in a library). Since SPI 3.3.0.
 */
class RenameResult private constructor(val edit: WorkspaceEdit?, val error: String?) {
    companion object {
        @JvmStatic
        fun of(edit: WorkspaceEdit): RenameResult = RenameResult(edit, null)

        @JvmStatic
        fun rejected(message: String): RenameResult = RenameResult(null, message)
    }
}

/**
 * Rename for a plugin's language. For a file in one of [languages] (empty means every language) the editor
 * asks [prepare] when the user starts a rename (null: nothing to rename here, and the dialog says so) and
 * [rename] once they confirm the new name. The host applies the edit to disk and to every open editor, as one
 * change the project re-indexes once.
 *
 * Registered on [RENAME_PROVIDER_EP]. Since SPI 3.3.0.
 */
interface RenameProvider {
    val id: String

    val languages: Set<LanguageId> get() = emptySet()

    suspend fun prepare(target: AnalysisTarget, offset: Int): RenameTarget?

    suspend fun rename(target: AnalysisTarget, offset: Int, newName: String): RenameResult
}

/** Plugin-contributed rename; see [RenameProvider]. Since SPI 3.3.0. */
val RENAME_PROVIDER_EP = ExtensionPoint<RenameProvider>("platform.renameProvider")
