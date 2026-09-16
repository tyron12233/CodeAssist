package dev.ide.kotlin.syntax

import dev.ide.kotlin.syntax.lexer.KtTokens
import dev.ide.kotlin.syntax.parsing.SyntaxTreeBuilderImpl
import dev.ide.kotlin.syntax.tree.TokenType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The builder on its own, driven by hand rather than by the grammar.
 *
 * These are the operations the whole parser is written in, and the ones a grammar port would be debugging
 * blind without: `rollbackTo` after arbitrary work, `precede` to wrap what is already parsed, `doneBefore` to
 * close an element earlier than the cursor. Each is tested here so that a parser failure can be read as a
 * grammar bug rather than a builder bug.
 */
class SyntaxTreeBuilderTest {

    @Test
    fun theTreeCoversTheWholeInput() {
        // Concatenating the leaves must reproduce the source exactly, trivia included. Every offset the
        // analysis layer reports depends on it.
        val text = "fun f() {\n  // hi\n  val x = 1\n}\n"
        val root = KotlinParserFixture.parse(text)
        val rebuilt = root.descendants().filter { it.children.isEmpty() }.joinToString("") { it.text }
        assertEquals(text, rebuilt)
    }

    @Test
    fun markersNestIntoElements() {
        val builder = SyntaxTreeBuilderImpl("a b c")
        val outer = builder.mark()
        builder.advanceLexer()
        val inner = builder.mark()
        builder.advanceLexer()
        inner.done(KtNodeTypes.REFERENCE_EXPRESSION)
        builder.advanceLexer()
        outer.done(KtNodeTypes.BLOCK)
        val root = builder.treeBuilt(KtNodeTypes.KT_FILE)
        assertEquals("(kotlin.FILE (BLOCK IDENTIFIER (REFERENCE_EXPRESSION IDENTIFIER) IDENTIFIER))", root.treeString())
    }

    @Test
    fun rollbackDiscardsEverythingSinceTheMarker() {
        val builder = SyntaxTreeBuilderImpl("a b c")
        val speculative = builder.mark()
        builder.advanceLexer()
        val nested = builder.mark()
        builder.advanceLexer()
        nested.done(KtNodeTypes.REFERENCE_EXPRESSION)
        speculative.rollbackTo()

        // The stream is back at the start, and nothing the abandoned attempt marked survives.
        assertEquals(0, builder.currentOffset)
        val root = builder.treeBuilt(KtNodeTypes.KT_FILE)
        assertEquals("(kotlin.FILE IDENTIFIER IDENTIFIER IDENTIFIER)", root.treeString())
    }

    @Test
    fun precedeWrapsWhatIsAlreadyParsed() {
        // This is how left-associative postfix chains are built: parse the atom, then wrap it.
        val builder = SyntaxTreeBuilderImpl("a b")
        val inner = builder.mark()
        builder.advanceLexer()
        val outer = inner.precede()
        inner.done(KtNodeTypes.REFERENCE_EXPRESSION)
        builder.advanceLexer()
        outer.done(KtNodeTypes.DOT_QUALIFIED_EXPRESSION)
        val root = builder.treeBuilt(KtNodeTypes.KT_FILE)
        assertEquals(
            "(kotlin.FILE (DOT_QUALIFIED_EXPRESSION (REFERENCE_EXPRESSION IDENTIFIER) IDENTIFIER))",
            root.treeString(),
        )
    }

    @Test
    fun doneBeforeClosesAtAnEarlierPosition() {
        val builder = SyntaxTreeBuilderImpl("a b c")
        val first = builder.mark()
        builder.advanceLexer()
        val second = builder.mark()
        builder.advanceLexer()
        second.done(KtNodeTypes.REFERENCE_EXPRESSION)
        // `first` should end where `second` began, not where the cursor is.
        first.doneBefore(KtNodeTypes.BLOCK, second)
        val root = builder.treeBuilt(KtNodeTypes.KT_FILE)
        assertEquals(
            "(kotlin.FILE (BLOCK IDENTIFIER) (REFERENCE_EXPRESSION IDENTIFIER) IDENTIFIER)",
            root.treeString(),
        )
    }

    @Test
    fun droppedMarkersLeaveTheirContent() {
        val builder = SyntaxTreeBuilderImpl("a b")
        val marker = builder.mark()
        builder.advanceLexer()
        marker.drop()
        builder.advanceLexer()
        val root = builder.treeBuilt(KtNodeTypes.KT_FILE)
        assertEquals("(kotlin.FILE IDENTIFIER IDENTIFIER)", root.treeString())
    }

