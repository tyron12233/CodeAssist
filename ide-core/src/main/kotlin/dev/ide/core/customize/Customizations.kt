package dev.ide.core.customize

/**
 * User-editable editor customizations — the keyboard **symbol bar**, live-template **macros**, and (later)
 * recorded action macros. One [CustomizationSet] per scope (global vs project); [EditorCustomizationStore]
 * merges them into an effective view the editor reads. Persisted as JSON so a set is directly shareable
 * (import/export). Pure data — no engine dependencies, so it lives at the bottom of the customization stack.
 */

/**
 * One key on the editor's keyboard symbol bar. [label] is what shows on the key; [insert] is committed at the
 * caret when it's tapped — a single-character insert goes through the editor's smart-insert, so `{`/`(`/`"`
 * still auto-close exactly as when typed. [insert] may be multi-character (e.g. `->`, `!=`) for a compound key.
 *
 * [pinned] keeps the key in the bar's fixed left group instead of the horizontal scroll (the frequently-reached
 * keys). When [action] is set the key invokes a built-in editor op ([SymbolActions]) instead of inserting text —
 * so the Tab / comment / move-line keys are themselves customizable keys, not hardcoded chrome.
 */
data class SymbolKeyDef(
    val label: String,
    val insert: String,
    val pinned: Boolean = false,
    val action: String? = null,
) {
    val isAction: Boolean get() = action != null

    companion object {
        /** A key whose label IS what it inserts — the common case (a bare symbol). */
        fun of(symbol: String): SymbolKeyDef = SymbolKeyDef(symbol, symbol)

        /** A pinned action key bound to one of [SymbolActions]. */
        fun action(label: String, action: String): SymbolKeyDef =
            SymbolKeyDef(label = label, insert = "", pinned = true, action = action)
    }
}

/**
 * The built-in editor actions a symbol-bar key can invoke (carried in [SymbolKeyDef.action]). The bar maps each
 * to an icon/behavior and the host dispatches it against the active editor session. New actions can be added
 * here without touching the persisted format (an unknown id renders as its label and no-ops).
 */
object SymbolActions {
    const val TAB = "tab"
    const val COMMENT = "comment"
    const val MOVE_LINE_UP = "moveLineUp"
    const val MOVE_LINE_DOWN = "moveLineDown"
    const val DUPLICATE_LINE = "duplicateLine"
    const val NEXT_PROBLEM = "nextProblem"

    /** Every action id, for the editor's "add an action key" picker. */
    val ALL: List<String> = listOf(TAB, COMMENT, MOVE_LINE_UP, MOVE_LINE_DOWN, DUPLICATE_LINE, NEXT_PROBLEM)
}

/**
 * A live-template **macro**: type [abbreviation] and accept it in completion to expand [template]. The template
 * text uses `$1`/`$2` tab stops (`${1:default}` for a pre-filled, editable placeholder), `$END$` for the final
 * caret, and `$VAR$` variables resolved at expansion time (`$FILE$`, `$DATE$`, `$USER$`, …; `$$` = a literal
 * `$`). [languages] limits where the macro fires — [LanguageId] values like `java`/`kotlin`/`xml`; empty = every
 * language. [builtIn] marks one of the shipped templates surfaced for editing/disabling (a user-added macro is
 * `false`); a disabled or edited built-in is persisted as an override so "reset" can restore the original.
 */
data class MacroDef(
    val abbreviation: String,
    val template: String,
    val description: String = "",
    val languages: List<String> = emptyList(),
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    /** When set (a fully-qualified type like `java.lang.String`), the macro is **type-scoped**: it's offered
     *  only at a `receiver.abbrev` position where the receiver's type matches [receiverType] (subtype-aware for
     *  an instance; the type name itself for [static]). Null = a plain statement macro offered anywhere. */
    val receiverType: String? = null,
    /** Type-scoped only: match a **static** reference to the type (`String.abbrev`) rather than an instance. */
    val static: Boolean = false,
) {
    val typeScoped: Boolean get() = !receiverType.isNullOrBlank()
}

/**
 * A recorded action macro: an ordered, replayable capture of editor operations. The op encoding is defined in
 * the recording phase; it's modeled here from the start so the persisted file schema is stable and needs no
 * migration when recording lands.
 */
data class RecordedMacroDef(val name: String, val ops: List<String> = emptyList())

/**
 * One scope's customization set. A null [symbols] means "this scope does not define a symbol bar" (fall through
 * to the next scope, then the shipped defaults); an empty list means "defined as empty" (an intentionally blank
 * bar). [macros]/[recorded] are additive per scope and merged by key.
 */
data class CustomizationSet(
    val symbols: List<SymbolKeyDef>? = null,
    val macros: List<MacroDef> = emptyList(),
    val recorded: List<RecordedMacroDef> = emptyList(),
) {
    val isEmpty: Boolean get() = symbols == null && macros.isEmpty() && recorded.isEmpty()

    companion object {
        val EMPTY = CustomizationSet()
    }
}

/** The two scopes a customization can live at: [GLOBAL] (per-user, every project) and [PROJECT] (checked into
 *  the open project, shareable with a team). The effective view is project overlaid on global overlaid on the
 *  shipped defaults. */
enum class CustomizationScope { GLOBAL, PROJECT }
