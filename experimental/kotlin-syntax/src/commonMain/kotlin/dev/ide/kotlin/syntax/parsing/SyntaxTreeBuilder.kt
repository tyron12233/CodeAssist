package dev.ide.kotlin.syntax.parsing

import dev.ide.kotlin.syntax.tree.AstNode
import dev.ide.kotlin.syntax.tree.IElementType

/**
 * The parser's view of the token stream and the tree it is building, mirroring `com.intellij.lang.PsiBuilder`
 * together with the Kotlin compiler's `SemanticWhitespaceAwarePsiBuilder`.
 *
 * This interface is the reason the port is tractable. The compiler's grammar (`KotlinParsing`,
 * `KotlinExpressionParsing`) is ~6,000 lines of Java that touches only five IntelliJ types, and this is the
 * load-bearing one: everything the grammar does is `mark`, `advanceLexer`, look at [tokenType], and then
 * `done` or `rollbackTo`. Reproduce it faithfully and the grammar itself becomes a translation rather than a
 * redesign.
 *
 * Two Kotlin-specific extensions sit alongside the generic builder, both because Kotlin's grammar is
 * newline-sensitive in some places and not others:
 *
 *  * [newlineBeforeCurrentToken] with [disableNewlines] / [enableNewlines] / [restoreNewlinesState]. Inside
 *    parentheses a newline means nothing; between statements it terminates one. The stack discipline is what
 *    lets a nested construct restore whatever the enclosing one had set.
 *  * [disableJoiningComplexTokens] and friends. `!in`, `!is` and `as?` are two tokens in the text and one
 *    operation to the grammar, and the join has to be switchable because `!` is also ordinary negation.
 */
interface SyntaxTreeBuilder {

    /** The current token, or null at end of input. Trivia is never current. */
    val tokenType: IElementType?

    /** The text of the current token, or null at end of input. */
    val tokenText: String?

    /** Start offset of the current token, or the end of the input at EOF. */
    val currentOffset: Int

    fun eof(): Boolean

    /** Consume the current token and move to the next non-trivia one. */
    fun advanceLexer()

    /** Open a marker at the current position. */
    fun mark(): Marker

    /** Report a syntax error at the current position without consuming anything. */
    fun error(message: String)

    /**
     * Re-label the current token as [type].
     *
     * This is how a soft keyword becomes one. The lexer hands back IDENTIFIER for `data`, `by` and the rest,
     * and only the grammar knows whether this particular `data` is a modifier or somebody's variable; when it
     * decides, the token in the TREE has to say so, or `KtModifierList` cannot find it.
     */
    fun remapCurrentToken(type: IElementType)

    /**
     * The token [steps] ahead in the raw stream, trivia included. Used where the grammar has to know whether
     * two tokens are adjacent, which trivia-skipping lookahead would hide.
     */
    fun rawLookup(steps: Int): IElementType?

    /** The token [steps] ahead, skipping trivia. `lookAhead(0)` is [tokenType]. */
    fun lookAhead(steps: Int): IElementType?

    /** Did a line break occur between the previous token and this one? False while newlines are disabled. */
    fun newlineBeforeCurrentToken(): Boolean

    fun disableNewlines()
    fun enableNewlines()
    fun restoreNewlinesState()

    fun disableJoiningComplexTokens()
    fun enableJoiningComplexTokens()
    fun restoreJoiningComplexTokensState()

    /** Finish the parse and return the root, with every token in the input present in the tree. */
    fun treeBuilt(rootType: IElementType): AstNode

    /**
     * A position in the token stream that can later become an element, be abandoned, or be rewound to,
     * mirroring `PsiBuilder.Marker`.
     *
     * [rollbackTo] is what makes the grammar's speculative parsing possible and is the single most important
     * operation here: Kotlin needs it wherever a prefix is ambiguous, such as telling a parenthesized
     * expression from a lambda's parameter list.
     */
    interface Marker {
        /** Close this marker as an element of [type], spanning what has been consumed since it was opened. */
        fun done(type: IElementType)

        /** Close it as if the parse had stopped where [before] was opened. */
        fun doneBefore(type: IElementType, before: Marker)

        /** As [doneBefore], and report [errorMessage] at [before]'s position. */
        fun doneBefore(type: IElementType, before: Marker, errorMessage: String)

        /** Close it as a single leaf of [type], discarding the structure inside. */
        fun collapse(type: IElementType)

        /** Abandon the marker, keeping everything parsed since it was opened. */
        fun drop()

        /** Abandon the marker AND rewind the stream to where it was opened. */
        fun rollbackTo()

        /** Open a new marker immediately before this one, to wrap it. */
        fun precede(): Marker

        /** Close this marker as a syntax error carrying [message]. */
        fun error(message: String)

        /** Report [message] as an error at [before]'s position and abandon this marker. */
        fun errorBefore(message: String, before: Marker)
    }
}
