package dev.ide.kotlin.syntax.lexer

import dev.ide.kotlin.syntax.tree.IElementType

/**
 * The Kotlin lexer: text in, a token stream out.
 *
 * It mirrors what `org.jetbrains.kotlin.lexer.KotlinLexer` (JFlex-generated) emits, token for token and
 * offset for offset, which `KotlinLexerOracleTest` checks over this repository's own sources. Two of those
 * shared decisions are worth stating because they look like omissions:
 *
 *  * **Soft keywords come out as IDENTIFIER.** `data`, `value`, `by`, `where` and the modifiers are ordinary
 *    names until the grammar asks for them, so promoting them is the parser's job, not the lexer's.
 *  * **`!in`, `!is` and `as?` come out as separate tokens.** The compiler joins them one layer up, in the
 *    builder, because whether `!` starts a negation or a `!in` depends on what the parser is doing.
 *
 * A string is a token *sequence*, not a token, because interpolations hold arbitrary expressions the parser
 * has to see. That needs real lexer state, and the state is a stack rather than a flag: `"${ "${x}" }"` is
 * legal Kotlin and nests.
 *
 * The lexer never throws and never stops early. Unterminated strings, stray characters and broken escapes
 * all produce tokens ([KtTokens.BAD_CHARACTER], [KtTokens.DANGLING_NEWLINE]) and lexing continues, because
 * every caller is an editor looking at half-typed text.
 */
class KotlinLexer(private val text: CharSequence) {

    private val length = text.length

    /** The kind of the current token, or null once the input is exhausted. */
    var tokenType: IElementType? = null
        private set

    /** Start offset of the current token, inclusive. */
    var tokenStart: Int = 0
        private set

    /** End offset of the current token, exclusive. */
    var tokenEnd: Int = 0
        private set

    /** The text of the current token. */
    val tokenText: CharSequence get() = text.subSequence(tokenStart, tokenEnd)

    // The lexer state stack. Only string interpolation can nest, so this is empty for most input and one or
    // two deep for the rest; a stack rather than a counter because the nesting is genuine.
    private val states = ArrayList<StringState>()
    private var mode = Mode.NORMAL

    // Inside `${ … }` the lexer is in normal mode but has to know which `}` ends the interpolation rather
    // than a block, so it counts the braces opened since the entry started.
    private var braceDepth = 0

    // Set by INTERPOLATION_PREFIX so the OPEN_QUOTE that follows knows how many `$` its interpolations need.
    private var pendingDollars = 1

    private enum class Mode { NORMAL, STRING, RAW_STRING, SHORT_TEMPLATE, LONG_TEMPLATE }

    /**
     * One live string. [raw] separates `"""…"""` (no escapes, newlines allowed) from `"…"`, and [dollars] is
     * how many `$` an interpolation needs — normally one, but `$$"""…"""` (multi-dollar interpolation) says
     * two or more, and a single `$` in such a string is then just a character.
     */
    private class StringState(val raw: Boolean, val dollars: Int, val outerBraceDepth: Int, val outerMode: Mode)

    init {
        advance()
    }

    /** Move to the next token. [tokenType] becomes null at end of input. */
    fun advance() {
        tokenStart = tokenEnd
        if (tokenStart >= length) {
            tokenType = null
            return
        }
        tokenType = when (mode) {
            Mode.NORMAL, Mode.LONG_TEMPLATE -> scanNormal()
            Mode.STRING, Mode.RAW_STRING -> scanStringBody()
            Mode.SHORT_TEMPLATE -> scanShortTemplate()
        }
    }

    /** Every token in [text], as a list. Convenience for tests and for the builder, which wants them all. */
    fun tokenize(): List<Token> {
        val out = ArrayList<Token>()
        while (tokenType != null) {
            out.add(Token(tokenType!!, tokenStart, tokenEnd))
            advance()
        }
        return out
    }

    /** One lexed token: its kind and its half-open range in the original text. */
    class Token(val type: IElementType, val start: Int, val end: Int) {
        override fun toString(): String = "${type.debugName}[$start,$end)"
    }

    // -----------------------------------------------------------------------------------------------------
    // Normal mode (also the inside of a `${ … }` interpolation)
    // -----------------------------------------------------------------------------------------------------

