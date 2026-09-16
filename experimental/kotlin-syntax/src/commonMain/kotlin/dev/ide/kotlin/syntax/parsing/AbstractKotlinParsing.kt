package dev.ide.kotlin.syntax.parsing

import dev.ide.kotlin.syntax.lexer.KtKeywordToken
import dev.ide.kotlin.syntax.lexer.KtTokens
import dev.ide.kotlin.syntax.tree.IElementType
import dev.ide.kotlin.syntax.tree.TokenSet

/**
 * The vocabulary both halves of the grammar are written in, mirroring
 * `org.jetbrains.kotlin.parsing.AbstractKotlinParsing`.
 *
 * Nothing here is clever. It exists so that `KotlinParsing` and `KotlinExpressionParsing` read as grammar
 * rules rather than as token bookkeeping, and so the one genuinely subtle rule — soft-keyword promotion in
 * [at] — lives in exactly one place.
 */
abstract class AbstractKotlinParsing(protected val builder: SyntaxTreeBuilder) {

    protected fun mark(): SyntaxTreeBuilder.Marker = builder.mark()

    protected fun tt(): IElementType? = builder.tokenType

    protected fun eof(): Boolean = builder.eof()

    protected fun advance() = builder.advanceLexer()

    protected fun error(message: String) = builder.error(message)

    /**
     * Is the current token [expectation]?
     *
     * The subtlety is soft keywords. The lexer cannot know whether `data` is a modifier or a variable called
     * data, so it always says IDENTIFIER, and this is where the grammar decides. When the grammar asks for a
     * soft keyword and the text matches, the token is PROMOTED in place ([SyntaxTreeBuilder.remapCurrentToken])
     * so the tree records what it turned out to be. The mirror case matters just as much: code that asks for
     * an IDENTIFIER accepts any soft keyword, which is what keeps `val data = 1` legal.
     */
    protected fun at(expectation: IElementType): Boolean {
        val token = tt() ?: return false
        if (token === expectation) return true
        if (expectation === KtTokens.IDENTIFIER && token is KtKeywordToken && token.isSoft) return true
        if (token === KtTokens.IDENTIFIER && expectation is KtKeywordToken && expectation.isSoft) {
            if (expectation.value == builder.tokenText) {
                builder.remapCurrentToken(expectation)
                return true
            }
        }
        return false
    }

    protected fun atSet(set: TokenSet): Boolean {
        val token = tt() ?: return false
        if (token in set) return true
        // A soft keyword arrives as IDENTIFIER, so a set of them has to be matched by text.
        if (token === KtTokens.IDENTIFIER) {
            val text = builder.tokenText ?: return false
            for (element in set.elements) {
                if (element is KtKeywordToken && element.isSoft && element.value == text) {
                    builder.remapCurrentToken(element)
                    return true
                }
            }
        }
        return false
    }

    protected fun atSet(vararg tokens: IElementType): Boolean = tokens.any { at(it) }

    /** The token [steps] ahead, skipping trivia. */
    protected fun lookahead(steps: Int): IElementType? = builder.lookAhead(steps)

    /** Consume the current token if it is [token]; report nothing and consume nothing otherwise. */
    protected fun consumeIf(token: IElementType): Boolean {
        if (!at(token)) return false
        advance()
        return true
    }

    /** Consume [token] or report [message] where it should have been, without consuming anything else. */
    protected fun expect(token: IElementType, message: String): Boolean {
        if (at(token)) {
            advance()
            return true
        }
        error(message)
        return false
    }

    /**
     * As [expect], but when the expected token is missing, skip forward over tokens that cannot start
     * anything in [recoverySet] first. This is what keeps one typo from cascading: the parser resynchronises
     * at the next construct it recognises instead of reporting an error per token to the end of the file.
     */
    protected fun expect(token: IElementType, message: String, recoverySet: TokenSet): Boolean {
        if (at(token)) {
            advance()
            return true
        }
        errorWithRecovery(message, recoverySet)
        return false
    }

    protected fun errorWithRecovery(message: String, recoverySet: TokenSet) {
        if (atSet(recoverySet) || eof()) {
            error(message)
            return
        }
        val marker = mark()
        advance()
        marker.error(message)
    }

    /** Report [message] and consume everything up to (not including) the next token in [recoverySet]. */
    protected fun errorUntil(message: String, recoverySet: TokenSet) {
        val marker = mark()
        while (!eof() && !atSet(recoverySet)) advance()
        marker.error(message)
    }

    /** Consume a statement separator: a semicolon, a line break, or the end of a block. */
    protected fun consumeSemis() {
        while (at(KtTokens.SEMICOLON)) advance()
    }

    /** Is the current position a statement boundary, by semicolon, newline, `}` or end of input? */
    protected fun atStatementEnd(): Boolean =
        eof() || at(KtTokens.SEMICOLON) || at(KtTokens.RBRACE) || builder.newlineBeforeCurrentToken()
}
