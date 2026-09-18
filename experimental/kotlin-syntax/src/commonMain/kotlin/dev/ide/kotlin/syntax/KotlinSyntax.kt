package dev.ide.kotlin.syntax

import com.intellij.platform.syntax.SyntaxElementType
import com.intellij.platform.syntax.lexer.TokenList
import com.intellij.platform.syntax.lexer.performLexing
import com.intellij.platform.syntax.parser.SyntaxTreeBuilder
import com.intellij.platform.syntax.parser.SyntaxTreeBuilderFactory
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtTreeSession
import org.jetbrains.kotlin.kmp.lexer.KDocLexer
import org.jetbrains.kotlin.kmp.lexer.KotlinLexer
import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.jetbrains.kotlin.kmp.parser.AbstractParser
import org.jetbrains.kotlin.kmp.parser.KDocLinkParser
import org.jetbrains.kotlin.kmp.parser.KDocParser
import org.jetbrains.kotlin.kmp.parser.KotlinParser
import org.jetbrains.kotlin.kmp.parser.utils.KotlinParsing
import org.jetbrains.kotlin.kmp.parser.utils.SemanticWhitespaceAwareSyntaxBuilderImpl
import org.jetbrains.kotlin.kmp.tree.LightNode
import org.jetbrains.kotlin.kmp.tree.LightSyntaxTree
import org.jetbrains.kotlin.kmp.tree.buildLanguageSpecificLightTree

/**
 * Text in, a parse tree out, using the Kotlin compiler's own grammar.
 *
 * The parser underneath is vendored from `compiler/multiplatform-parsing` (see `vendor/VENDOR.md`), so the
 * tree this returns is the one the compiler produces, not an approximation of it.
 *
 * The incantation in [parse] is not obvious and is the reason this file exists: the parser does not build a
 * tree, it drives a `SyntaxTreeBuilder`, and the tree is a second pass over the production markers that
 * builder recorded.
 */
object KotlinSyntax {

    /**
     * Parse [text] as a Kotlin file, or as a script when [isScript].
     *
     * [lazy] is the one that matters for an editor. In lazy mode the parser does not descend into function
     * bodies at all: it counts braces past them and collapses each into a single `BLOCK` node, which is what
     * PSI's lazy-parseable blocks do and is dramatically cheaper, because bodies are most of a file. The
     * interior of one is parsed on demand by [parseBlock].
     *
     * Never throws and never returns null, whatever the input. Callers here are editors, looking at code that
     * is wrong by definition halfway through being typed.
     */
    fun parse(
        text: CharSequence,
        isScript: Boolean = false,
        lazy: Boolean = false,
        tokens: TokenList? = null,
    ): LightSyntaxTree =
        build(text, startOffset = 0, parser = KotlinParser(isScript = isScript, isLazy = lazy), tokens = tokens)

    /**
     * Parse one block in isolation. [blockText] is the block's own text, braces included.
     *
     * **Offsets in the result are RELATIVE to [blockText]**, and the caller adds where the block starts. That
     * is worth stating loudly, because the obvious alternative does not work and fails quietly:
     * `withStartOffset` shifts the positions of elements the parser MARKS but not the tokens, which carry the
     * offsets they were lexed at. Ask for a sub-range that way and you get a tree whose composites are
     * absolute and whose tokens are relative — and, since the builder still consumes from the first token,
     * one that parsed the whole file rather than the block.
     *
     * `IncrementalKotlinParse.blockAt` does the translation; see `ExpandedBlock`.
     */
    fun parseBlock(blockText: CharSequence): LightSyntaxTree = build(blockText, 0, BlockParser, null)