    private fun scanNormal(): IElementType {
        val c = text[tokenStart]

        if (isWhitespace(c)) return whitespace()
        if (c == '#' && tokenStart == 0 && peek(1) == '!') return lineComment(KtTokens.SHEBANG_COMMENT)
        if (c == '/') {
            when (peek(1)) {
                '/' -> return lineComment(KtTokens.EOL_COMMENT)
                '*' -> return blockComment()
            }
        }
        // Inside an interpolation, track braces so the `}` that closes the ENTRY is not read as a block's.
        if (mode == Mode.LONG_TEMPLATE) {
            if (c == '{') {
                braceDepth++
                tokenEnd = tokenStart + 1
                return KtTokens.LBRACE
            }
            if (c == '}') {
                tokenEnd = tokenStart + 1
                if (braceDepth == 0) {
                    mode = if (states.last().raw) Mode.RAW_STRING else Mode.STRING
                    return KtTokens.LONG_TEMPLATE_ENTRY_END
                }
                braceDepth--
                return KtTokens.RBRACE
            }
        }
        if (c.isDigit()) return number()
        if (c == '.' && peek(1).isDigit()) return number()
        if (c == '`') return backtickIdentifier()
        if (isIdentifierStart(c)) return identifierOrKeyword()
        if (c == '\'') return characterLiteral()
        if (c == '"') return openQuote(pendingDollars)
        if (c == '!' && (matches("!in") || matches("!is")) && !isIdentifierPart(peek(3))) {
            // `!in` / `!is` are single tokens, which is why they cannot be written with a space.
            tokenEnd = tokenStart + 3
            return if (peek(1) == 'i' && peek(2) == 'n') KtTokens.NOT_IN else KtTokens.NOT_IS
        }
        if (c == '$') {
            // A run of two or more `$` immediately before a quote is the multi-dollar interpolation prefix.
            var n = 0
            while (tokenStart + n < length && text[tokenStart + n] == '$') n++
            if (n >= 2 && tokenStart + n < length && text[tokenStart + n] == '"') {
                tokenEnd = tokenStart + n
                pendingDollars = n
                return KtTokens.INTERPOLATION_PREFIX
            }
        }
        return operator()
    }

    private fun whitespace(): IElementType {
        var i = tokenStart
        while (i < length && isWhitespace(text[i])) i++
        tokenEnd = i
        return KtTokens.WHITE_SPACE
    }

    private fun lineComment(type: IElementType): IElementType {
        var i = tokenStart
        while (i < length && text[i] != '\n' && text[i] != '\r') i++
        tokenEnd = i
        return type
    }

    /**
     * A block comment, which nests in Kotlin, so a depth counter rather than a search for the first close.
     * A comment opening with two stars is a doc comment, but the empty one is a plain block comment rather
     * than a doc comment that never opened.
     */
    private fun blockComment(): IElementType {
        val doc = peek(2) == '*' && peek(3) != '/'
        var i = tokenStart + 2
        var depth = 1
        while (i < length) {
            if (text[i] == '/' && i + 1 < length && text[i + 1] == '*') {
                depth++
                i += 2
            } else if (text[i] == '*' && i + 1 < length && text[i + 1] == '/') {
                depth--
                i += 2
                if (depth == 0) break
            } else {
                i++
            }
        }
        tokenEnd = i
        return if (doc) KtTokens.DOC_COMMENT else KtTokens.BLOCK_COMMENT
    }

    private fun number(): IElementType {
        var i = tokenStart
        var isFloat = false

        if (text[i] == '0' && i + 1 < length && (text[i + 1] == 'x' || text[i + 1] == 'X')) {
            i += 2
            while (i < length && (isHexDigit(text[i]) || text[i] == '_')) i++
        } else if (text[i] == '0' && i + 1 < length && (text[i + 1] == 'b' || text[i + 1] == 'B')) {
            i += 2
            while (i < length && (text[i] == '0' || text[i] == '1' || text[i] == '_')) i++
        } else {
            if (text[i] == '.') isFloat = true
            while (i < length && (text[i].isDigit() || text[i] == '_')) i++
            // `.` continues the number only when a digit follows: `1..2` is a range, `1.toString()` is a call.
            if (i < length && text[i] == '.' && i + 1 < length && text[i + 1].isDigit()) {
                isFloat = true
                i++
                while (i < length && (text[i].isDigit() || text[i] == '_')) i++
            }
            if (i < length && (text[i] == 'e' || text[i] == 'E')) {
                // `1e` and `1e+` are malformed, but they are malformed FLOATS: the exponent marker commits
                // the literal, and reporting it as an integer followed by a name misplaces the error.
                isFloat = true
                i++
                if (i < length && (text[i] == '+' || text[i] == '-')) i++
                while (i < length && (text[i].isDigit() || text[i] == '_')) i++
            }
            if (i < length && (text[i] == 'f' || text[i] == 'F')) {
                tokenEnd = i + 1
                return KtTokens.FLOAT_LITERAL
            }
        }

        // Integer suffixes: `u`/`U` (unsigned), `L` (long), and `uL` together.
        if (!isFloat) {
            if (i < length && (text[i] == 'u' || text[i] == 'U')) i++
            if (i < length && text[i] == 'L') i++
        }
        tokenEnd = i
        return if (isFloat) KtTokens.FLOAT_LITERAL else KtTokens.INTEGER_LITERAL
    }

