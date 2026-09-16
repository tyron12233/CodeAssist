package dev.ide.kotlin.syntax

import dev.ide.kotlin.syntax.lexer.KotlinLexer
import dev.ide.kotlin.syntax.lexer.KtTokens
import dev.ide.kotlin.syntax.tree.IElementType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lexer, case by case.
 *
 * Two properties are asserted everywhere rather than in one place, because both are contracts the rest of the
 * stack leans on: the token stream COVERS the input with no gaps and no overlaps, and lexing never throws on
 * broken text. `KotlinLexerOracleTest` then checks the same lexer against the real compiler's over this
 * repository's sources; these tests are what say what the behaviour is supposed to be.
 */
class KotlinLexerTest {

    private fun lex(text: String): List<KotlinLexer.Token> {
        val tokens = KotlinLexer(text).tokenize()
        assertCoversInput(text, tokens)
        return tokens
    }

    /** Every offset of the input belongs to exactly one token, in order. */
    private fun assertCoversInput(text: String, tokens: List<KotlinLexer.Token>) {
        var offset = 0
        for (token in tokens) {
            assertEquals(offset, token.start, "gap or overlap before $token in ${text.take(60)}")
            // Zero-width tokens are legal and rare: DANGLING_NEWLINE marks the break that ends an
            // unterminated string without consuming the newline, which stays ordinary whitespace.
            assertTrue(token.end >= token.start, "negative-width token $token")
            offset = token.end
        }
        assertEquals(text.length, offset, "lexer stopped before the end of the input")
    }

    private fun types(text: String): List<IElementType> = lex(text).map { it.type }

    private fun significant(text: String): List<IElementType> =
        types(text).filter { it !== KtTokens.WHITE_SPACE }

    // --- names and keywords ------------------------------------------------------------------------------

    @Test
    fun hardKeywordsLexAsKeywords() {
        assertEquals(listOf(KtTokens.CLASS_KEYWORD, KtTokens.IDENTIFIER), significant("class Foo"))
        assertEquals(listOf(KtTokens.FUN_KEYWORD, KtTokens.IDENTIFIER), significant("fun main"))
        assertEquals(listOf(KtTokens.VAL_KEYWORD, KtTokens.IDENTIFIER), significant("val x"))
    }

    @Test
    fun softKeywordsLexAsIdentifiers() {
        // The lexer cannot know whether `data` modifies a class or names a variable; the parser decides.
        assertEquals(listOf(KtTokens.IDENTIFIER, KtTokens.CLASS_KEYWORD, KtTokens.IDENTIFIER), significant("data class A"))
        assertEquals(listOf(KtTokens.VAL_KEYWORD, KtTokens.IDENTIFIER, KtTokens.EQ, KtTokens.INTEGER_LITERAL), significant("val data = 1"))
    }

    @Test
    fun backtickedNamesAreOneIdentifier() {
        val tokens = lex("`name with spaces`")
        assertEquals(1, tokens.size)
        assertEquals(KtTokens.IDENTIFIER, tokens[0].type)
    }

    @Test
    fun anUnterminatedBacktickIsAStrayCharacter() {
        // A half-typed backtick must not swallow the rest of the file, or the line turns red as it is typed.
        val tokens = lex("`oops\nval x = 1")
        assertEquals(KtTokens.BAD_CHARACTER, tokens[0].type)
        assertEquals(1, tokens[0].end)
        assertTrue(tokens.any { it.type === KtTokens.VAL_KEYWORD })
    }

    // --- numbers -----------------------------------------------------------------------------------------

    @Test
    fun integerForms() {
        assertEquals(listOf(KtTokens.INTEGER_LITERAL), significant("0xFF"))
        assertEquals(listOf(KtTokens.INTEGER_LITERAL), significant("0b1010"))
        assertEquals(listOf(KtTokens.INTEGER_LITERAL), significant("1_000_000"))
        assertEquals(listOf(KtTokens.INTEGER_LITERAL), significant("42L"))
        assertEquals(listOf(KtTokens.INTEGER_LITERAL), significant("42uL"))
    }

    @Test
    fun floatForms() {
        assertEquals(listOf(KtTokens.FLOAT_LITERAL), significant("1.5"))
        assertEquals(listOf(KtTokens.FLOAT_LITERAL), significant("1e10"))
        assertEquals(listOf(KtTokens.FLOAT_LITERAL), significant("1.5e-3"))
        assertEquals(listOf(KtTokens.FLOAT_LITERAL), significant("2.0f"))
    }

