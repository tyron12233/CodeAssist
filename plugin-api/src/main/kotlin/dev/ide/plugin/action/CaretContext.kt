// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.plugin.action

/**
 * What the caret is sitting on, as flat data: the context an editor action reasons about when it is being
 * listed.
 *
 * This is deliberately a snapshot of primitives rather than a handle on the live syntax tree. Listing runs
 * on every caret move and for every registered action, so it must not pay a binding-level analysis; and the
 * snapshot has to cross the `IdeBackend` port as a DTO, which a tree node holding a parent pointer cannot.
 * The host builds one from the syntax-only tree it already has parsed.
 *
 * An action that needs to walk the tree or resolve a symbol belongs on the analysis-side `ActionProvider`
 * extension point instead, which hands it the live DOM and resolver. The division is intentional:
 * [CaretContext] is enough to decide whether an action applies at all, and most editor actions (comment a
 * selection, wrap a call, send code somewhere) need nothing more.
 *
 * @property offset the caret offset; equal to [ActionContext.selectionStart] when there is no selection.
 * @property languageId the language of the file the caret is in (`"java"`, `"kotlin"`, `"xml"`, …), or null
 *   when the file has no language backend.
 * @property nodeKind the kind id of the innermost tree node containing [offset], from the same open string
 *   set the DOM uses (`"method_call"`, `"class_decl"`, `"literal"`, and language-specific ids beyond those).
 *   Empty when the file could not be parsed at all.
 * @property nodeStart the innermost node's start offset.
 * @property nodeEnd the innermost node's end offset, exclusive.
 * @property nodeText the source text the innermost node covers. Capped at [MAX_NODE_TEXT] characters so a
 *   caret inside a large declaration cannot make listing expensive; [nodeTextTruncated] records when it was.
 * @property nodeTextTruncated true when [nodeText] was cut to [MAX_NODE_TEXT] characters.
 * @property ancestors the kind ids of the innermost node's ancestors, innermost first, ending at the file
 *   root, e.g. `["block", "method_decl", "class_decl", "compilation_unit"]`. Lets an action test whether
 *   it sits inside a class body without walking a tree it cannot see.
 */
data class CaretContext(
    val offset: Int,
    val languageId: String? = null,
    val nodeKind: String = "",
    val nodeStart: Int = offset,
    val nodeEnd: Int = offset,
    val nodeText: String = "",
    val nodeTextTruncated: Boolean = false,
    val ancestors: List<String> = emptyList(),
) {
    /** True when [kind] is the innermost node's kind or any ancestor's: is the caret anywhere inside one. */
    fun isInside(kind: String): Boolean = nodeKind == kind || kind in ancestors

    companion object {
        /** The cap on [nodeText]. A node longer than this is truncated rather than copied whole. */
        const val MAX_NODE_TEXT = 4096
    }
}