    private fun identifierOrKeyword(): IElementType {
        var i = tokenStart
        while (i < length && isIdentifierPart(text[i])) i++
        tokenEnd = i
        val word = text.substring(tokenStart, i)
        if (word == "as" && i < length && text[i] == '?') {
            tokenEnd = i + 1
            return KtTokens.AS_SAFE
        }
        // Hard keywords only. A soft keyword is an identifier here and is promoted by the parser.
        return KtTokens.HARD_KEYWORD_BY_TEXT[word] ?: KtTokens.IDENTIFIER
    }

    /**
     * A backtick-quoted name.
     *
     * An UNTERMINATED one is a stray character rather than a name running to the end of the line: the text
     * after it is still ordinary code and has to lex as such, which is what keeps a half-typed backtick from
     * turning the rest of the file red.
     */
    private fun backtickIdentifier(): IElementType {
        var i = tokenStart + 1
        while (i < length && text[i] != '`' && text[i] != '\n' && text[i] != '\r') i++
        if (i >= length || text[i] != '`') {
            tokenEnd = tokenStart + 1
            return KtTokens.BAD_CHARACTER
        }
        tokenEnd = i + 1
        return KtTokens.IDENTIFIER
    }

    private fun characterLiteral(): IElementType {
        var i = tokenStart + 1
        while (i < length && text[i] != '\'' && text[i] != '\n' && text[i] != '\r') {
            i += if (text[i] == '\\' && i + 1 < length) 2 else 1
        }
        tokenEnd = if (i < length && text[i] == '\'') i + 1 else i
        return KtTokens.CHARACTER_LITERAL
    }

    private fun openQuote(dollars: Int): IElementType {
        val raw = peek(1) == '"' && peek(2) == '"'
        tokenEnd = tokenStart + if (raw) 3 else 1
        states.add(StringState(raw, dollars, braceDepth, mode))
        braceDepth = 0
        mode = if (raw) Mode.RAW_STRING else Mode.STRING
        pendingDollars = 1
        return KtTokens.OPEN_QUOTE
    }

    private fun operator(): IElementType {
        // Longest match first: the three-character forms must be tried before the two, and those before the
        // one, or `..<` lexes as `..` then `<` and `!==` as `!=` then `=`.
        for (op in OPERATORS) {
            if (matches(op.value)) {
                tokenEnd = tokenStart + op.value.length
                return op
            }
        }
        tokenEnd = tokenStart + 1
        return KtTokens.BAD_CHARACTER
    }

    // -----------------------------------------------------------------------------------------------------
    // String modes
    // -----------------------------------------------------------------------------------------------------

    private fun scanStringBody(): IElementType {
        val state = states.last()
        val c = text[tokenStart]

        if (c == '"') {
            if (!state.raw) return closeString(1)
            // In a raw string the closing delimiter is the LAST three quotes of a run, so any quote that is
            // not part of that final triple is literal text. Those are emitted ONE AT A TIME rather than as a
            // run, which is what the compiler does and what keeps the two token streams identical.
            var n = 0
            while (tokenStart + n < length && text[tokenStart + n] == '"') n++
            if (n != 3) {
                tokenEnd = tokenStart + 1
                return KtTokens.REGULAR_STRING_PART
            }
            return closeString(3)
        }

        if (c == '\\') {
            if (!state.raw) return escapeSequence()
            // A raw string has no escapes, but the backslash is still its own part.
            tokenEnd = tokenStart + 1
            return KtTokens.REGULAR_STRING_PART
        }

        if (state.raw && (c == '\n' || c == '\r')) {
            // A line break inside a raw string is legal and is its own part, so a part never spans lines.
            tokenEnd = tokenStart + 1
            return KtTokens.REGULAR_STRING_PART
        }

        if (c == '$' && !dollarRunStartsTemplate(state)) {
            // A dollar that opens no interpolation is literal text, but still a part of its own.
            var n = 0
            while (tokenStart + n < length && text[tokenStart + n] == '$') n++
            tokenEnd = tokenStart + n
            return KtTokens.REGULAR_STRING_PART
        }

        if (c == '$' && dollarRunStartsTemplate(state)) {
            val after = tokenStart + state.dollars
            if (text[after] == '{') {
                tokenEnd = after + 1
                braceDepth = 0
                mode = Mode.LONG_TEMPLATE
                return KtTokens.LONG_TEMPLATE_ENTRY_START
            }
            tokenEnd = after
            mode = Mode.SHORT_TEMPLATE
            return KtTokens.SHORT_TEMPLATE_ENTRY_START
        }

        if (!state.raw && (c == '\n' || c == '\r')) {
            // A newline ends a non-raw string. What is reported is a ZERO-WIDTH marker at the break: the
            // newline itself is ordinary whitespace, and the code after it has to keep lexing as code, which
            // is what makes a half-typed quote survivable.
            tokenEnd = tokenStart
            popString()
            return KtTokens.DANGLING_NEWLINE
        }

        var i = tokenStart
        while (i < length) {
            val ch = text[i]
            if (ch == '"') break
            if (ch == '\\') break
            if (ch == '$') break
            if (ch == '\n' || ch == '\r') break
            i++
        }
        tokenEnd = i
        return KtTokens.REGULAR_STRING_PART
    }