    @Test
    fun aDotAfterDigitsIsOnlyAFloatWhenDigitsFollow() {
        // `1..2` is a range and `1.toString()` is a call; neither is a float, and both used to be.
        assertEquals(
            listOf(KtTokens.INTEGER_LITERAL, KtTokens.RANGE, KtTokens.INTEGER_LITERAL),
            significant("1..2"),
        )
        assertEquals(
            listOf(KtTokens.INTEGER_LITERAL, KtTokens.DOT, KtTokens.IDENTIFIER, KtTokens.LPAR, KtTokens.RPAR),
            significant("1.toString()"),
        )
    }

    // --- operators ---------------------------------------------------------------------------------------

    @Test
    fun longestOperatorWins() {
        assertEquals(listOf(KtTokens.EXCLEQEQEQ), significant("!=="))
        assertEquals(listOf(KtTokens.EQEQEQ), significant("==="))
        assertEquals(listOf(KtTokens.RANGE_UNTIL), significant("..<"))
        assertEquals(listOf(KtTokens.COLONCOLON), significant("::"))
    }

    @Test
    fun adjacencyIsPartOfTheSpellingForSomeOperators() {
        // `!in`, `!is` and `as?` cannot be written with a space, so they are single tokens.
        assertEquals(listOf(KtTokens.NOT_IN), significant("!in"))
        assertEquals(listOf(KtTokens.NOT_IS), significant("!is"))
        assertEquals(listOf(KtTokens.AS_SAFE), significant("as?"))
        assertEquals(listOf(KtTokens.EXCL, KtTokens.IN_KEYWORD), significant("! in"))
    }

    @Test
    fun theQuestionMarkPairsAndDoubleBangAreLeftToTheParser() {
        // `?.`, `?:` and `!!` are all ambiguous in isolation: `Foo?` is a nullable type and `!!x` is a double
        // negation, so the lexer keeps the pieces apart and the layers above decide.
        assertEquals(listOf(KtTokens.QUEST, KtTokens.DOT), significant("?."))
        assertEquals(listOf(KtTokens.QUEST, KtTokens.COLON), significant("?:"))
        assertEquals(listOf(KtTokens.EXCL, KtTokens.EXCL), significant("!!"))
    }

    // --- comments ----------------------------------------------------------------------------------------

    @Test
    fun commentKinds() {
        assertEquals(listOf(KtTokens.EOL_COMMENT), significant("// hello"))
        assertEquals(listOf(KtTokens.BLOCK_COMMENT), significant("/* hello */"))
        assertEquals(listOf(KtTokens.DOC_COMMENT), significant("/** hello */"))
    }

    @Test
    fun emptyBlockCommentIsNotADocComment() {
        assertEquals(listOf(KtTokens.BLOCK_COMMENT), significant("/**/"))
    }

    @Test
    fun blockCommentsNest() {
        // Kotlin nests them, so the first close does not end the outer comment.
        val tokens = lex("/* a /* b */ c */x")
        assertEquals(KtTokens.BLOCK_COMMENT, tokens[0].type)
        assertEquals(17, tokens[0].end)
        assertEquals(KtTokens.IDENTIFIER, tokens[1].type)
    }

    @Test
    fun shebangOnlyCountsAtTheStartOfTheFile() {
        assertEquals(KtTokens.SHEBANG_COMMENT, lex("#!/usr/bin/env kotlin\nval x = 1")[0].type)
        assertTrue(lex("val x = 1\n#!nope").none { it.type === KtTokens.SHEBANG_COMMENT })
    }

    // --- strings -----------------------------------------------------------------------------------------

    @Test
    fun plainStringIsASequence() {
        assertEquals(
            listOf(KtTokens.OPEN_QUOTE, KtTokens.REGULAR_STRING_PART, KtTokens.CLOSING_QUOTE),
            significant("\"abc\""),
        )
    }

    @Test
    fun shortInterpolation() {
        assertEquals(
            listOf(
                KtTokens.OPEN_QUOTE, KtTokens.REGULAR_STRING_PART, KtTokens.SHORT_TEMPLATE_ENTRY_START,
                KtTokens.IDENTIFIER, KtTokens.CLOSING_QUOTE,
            ),
            significant("\"hi \$name\""),
        )
    }

    @Test
    fun longInterpolation() {
        assertEquals(
            listOf(
                KtTokens.OPEN_QUOTE, KtTokens.LONG_TEMPLATE_ENTRY_START, KtTokens.IDENTIFIER, KtTokens.DOT,
                KtTokens.IDENTIFIER, KtTokens.LONG_TEMPLATE_ENTRY_END, KtTokens.CLOSING_QUOTE,
            ),
            significant("\"\${a.b}\""),
        )
    }

