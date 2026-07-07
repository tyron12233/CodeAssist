package dev.ide.lang.completion

import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.resolve.Scope
import dev.ide.lang.resolve.Symbol
import dev.ide.lang.resolve.TypeRef
import dev.ide.lang.template.SnippetExpansion

/**
 * Code completion is delivered through the [CompletionContributor] API (see `Contributor.kt`): a language
 * backend publishes its completion as one or more contributors via
 * [dev.ide.lang.SourceAnalyzer.completionContributions], and the engine merges them with cross-cutting /
 * plugin contributors over a shared [CompletionResultSet]. There is no per-language completion *service*
 * interface — the old `CompletionService` was replaced by contributors (see `docs/completion-contributor-api.md`).
 *
 * [CompletionRequest] survives as a convenience input for the engine-free `complete(...)` runners in
 * `CompletionRunner.kt` (used by tests and simple drivers).
 */
data class CompletionRequest(
    val document: DocumentSnapshot,
    val offset: Int,
    val trigger: CompletionTrigger,
)

sealed interface CompletionTrigger {
    /** Ctrl-Space / explicit invocation. */
    object Explicit : CompletionTrigger
    /** Typed a trigger character such as '.' or ':'. */
    data class TypedChar(val char: Char) : CompletionTrigger
}

/**
 * The semantic situation at the caret, produced by the completion-marker parse. This — not the raw
 * text — is what drives candidate generation.
 */
data class CompletionContext(
    val kind: CompletionContextKind,
    /** The completion node (the marker's node) in the local completion parse. */
    val node: DomNode,
    /** Text already typed for the symbol being completed; candidates are prefix-filtered by it. */
    val prefix: String,
    /** Range the accepted item should replace (usually the partial identifier under the caret). */
    val replacementRange: TextRange,
    /** For MEMBER_ACCESS: the resolved static type of the qualifier; null otherwise. */
    val qualifierType: TypeRef? = null,
    /** For NAME_REFERENCE / TYPE_REFERENCE: the lexical scope visible here. */
    val scope: Scope? = null,
    /** Inferred target type at the caret, for ranking (int x = | -> prefer int-assignable). */
    val expectedType: TypeRef? = null,
)

enum class CompletionContextKind {
    NAME_REFERENCE,     // bare `foo|`   -> scope.symbols()
    MEMBER_ACCESS,      // `expr.|`      -> qualifierType.members(accessibleFrom)
    TYPE_REFERENCE,     // type position -> visible types + classpath index
    METHOD_ARGUMENT,    // inside `call(|` -> scope + expected param type
    ANNOTATION,         // `@|`
    IMPORT,             // `import a.b.|`
    PACKAGE_REFERENCE,  // package segment
    CASE_LABEL,         // `case |` in a switch on an enum
    KEYWORD,            // statement-start keyword position
    UNKNOWN,
}

data class CompletionResult(
    val items: List<CompletionItem>,
    /** true if the list was truncated and should be recomputed as the user types more. */
    val isIncomplete: Boolean = false,
    val replacementRange: TextRange,
)

data class CompletionItem(
    val label: String,                      // shown in the popup
    val insertText: String,                 // what gets inserted (may differ from label)
    val kind: CompletionItemKind,
    val detail: String? = null,             // signature / type, shown under the label (second line)
    /** Origin shown right-aligned on the row: a type's package, or a member's declaring class. Null hides it. */
    val container: String? = null,
    val documentation: String? = null,
    /** Lower sorts first; the final within-backend tiebreaker after the weigher chain (see `Contributor.kt`). */
    val sortPriority: Int = 0,
    /** The resolved symbol behind this item, if any (for navigation, docs-on-demand). */
    val symbol: Symbol? = null,
    /** Edits beyond the insertion — e.g. auto-add an `import` when completing a type by simple name. */
    val additionalEdits: List<TextEdit> = emptyList(),
    /** Where the caret (and any selection) lands once this item is accepted; see [CaretAction]. */
    val caret: CaretAction = CaretAction.AtEnd,
    /** Producer-computed relevance facts the ranking weighers read; null ranks as [CompletionRelevance.NONE]. */
    val relevance: CompletionRelevance? = null,
)

/**
 * The relevance facts a producing contributor already knows at emit time, carried on the item so the
 * [CompletionWeigher] chain can rank the merged set without re-resolving any symbol. Every field has a
 * neutral default: a contributor that fills nothing ranks exactly as before (the weighers see no signal).
 */
data class CompletionRelevance(
    /** The candidate's produced/declared type is assignable to the type the context expects. */
    val fitsExpectedType: Boolean = false,
    /** Backend-judged contextual fit beyond types — e.g. a `@Composable` callable inside a composable
     *  calling context. A boost, never a filter. */
    val contextBoost: Boolean = false,
    /** Grouping of a callable relative to its receiver, lower = closer: own member (0), project-source
     *  extension (1), library extension (2), universal scope function (3), `Object`/`Any` method (4). */
    val callableWeight: Int = 0,
    /** Already visible at the caret — imported or in scope, so accepting it adds no import. */
    val inScope: Boolean = true,
    val deprecated: Boolean = false,
    /** Producer-computed declaration proximity (lower = closer): locals/parameters, then members, then
     *  project symbols, then library. 0 = no signal, so a backend that doesn't fill it is unaffected. */
    val proximity: Int = 0,
) {
    companion object {
        /** What the weighers assume for an item whose producer attached no relevance. */
        val NONE = CompletionRelevance()
    }
}

/**
 * Where the caret — and optionally a selection — ends up after a [CompletionItem] is accepted, expressed
 * relative to the just-inserted [CompletionItem.insertText]. This is the extensible seam for "smart"
 * insertions: a [CompletionContributor] decides the behavior (e.g. completing a method inserts `()` and lands
 * the caret between the parentheses when it takes arguments) while the editor — which knows nothing about
 * Java, methods, or parentheses — simply applies the action. Offsets are character counts from the start
 * of the inserted text; the editor clamps them into range. Add new variants here, not in the editor.
 */
sealed interface CaretAction {
    /** Leave the caret at the end of the inserted text — the default, plain-identifier behavior. */
    object AtEnd : CaretAction
    /** Place the caret [offset] characters into the inserted text (e.g. between an empty `()`). */
    data class At(val offset: Int) : CaretAction
    /** Select [length] characters starting [offset] characters in — a placeholder to overtype. */
    data class Select(val offset: Int, val length: Int) : CaretAction

    /**
     * Drive the inserted text as a snippet: the editor steps through [SnippetExpansion.stops] (linked
     * multi-cursor stops, choice popups) and lands at [SnippetExpansion.finalCaretOffset]. This lets a
     * `kind = SNIPPET` item — or a postfix-template item (see dev.ide.lang.postfix) — carry full tab-stop
     * behaviour through the ordinary completion-accept path. The expansion's offsets are relative to the
     * start of [CompletionItem.insertText], which the engine should set to [SnippetExpansion.text].
     */
    data class ExpandSnippet(val expansion: SnippetExpansion) : CaretAction
}

enum class CompletionItemKind {
    CLASS, INTERFACE, ENUM, ANNOTATION_TYPE, RECORD,
    METHOD, CONSTRUCTOR, FIELD, ENUM_CONSTANT,
    VARIABLE, PARAMETER, TYPE_PARAMETER,
    PACKAGE, KEYWORD, SNIPPET,
    /** A bare word lifted from the current buffer (hippie/word completion) — no semantic backing. */
    WORD,
}

data class TextEdit(val range: TextRange, val newText: String)