    /**
     * Parse the text of a doc comment, `/**` and `*/` included.
     *
     * A separate grammar and a separate LEXER: the file parse leaves a doc comment as one DOC_COMMENT token,
     * exactly as it leaves a lazily-parsed body as one BLOCK, and the compiler keeps `KDocParser` for the
     * inside. Offsets are relative to [text], as with [parseBlock]; `KtTreeSession.baseOffset` is what turns
     * them back into file coordinates.
     */
    fun parseKDoc(text: CharSequence): LightSyntaxTree =
        build(text, 0, KDocParser, performLexing(text, KDocLexer(), cancellationProvider = null, logger = null))

    /**
     * Parse the inside of a `[Foo.bar]` link.
     *
     * A THIRD grammar, and back to the Kotlin lexer: the text between the brackets is Kotlin, which is why the
     * KDoc lexer that produced the link token cannot also read it.
     */
    fun parseKDocLink(text: CharSequence): LightSyntaxTree = build(text, 0, KDocLinkParser, null)

    /** Lex [text] once, for a caller that will parse it more than once. */
    fun lex(text: CharSequence): TokenList =
        performLexing(text, KotlinLexer(), cancellationProvider = null, logger = null)

    private fun build(
        text: CharSequence,
        startOffset: Int,
        parser: AbstractParser,
        tokens: TokenList? = null,
    ): LightSyntaxTree {
        val builder = SyntaxTreeBuilderFactory.builder(
            text,
            tokens ?: lex(text),
            whitespaces = parser.whitespaces,
            comments = parser.comments,
        ).withStartOffset(startOffset)
            .withWhitespaceOrCommentBindingPolicy(parser.whitespaceOrCommentBindingPolicy)
            .build()

        parser.parse(builder)

        return buildLanguageSpecificLightTree(
            builder = builder,
            source = text,
            buildLanguageSpecificTreeStructure = { it },
            isComment = { it in parser.comments },
        )
    }

    /**
     * Parse [text] and return it behind the `Kt*` facade, which is what callers normally want.
     *
     * [name] is carried rather than derived: nothing in the text says what file it came from, and the symbol
     * layer keys declarations on the file name.
     */
    fun parseFile(
        text: CharSequence,
        isScript: Boolean = false,
        lazy: Boolean = false,
        name: String = "dummy.kt",
    ): KtFile = KtTreeSession(parse(text, isScript, lazy), name).file

    /** Drives the vendored grammar's block rule rather than its file rule. */
    private object BlockParser : AbstractParser() {
        override val whitespaces: Set<SyntaxElementType> = KtTokens.WHITESPACES
        override val comments: Set<SyntaxElementType> = KtTokens.COMMENTS

        override fun parse(builder: SyntaxTreeBuilder) {
            // Non-lazy on purpose: expanding a block and then collapsing the blocks inside it would defeat
            // the point of having asked.
            KotlinParsing.createForTopLevelNonLazy(SemanticWhitespaceAwareSyntaxBuilderImpl(builder))
                .parseBlockExpression()
        }
    }
}

/**
 * A one-line rendering of the tree, used by the tests and by the parity oracle: an element as
 * `(TYPE child child)` and a token as its type name.
 *
 * Trivia is excluded by default, because an oracle that compared it would be measuring the whitespace
 * binders rather than the grammar.
 */
fun LightSyntaxTree.render(includeTrivia: Boolean = false): String {
    val out = StringBuilder()
    appendNode(this, getRoot(), out, includeTrivia)
    return out.toString()
}

private fun appendNode(tree: LightSyntaxTree, node: LightNode, out: StringBuilder, includeTrivia: Boolean) {
    val type = tree.getType(node)
    if (tree.isToken(node)) {
        out.append(type.toString())
        return
    }
    out.append('(').append(type.toString())
    for (child in tree.getChildren(node)) {
        if (!includeTrivia && isTriviaName(tree.getType(child).toString())) continue
        out.append(' ')
        appendNode(tree, child, out, includeTrivia)
    }
    out.append(')')
}

private fun isTriviaName(typeName: String): Boolean =
    typeName == "WHITE_SPACE" || typeName.endsWith("COMMENT") || typeName == "KDoc"
