package dev.ide.kotlin.syntax.lexer

import dev.ide.kotlin.syntax.tree.IElementType

/** A lexer token kind, mirroring `org.jetbrains.kotlin.lexer.KtToken`. */
open class KtToken(debugName: String) : IElementType(debugName)

/**
 * A token whose text is fixed, mirroring `KtSingleValueToken`: `LBRACE` is always `{`. The parser uses
 * [value] to report what it expected without hard-coding the spelling at each site.
 */
open class KtSingleValueToken(debugName: String, val value: String) : KtToken(debugName)

/**
 * A keyword, mirroring `KtKeywordToken`.
 *
 * [isSoft] is the distinction that matters to the grammar: a hard keyword (`class`, `fun`) can never be an
 * identifier, while a soft one (`data`, `value`, `by`, `where`) is an ordinary identifier everywhere the
 * grammar is not specifically looking for it. The lexer therefore emits IDENTIFIER for soft keywords and the
 * PARSER promotes them, which is why [softKeyword] tokens never come out of [KotlinLexer].
 */
open class KtKeywordToken internal constructor(
    debugName: String,
    value: String,
    val isSoft: Boolean,
) : KtSingleValueToken(debugName, value) {
    companion object {
        /** A hard keyword: reserved, and lexed as itself. */
        fun keyword(debugName: String, value: String): KtKeywordToken = KtKeywordToken(debugName, value, false)

        /** A soft keyword: lexed as IDENTIFIER, promoted by the parser where the grammar expects it. */
        fun softKeyword(debugName: String, value: String): KtKeywordToken = KtKeywordToken(debugName, value, true)
    }
}

/**
 * A keyword that can appear in a modifier list, mirroring `KtModifierKeywordToken`.
 *
 * It EXTENDS [KtKeywordToken], as the compiler's does, and that is load-bearing rather than tidy: nearly every
 * modifier is a soft keyword, so the parser's promotion of IDENTIFIER to a keyword is what makes
 * `data class` work at all, and that promotion tests for [KtKeywordToken]. Making this a sibling type instead
 * silently disables every modifier in the language.
 */
class KtModifierKeywordToken internal constructor(
    debugName: String,
    value: String,
    isSoft: Boolean,
) : KtKeywordToken(debugName, value, isSoft) {
    companion object {
        fun keywordModifier(debugName: String, value: String): KtModifierKeywordToken =
            KtModifierKeywordToken(debugName, value, false)

        fun softKeywordModifier(debugName: String, value: String): KtModifierKeywordToken =
            KtModifierKeywordToken(debugName, value, true)
    }
}
