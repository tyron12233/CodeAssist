package dev.ide.ui.screens

import dev.ide.ui.backend.CustomizationActions
import dev.ide.ui.backend.UiSymbolKey

/**
 * What a symbol key types when flicked up: its partner, so one key covers two symbols and the bar needs half as
 * many. Looked up by the key's own text, so a user's customized keys get the same alternates.
 */
internal val SYMBOL_ALTERNATES: Map<String, String> = mapOf(
    "{" to "}", "(" to ")", "[" to "]", "<" to ">",
    "\"" to "'", "." to ",", ":" to ";", ";" to ":", "=" to "!",
    "+" to "-", "*" to "/", "/" to "\\", "&" to "|", "@" to "#", "_" to "%",
    "->" to "=>", "?." to "?:", "$" to "\${", "?" to "!",
)

/** The editor actions every set carries: Tab up front, the rest folded behind the bar's ⋯ key. */
private val ACTION_KEYS = listOf(
    UiSymbolKey("Tab", "", pinned = true, action = CustomizationActions.TAB),
    UiSymbolKey("//", "", action = CustomizationActions.COMMENT),
    UiSymbolKey("Move line up", "", action = CustomizationActions.MOVE_LINE_UP),
    UiSymbolKey("Move line down", "", action = CustomizationActions.MOVE_LINE_DOWN),
    UiSymbolKey("Duplicate line", "", action = CustomizationActions.DUPLICATE_LINE),
    UiSymbolKey("Clipboard", "", action = CustomizationActions.PASTE_HISTORY),
    UiSymbolKey("Bookmark", "", action = CustomizationActions.TOGGLE_BOOKMARK),
)

private fun keys(vararg symbols: String) = ACTION_KEYS + symbols.map { UiSymbolKey(it, it) }

/**
 * The shipped keys for [path]'s language, most used first. With the flick-up alternates the first screenful
 * covers what that language types most: Kotlin's `{ } ( ) . , : ; = ! " '` then `->` and `?.`, XML's tag
 * punctuation, Java's `;` up front. A file of no known language gets the generic set.
 */
internal fun languageSymbolKeys(path: String): List<UiSymbolKey> = when (path.substringAfterLast('.', "").lowercase()) {
    "kt", "kts" -> keys("{", "(", ".", ":", "=", "\"", "->", "?.", "<", "[", "$", "+", "*", "&", "@", "_")
    "java" -> keys(";", "{", "(", ".", "=", "\"", "<", "[", "+", "*", "&", "?", "@", "->", "_")
    "xml" -> keys("<", "/", "=", "\"", "@", ":", "_", ".", "?", "{", "(")
    else -> keys("{", "(", ";", ".", "=", "\"", "<", "[", "+", "*", "&", "@", "_", "?")
}

/**
 * The keys the bar shows for [path]: the user's own set when they customized it, else the language's. The shipped
 * global default ([DEFAULT_SYMBOL_KEYS]) counts as not customized, since it predates the per-language sets.
 */
internal fun effectiveSymbolKeys(userKeys: List<UiSymbolKey>, path: String?): List<UiSymbolKey> =
    if (userKeys.isEmpty() || userKeys == DEFAULT_SYMBOL_KEYS) languageSymbolKeys(path.orEmpty()) else userKeys
