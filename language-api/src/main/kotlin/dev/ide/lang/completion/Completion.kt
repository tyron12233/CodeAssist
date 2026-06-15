package dev.ide.lang.completion

import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.resolve.Scope
import dev.ide.lang.resolve.Symbol
import dev.ide.lang.resolve.TypeRef
import dev.ide.lang.template.SnippetExpansion

/**
 * Code completion.
 *
 * Flow: capture a [DocumentSnapshot] + caret offset -> ensure the ParsedFile is at that version
 * (incremental reparse if stale) -> splice a completion marker at the caret on a copy and parse it
 * -> derive a [CompletionContext] (kind + qualifier/scope) -> enumerate, filter by prefix and
 * accessibility, rank by expected type -> [CompletionResult].
 *
 * Everything above the SPI talks only to [CompletionService] and the neutral Symbol/Scope/TypeRef
 * types, so which engine produced the items is invisible.
 */
interface CompletionService {
    suspend fun complete(request: CompletionRequest): CompletionResult

    /** Optional: lazily fill in docs/signature for one item when the user highlights it. */
    suspend fun resolveItem(item: CompletionItem): CompletionItem = item
}

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
    val detail: String? = null,             // signature / type, shown to the right
    val documentation: String? = null,
    /** Lower sorts first; backends set this from prefix match + expected-type match + proximity. */
    val sortPriority: Int = 0,
    /** The resolved symbol behind this item, if any (for navigation, docs-on-demand). */
    val symbol: Symbol? = null,
    /** Edits beyond the insertion — e.g. auto-add an `import` when completing a type by simple name. */
    val additionalEdits: List<TextEdit> = emptyList(),
    /** Where the caret (and any selection) lands once this item is accepted; see [CaretAction]. */
    val caret: CaretAction = CaretAction.AtEnd,
)

/**
 * Where the caret — and optionally a selection — ends up after a [CompletionItem] is accepted, expressed
 * relative to the just-inserted [CompletionItem.insertText]. This is the extensible seam for "smart"
 * insertions: a [CompletionService] decides the behavior (e.g. completing a method inserts `()` and lands
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
}

data class TextEdit(val range: TextRange, val newText: String)
