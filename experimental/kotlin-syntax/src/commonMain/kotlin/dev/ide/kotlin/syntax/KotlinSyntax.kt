package dev.ide.kotlin.syntax

import com.intellij.platform.syntax.SyntaxElementType
import com.intellij.platform.syntax.lexer.TokenList
import com.intellij.platform.syntax.lexer.performLexing
import com.intellij.platform.syntax.parser.SyntaxTreeBuilder
import com.intellij.platform.syntax.parser.SyntaxTreeBuilderFactory
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtTreeSession
import dev.ide.kotlin.syntax.psi.LazyBodyParser
import dev.ide.kotlin.syntax.psi.collapsedBodiesAreClosed
import dev.ide.kotlin.syntax.psi.isCollapsedBody
import org.jetbrains.kotlin.kmp.lexer.KDocLexer
import org.jetbrains.kotlin.kmp.lexer.KotlinLexer
import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.jetbrains.kotlin.kmp.parser.AbstractParser
import org.jetbrains.kotlin.kmp.parser.KDocLinkParser
import org.jetbrains.kotlin.kmp.parser.KDocParser
import org.jetbrains.kotlin.kmp.parser.KotlinParser
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes
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
     * Parse one lambda literal in isolation, the interior of a `LAMBDA_EXPRESSION` a lazy parse collapsed.
     * [lambdaText] is the literal's own text, braces included; offsets are relative to it, as with [parseBlock].
     */
    fun parseLambda(lambdaText: CharSequence): LightSyntaxTree = build(lambdaText, 0, LambdaParser, null)

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

    /**
     * Parse [text] lazily and return it behind the facade, with every collapsed body parsed by [bodies] the
     * first time anything looks inside it. Reads exactly like [parseFile] (the same tree, element for
     * element, see `LazyBlockParityTest`), but a caller that never enters a body never pays for parsing it.
     */
    fun parseFileLazily(
        text: CharSequence,
        bodies: LazyBodyParser,
        isScript: Boolean = false,
        name: String = "dummy.kt",
    ): KtFile {
        val lazyTree = parse(text, isScript, lazy = true)
        // A body left open to the end of the file (a brace or a comment not yet closed, mid-edit) is where the
        // lazy grammar and the full one part ways; parse such a file in full.
        if (!collapsedBodiesAreClosed(lazyTree)) return KtTreeSession(parse(text, isScript), name).file
        return KtTreeSession(lazyTree, name, bodies = bodies).file
    }

    /**
     * [previous], a file [parseFileLazily] built, updated to [newText] without parsing the file again; or null
     * when that cannot be shown to give the same tree, and the caller should parse [newText] itself.
     *
     * It applies to the edit a keystroke almost always is: one that stays strictly inside a body the lazy
     * parse collapsed. The grammar skipped such a body by counting braces, so as long as the new body still
     * closes exactly at its end nothing outside it parses any differently. Only that body is parsed again
     * (lazily, on its own), and the rest of the tree is carried over with its offsets moved. The check reads
     * tokens rather than characters, because a brace inside a string or a comment is not a brace, and an
     * unclosed string or comment runs to the end of the body and fails it.
     */
    fun reparseFileLazily(previous: KtFile, newText: CharSequence): KtFile? {
        val session = previous.session
        val bodies = session.bodies ?: return null
        val tree = session.tree
        val old = tree.source
        if (old.length == newText.length && old.contentEquals(newText)) return previous
        val limit = minOf(old.length, newText.length)
        var prefix = 0
        while (prefix < limit && old[prefix] == newText[prefix]) prefix++
        var suffix = 0
        while (suffix < limit - prefix && old[old.length - 1 - suffix] == newText[newText.length - 1 - suffix]) suffix++
        val body = collapsedBodyAround(tree, tree.getRoot(), prefix, old.length - suffix) ?: return null
        val type = tree.getType(body)
        val start = tree.getStartOffset(body)
        val end = tree.getEndOffset(body) + (newText.length - old.length)
        // The body parsed lazily on its own, in the smallest context that parses it the way the file does, so
        // it comes back in the same collapsed shape (the lazy grammar still groups `?.` and `?:` inside one).
        val context = if (type == KtNodeTypes.BLOCK) "fun f()" else "val v = "
        val sub = parse(StringBuilder(context.length + end - start).append(context).append(newText, start, end), lazy = true)
        val subBody = nodeSpanning(sub, sub.getRoot(), type, context.length, context.length + end - start) ?: return null
        if (!isCollapsedBody(sub, subBody, type) || !closesAtItsEnd(sub, subBody)) return null
        val updated = tree.withReplacedSubtree(body, newText, sub, subBody) ?: return null
        return KtTreeSession(updated, session.fileName, bodies = bodies).file
    }

    /** The composite of [type] under [node] spanning exactly [start, end), or null. */
    private fun nodeSpanning(tree: LightSyntaxTree, node: LightNode, type: SyntaxElementType, start: Int, end: Int): LightNode? {
        if (tree.isToken(node)) return null
        val s = tree.getStartOffset(node)
        val e = tree.getEndOffset(node)
        if (s > start || e < end) return null
        if (s == start && e == end && tree.getType(node) == type) return node
        for (i in 0 until tree.childCount(node)) {
            nodeSpanning(tree, LightNode(tree.childIndexAt(node, i)), type, start, end)?.let { return it }
        }
        return null
    }

    /** Whether [body]'s braces first return to depth zero at its last token, which is a `}`. */
    private fun closesAtItsEnd(tree: LightSyntaxTree, body: LightNode): Boolean {
        val tokens = tree.tokens
        var first = -1
        var last = -1
        for (i in 0 until tokens.tokenCount) {
            if (tokens.getTokenStart(i) < tree.getStartOffset(body)) continue
            if (tokens.getTokenEnd(i) > tree.getEndOffset(body)) break
            if (first < 0) first = i
            last = i
        }
        if (first < 0 || tokens.getTokenType(first) != KtTokens.LBRACE) return false
        var depth = 0
        for (i in first..last) {
            when (tokens.getTokenType(i)) {
                KtTokens.LBRACE -> depth++
                KtTokens.RBRACE -> {
                    depth--
                    if (depth == 0) return i == last
                }
            }
        }
        return false
    }

    /** The outermost collapsed body under [node] whose interior holds the old-text change [from, to). */
    private fun collapsedBodyAround(tree: LightSyntaxTree, node: LightNode, from: Int, to: Int): LightNode? {
        if (tree.isToken(node)) return null
        val type = tree.getType(node)
        if ((type == KtNodeTypes.BLOCK || type == KtNodeTypes.LAMBDA_EXPRESSION) && isCollapsedBody(tree, node, type)) {
            // Strictly inside: the braces themselves are what the rest of the parse was decided by.
            return node.takeIf { from > tree.getStartOffset(it) && to < tree.getEndOffset(it) }
        }
        for (i in 0 until tree.childCount(node)) {
            val c = LightNode(tree.childIndexAt(node, i))
            if (tree.isToken(c)) continue
            if (tree.getStartOffset(c) <= from && to <= tree.getEndOffset(c)) return collapsedBodyAround(tree, c, from, to)
        }
        return null
    }

    /** Drives the vendored grammar's lambda rule, non-lazy, for the same reason as [BlockParser]. */
    private object LambdaParser : BodyParser() {
        override fun parseBody(parsing: KotlinParsing) = parsing.parseLambdaExpression()
    }

    /**
     * A body parsed on its own, shaped like a file parse so the tree matches the one the body would have in
     * place: the same comment-binding policy as [KotlinParser], and the body wrapped in a FILE marker. The
     * light tree treats the outermost marker as its synthetic root and drops comments directly under the
     * root, so without the wrapper the body itself became the root and lost the comments between its
     * statements.
     */
    private abstract class BodyParser : AbstractParser() {
        private val file = KotlinParser(isScript = false, isLazy = false)
        override val whitespaces: Set<SyntaxElementType> = file.whitespaces
        override val comments: Set<SyntaxElementType> = file.comments
        override val whitespaceOrCommentBindingPolicy = file.whitespaceOrCommentBindingPolicy

        abstract fun parseBody(parsing: KotlinParsing)

        override fun parse(builder: SyntaxTreeBuilder) {
            val root = builder.mark()
            parseBody(KotlinParsing.createForTopLevelNonLazy(SemanticWhitespaceAwareSyntaxBuilderImpl(builder)))
            while (!builder.eof()) builder.advanceLexer()
            root.done(KtNodeTypes.FILE)
        }
    }

    /** Drives the vendored grammar's block rule rather than its file rule. */
    private object BlockParser : BodyParser() {
        // Non-lazy on purpose: expanding a block and then collapsing the blocks inside it would defeat the
        // point of having asked.
        override fun parseBody(parsing: KotlinParsing) = parsing.parseBlockExpression()
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