    @Test
    fun errorsBecomeElementsInTheTree() {
        val builder = SyntaxTreeBuilderImpl("a")
        builder.error("something is wrong")
        val root = builder.treeBuilt(KtNodeTypes.KT_FILE)
        assertTrue(root.descendants().any { it.elementType === TokenType.ERROR_ELEMENT })
    }

    @Test
    fun triviaBindsOutsideAnElement() {
        // A declaration's range starts at its first real token, not at the blank line above it.
        val builder = SyntaxTreeBuilderImpl("  a  ")
        val marker = builder.mark()
        builder.advanceLexer()
        marker.done(KtNodeTypes.REFERENCE_EXPRESSION)
        val root = builder.treeBuilt(KtNodeTypes.KT_FILE)
        val element = root.findChildByType(KtNodeTypes.REFERENCE_EXPRESSION)
        assertNotNull(element)
        assertEquals(2, element.startOffset)
        assertEquals(3, element.endOffset)
        assertEquals("a", element.text)
    }

    @Test
    fun theParserNeverSeesTrivia() {
        val builder = SyntaxTreeBuilderImpl("// hi\n  a")
        assertEquals(KtTokens.IDENTIFIER, builder.tokenType)
        assertEquals(8, builder.currentOffset)
    }

    @Test
    fun newlineDetectionFollowsTheEnableStack() {
        val builder = SyntaxTreeBuilderImpl("a\nb")
        builder.advanceLexer()
        assertTrue(builder.newlineBeforeCurrentToken(), "there is a line break before `b`")

        builder.disableNewlines()
        assertFalse(builder.newlineBeforeCurrentToken(), "disabled inside brackets")
        builder.restoreNewlinesState()
        assertTrue(builder.newlineBeforeCurrentToken(), "restored to what the enclosing construct had")
    }

    @Test
    fun complexTokensJoinOnlyWhenAdjacent() {
        // `?.` is one operation; `? .` is a nullable marker and something else.
        assertEquals(KtTokens.SAFE_ACCESS, SyntaxTreeBuilderImpl("?.x").tokenType)
        assertEquals(KtTokens.QUEST, SyntaxTreeBuilderImpl("? .x").tokenType)
        assertEquals(KtTokens.ELVIS, SyntaxTreeBuilderImpl("?: x").tokenType)
        assertEquals(KtTokens.QUEST, SyntaxTreeBuilderImpl("? : x").tokenType)
    }

    @Test
    fun joiningCanBeTurnedOff() {
        // This is what lets a type read `Foo?` where an expression would have read `?.`.
        val builder = SyntaxTreeBuilderImpl("?.x")
        builder.disableJoiningComplexTokens()
        assertEquals(KtTokens.QUEST, builder.tokenType)
        builder.restoreJoiningComplexTokensState()
        assertEquals(KtTokens.SAFE_ACCESS, builder.tokenType)
    }

    @Test
    fun consumingAJoinedTokenStepsOverBothHalves() {
        val builder = SyntaxTreeBuilderImpl("?.x")
        builder.advanceLexer()
        assertEquals(KtTokens.IDENTIFIER, builder.tokenType)
    }

    @Test
    fun lookAheadSkipsTriviaAndRawLookupDoesNot() {
        val builder = SyntaxTreeBuilderImpl("a /* c */ b")
        assertEquals(KtTokens.IDENTIFIER, builder.lookAhead(1))
        assertEquals(TokenType.WHITE_SPACE, builder.rawLookup(1))
    }

    @Test
    fun remappingATokenChangesWhatTheTreeRecords() {
        // Soft-keyword promotion: the lexer said IDENTIFIER, the grammar decided it was a modifier.
        val builder = SyntaxTreeBuilderImpl("data")
        builder.remapCurrentToken(KtTokens.DATA_KEYWORD)
        assertEquals(KtTokens.DATA_KEYWORD, builder.tokenType)
        builder.advanceLexer()
        val root = builder.treeBuilt(KtNodeTypes.KT_FILE)
        assertEquals("(kotlin.FILE DATA_KEYWORD)", root.treeString())
    }

    @Test
    fun rollbackUndoesRemapping() {
        // A speculative read of `data` as a modifier must not leave DATA_KEYWORD behind when it is rewound.
        val builder = SyntaxTreeBuilderImpl("data")
        val marker = builder.mark()
        builder.remapCurrentToken(KtTokens.DATA_KEYWORD)
        builder.advanceLexer()
        marker.rollbackTo()
        assertEquals(KtTokens.IDENTIFIER, builder.tokenType)
        builder.advanceLexer()
        val root = builder.treeBuilt(KtNodeTypes.KT_FILE)
        assertEquals("(kotlin.FILE IDENTIFIER)", root.treeString())
    }
}