    @Test
    fun interpolationsNest() {
        // The lexer state has to be a stack, not a flag: a string inside an interpolation inside a string.
        val tokens = significant("\"\${ \"\${x}\" }\"")
        assertEquals(2, tokens.count { it === KtTokens.OPEN_QUOTE })
        assertEquals(2, tokens.count { it === KtTokens.CLOSING_QUOTE })
        assertEquals(2, tokens.count { it === KtTokens.LONG_TEMPLATE_ENTRY_START })
        assertEquals(2, tokens.count { it === KtTokens.LONG_TEMPLATE_ENTRY_END })
    }

    @Test
    fun bracesInsideAnInterpolationDoNotCloseIt() {
        val tokens = significant("\"\${ run { x } }\"")
        assertEquals(1, tokens.count { it === KtTokens.LONG_TEMPLATE_ENTRY_END })
        assertEquals(1, tokens.count { it === KtTokens.LBRACE })
        assertEquals(1, tokens.count { it === KtTokens.RBRACE })
    }

    @Test
    fun escapeSequences() {
        assertEquals(
            listOf(KtTokens.OPEN_QUOTE, KtTokens.ESCAPE_SEQUENCE, KtTokens.CLOSING_QUOTE),
            significant("\"\\n\""),
        )
        assertEquals(
            listOf(KtTokens.OPEN_QUOTE, KtTokens.ESCAPE_SEQUENCE, KtTokens.CLOSING_QUOTE),
            significant("\"\\u00e9\""),
        )
    }

    @Test
    fun rawStringsKeepBackslashesAndNewlines() {
        val tokens = significant("\"\"\"a\\nb\nc\"\"\"")
        assertEquals(KtTokens.OPEN_QUOTE, tokens.first())
        assertEquals(KtTokens.CLOSING_QUOTE, tokens.last())
        assertTrue(tokens.none { it === KtTokens.ESCAPE_SEQUENCE }, "a raw string has no escapes")
        assertTrue(tokens.none { it === KtTokens.DANGLING_NEWLINE }, "a raw string may span lines")
    }

    @Test
    fun rawStringClosingIsTheLastThreeQuotesOfARun() {
        // `""""` is one literal quote and then the terminator.
        val tokens = lex("\"\"\"a\"\"\"\"")
        val closing = tokens.last { it.type === KtTokens.CLOSING_QUOTE }
        assertEquals(8, closing.end)
        assertEquals(5, closing.start)
    }

    @Test
    fun aNewlineEndsAnUnterminatedString() {
        // The rest of the file must stay readable, which is what makes a half-typed quote survivable.
        val tokens = significant("\"oops\nval x = 1")
        assertTrue(tokens.contains(KtTokens.DANGLING_NEWLINE))
        // The marker is zero width: the newline itself is whitespace, and the code after it is still code.
        assertTrue(tokens.contains(KtTokens.VAL_KEYWORD))
    }

    @Test
    fun multiDollarInterpolationNeedsTheFullRun() {
        // In a `$$` string a single `$` is literal text, not an interpolation.
        val tokens = significant("\$\$\"\"\"a \$b \$\$c\"\"\"")
        assertEquals(1, tokens.count { it === KtTokens.INTERPOLATION_PREFIX })
        assertEquals(1, tokens.count { it === KtTokens.SHORT_TEMPLATE_ENTRY_START })
    }

    // --- robustness --------------------------------------------------------------------------------------

    @Test
    fun brokenInputStillProducesAFullTokenStream() {
        for (text in BROKEN_SOURCES) {
            val tokens = KotlinLexer(text).tokenize()
            assertCoversInput(text, tokens)
        }
    }

    @Test
    fun emptyInputProducesNoTokens() {
        assertEquals(emptyList(), KotlinLexer("").tokenize())
    }

    @Test
    fun strayCharactersBecomeBadCharacters() {
        assertEquals(listOf(KtTokens.BAD_CHARACTER), significant("\\"))
    }

    private companion object {
        /** Half-typed and malformed input, the normal case for an editor. */
        val BROKEN_SOURCES = listOf(
            "val x = \"unterminated",
            "fun f(",
            "class {",
            "\"\${",
            "/* unterminated",
            "'",
            "`",
            "\$",
            "0x",
            "1e",
            "val x = 1 @#%^&",
            "\"\"\"unterminated raw",
        )
    }
}
