package dev.ide.lang.jdt.completion

import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.TextEdit
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding

/**
 * How "close" a candidate is to the caret — a major ranking signal. In-scope tiers (locals … object
 * members) rank above index-sourced ones ([PACKAGE], [UNIMPORTED_TYPE]) so the user's own code wins.
 */
internal enum class Proximity {
    LOCAL, PARAMETER, OWN_MEMBER, NESTED_TYPE, INHERITED, OBJECT_MEMBER, PACKAGE, UNIMPORTED_TYPE
}

/**
 * A completion candidate from any source (resolved bindings or the index). [name] is matched (and is the
 * bare identifier); [insertText] is what actually gets typed (a method appends `()`); [presentation] is the
 * left display text and [tail] the dimmed right text. [type] (an internal binding, null for index
 * candidates) ranks against the expected type. [importEdits] carry an auto-`import` for an unimported type
 * (empty otherwise). [caret] places the caret after accept (e.g. between a method's parentheses).
 * [discouraged] demotes valid-but-poor-style candidates (a static member reached through an instance).
 */
internal class Candidate(
    val name: String,
    val insertText: String,
    val presentation: String,
    val tail: String?,
    val kind: CompletionItemKind,
    val type: TypeBinding?,
    val proximity: Proximity,
    val deprecated: Boolean,
    val importEdits: List<TextEdit> = emptyList(),
    val caret: CaretAction = CaretAction.AtEnd,
    val discouraged: Boolean = false,
    /** Javadoc recovered from source (for the doc panel), when available. */
    val documentation: String? = null,
)
