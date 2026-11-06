// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.analysis

import dev.ide.lang.LanguageId
import dev.ide.lang.dom.TextRange
import dev.ide.lang.highlight.UsageHighlight
import dev.ide.platform.ExtensionPoint

/**
 * One place a symbol is named: the [range] of the name in the file at the absolute [path].
 * [isDeclaration] marks the symbol's own declaration, which Find Usages leaves out of its list.
 * Since SPI 3.3.0.
 */
class SymbolReference @JvmOverloads constructor(
    val path: String,
    val range: TextRange,
    val isDeclaration: Boolean = false,
) {
    override fun equals(other: Any?): Boolean =
        other is SymbolReference && other.path == path && other.range == range && other.isDeclaration == isDeclaration

    override fun hashCode(): Int = (path.hashCode() * 31 + range.hashCode()) * 31 + isDeclaration.hashCode()

    override fun toString(): String = "$path@${range.start}..${range.end}" + if (isDeclaration) " (declaration)" else ""
}

/**
 * Find Usages and Highlight Usages for a plugin's language. The editor's built-in answers cover Java and
 * Kotlin; for a file in one of [languages] (empty means every language) it asks the providers instead.
 *
 * [target] is built from the live buffer, so [offset] indexes the text the user is looking at.
 * Registered on [REFERENCE_PROVIDER_EP]. Since SPI 3.3.0.
 */
interface ReferenceProvider {
    val id: String

    val languages: Set<LanguageId> get() = emptySet()

    /**
     * Every reference to the symbol at [offset], project-wide, with its declaration(s) when
     * [includeDeclaration]. Empty when the position is not a symbol this provider knows.
     */
    suspend fun references(target: AnalysisTarget, offset: Int, includeDeclaration: Boolean): List<SymbolReference>

    /**
     * The names in [target]'s own file that refer to the symbol at [offset], each read, written or declared,
     * for highlight-usages-at-caret. Null leaves the editor's plain whole-word scan in place.
     */
    suspend fun highlights(target: AnalysisTarget, offset: Int): List<UsageHighlight>? = null
}

/** Plugin-contributed usages; see [ReferenceProvider]. Since SPI 3.3.0. */
val REFERENCE_PROVIDER_EP = ExtensionPoint<ReferenceProvider>("platform.referenceProvider")