    /**
     * Does the dollar run at [at] open an interpolation? It must be exactly [StringState.dollars] long (so a
     * lone `$` in a multi-dollar string is literal text) and be followed by a brace or a name.
     */
    private fun dollarRunStartsTemplate(state: StringState, at: Int = tokenStart): Boolean {
        var n = 0
        while (at + n < length && text[at + n] == '$') n++
        if (n != state.dollars) return false
        val next = at + n
        if (next >= length) return false
        return text[next] == '{' || isIdentifierStart(text[next])
    }

    private fun escapeSequence(): IElementType {
        var i = tokenStart + 1
        if (i < length) {
            if (text[i] == 'u') {
                i++
                var digits = 0
                while (i < length && digits < 4 && isHexDigit(text[i])) {
                    i++
                    digits++
                }
            } else {
                i++
            }
        }
        tokenEnd = i
        return KtTokens.ESCAPE_SEQUENCE
    }

    private fun closeString(quoteLength: Int): IElementType {
        tokenEnd = tokenStart + quoteLength
        popString()
        return KtTokens.CLOSING_QUOTE
    }

    private fun popString() {
        val state = states.removeAt(states.size - 1)
        braceDepth = state.outerBraceDepth
        mode = state.outerMode
    }

    /** A short interpolation: exactly one name, then straight back into the string. */
    private fun scanShortTemplate(): IElementType {
        val type = if (isIdentifierStart(text[tokenStart])) {
            identifierOrKeyword()
        } else {
            tokenEnd = tokenStart + 1
            KtTokens.BAD_CHARACTER
        }
        mode = if (states.last().raw) Mode.RAW_STRING else Mode.STRING
        return type
    }

    // -----------------------------------------------------------------------------------------------------

    private fun matches(s: String): Boolean {
        if (tokenStart + s.length > length) return false
        for (k in s.indices) if (text[tokenStart + k] != s[k]) return false
        return true
    }

    private fun peek(offset: Int): Char =
        if (tokenStart + offset < length) text[tokenStart + offset] else ' '

    private companion object {
        /**
         * Every fixed-spelling token, longest first. Order is the whole correctness argument here: a shorter
         * operator that is a prefix of a longer one must come after it.
         */
        val OPERATORS: List<KtSingleValueToken> = listOf(
            KtTokens.EXCLEQEQEQ, KtTokens.EQEQEQ, KtTokens.RANGE_UNTIL,
            KtTokens.EXCLEQ, KtTokens.EQEQ, KtTokens.LTEQ, KtTokens.GTEQ,
            KtTokens.ANDAND, KtTokens.OROR, KtTokens.COLONCOLON,
            KtTokens.RANGE, KtTokens.MULTEQ, KtTokens.DIVEQ, KtTokens.PERCEQ, KtTokens.PLUSEQ,
            KtTokens.MINUSEQ, KtTokens.PLUSPLUS, KtTokens.MINUSMINUS, KtTokens.ARROW, KtTokens.DOUBLE_ARROW,
            KtTokens.DOUBLE_SEMICOLON,
            KtTokens.LPAR, KtTokens.RPAR, KtTokens.LBRACKET, KtTokens.RBRACKET, KtTokens.LBRACE,
            KtTokens.RBRACE, KtTokens.MUL, KtTokens.PLUS, KtTokens.MINUS, KtTokens.EXCL, KtTokens.DIV,
            KtTokens.PERC, KtTokens.LT, KtTokens.GT, KtTokens.AND, KtTokens.QUEST, KtTokens.COLON,
            KtTokens.SEMICOLON, KtTokens.EQ, KtTokens.HASH, KtTokens.AT, KtTokens.COMMA, KtTokens.DOT,
        )

        /** Space, tab, the two line terminators, and the form feed the compiler also treats as blank. */
        fun isWhitespace(c: Char): Boolean =
            c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C'

        fun isHexDigit(c: Char): Boolean = c.isDigit() || c in 'a'..'f' || c in 'A'..'F'

        fun isIdentifierStart(c: Char): Boolean = c == '_' || c.isLetter()

        fun isIdentifierPart(c: Char): Boolean = c == '_' || c.isLetterOrDigit()
    }
}
