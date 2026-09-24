package dev.ide.kotlin.syntax.psi

import com.intellij.platform.syntax.SyntaxElementType
import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes
import org.jetbrains.kotlin.kmp.tree.LightNode
import org.jetbrains.kotlin.kmp.tree.LightSyntaxTree

/** A half-open text range, mirroring `com.intellij.openapi.util.TextRange`. */
class TextRange(val startOffset: Int, val endOffset: Int) {
    val length: Int get() = endOffset - startOffset

    operator fun contains(offset: Int): Boolean = offset in startOffset until endOffset

    override fun equals(other: Any?): Boolean =
        other is TextRange && other.startOffset == startOffset && other.endOffset == endOffset

    override fun hashCode(): Int = startOffset * 31 + endOffset

    override fun toString(): String = "($startOffset,$endOffset)"
}

/**
 * One parsed file, and the facade wrappers over it.
 *
 * The session exists because of how the vendored tree is shaped. `LightNode` is a value class around an
 * `Int` index into flat arrays, not an object, so there is nowhere to hang a wrapper and no identity to rely
 * on: two `LightNode`s for the same node are equal but carry no state, and a node does not know its tree.
 * The session supplies both — the tree, and a wrapper cached per index.
 *
 * That caching is not a performance flourish. The analysis engine keys identity maps and caches on PSI
 * elements, and a facade that minted a fresh wrapper per access would turn every one of those into a leak
 * that still passed its own tests.
 */
class KtTreeSession internal constructor(
    val tree: LightSyntaxTree,
    val fileName: String = "dummy.kt",
    /**
     * Where this tree starts in the file, added to every offset the elements report.
     *
     * Zero for a file's own parse. It is not zero for a SUB-parse: a doc comment's contents are parsed on
     * their own (see `KotlinSyntax.parseKDoc`), and such a tree necessarily numbers from its own start,
     * because the tokens carry the offsets they were lexed at. Carrying the shift here rather than at the
     * call sites is the difference between a KDoc element that can be navigated to and one whose range points
     * at the top of the file.
     */
    val baseOffset: Int = 0,
    /**
     * Set for a LAZILY parsed file: the parser left function bodies and lambdas collapsed, and this parses
     * one on its own the first time anything looks inside it (see [expansionOf]). Null for a full parse and
     * for a sub-parse, which have nothing collapsed.
     */
    internal val bodies: LazyBodyParser? = null,
    /** The session this one is a sub-parse of, so its elements report the real [file]. */
    private val host: KtTreeSession? = null,
) {

    private val wrappers = HashMap<Int, KtElement>()

    /** The file element. A sub-parse answers with the file it was parsed out of. */
    val file: KtFile by lazy {
        host?.file ?: run {
            val root = tree.getRoot()
            KtFile(this, root).also { wrappers[root.index] = it }
        }
    }

    /** A collapsed body's own parse: the session over it, and the node that stands in for the body. */
    internal class Expansion(val session: KtTreeSession, val node: LightNode)

    // Node index -> its expansion, or null once a node was found not to be collapsed. Filled on first look.
    private val expansions = HashMap<Int, Expansion?>()

    /**
     * The parse of [node]'s interior when [node] is a body this lazy parse collapsed, else null. [owner] is
     * [node]'s element: it becomes the root of the sub-parse, so the body's statements report it as their
     * parent and the file's element identity is untouched. Parsed once per session; [bodies] may reuse an
     * identical body's parse from an earlier session.
     */
    internal fun expansionOf(node: LightNode, owner: KtElement): Expansion? {
        val parser = bodies ?: return null
        val type = tree.getType(node)
        if (type != KtNodeTypes.BLOCK && type != KtNodeTypes.LAMBDA_EXPRESSION) return null
        if (node.index in expansions) return expansions[node.index]
        val expansion = if (isCollapsedBody(tree, node, type)) {
            val kind = if (type == KtNodeTypes.BLOCK) LazyBodyParser.Kind.BLOCK else LazyBodyParser.Kind.LAMBDA
            val subTree = parser.parse(tree.getText(node).toString(), kind)
            val subNode = findOfType(subTree, subTree.getRoot(), type)
            if (subNode == null) null else {
                val sub = KtTreeSession(subTree, fileName, baseOffset + tree.getStartOffset(node), host = this)
                sub.claimRoot(subNode, owner)
                Expansion(sub, subNode)
            }
        } else null
        expansions[node.index] = expansion
        return expansion
    }

    private fun findOfType(t: LightSyntaxTree, node: LightNode, type: SyntaxElementType): LightNode? {
        if (t.getType(node) == type) return node
        for (c in t.getChildren(node)) if (!t.isToken(c)) findOfType(t, c, type)?.let { return it }
        return null
    }

    internal fun psi(node: LightNode): KtElement = wrappers.getOrPut(node.index) { createPsi(this, node) }

    /**
     * Claim [node]'s wrapper slot for [element].
     *
     * A sub-parse's root is built by the caller that knows what kind it is ([createPsi] maps by element type,
     * and a DOC_COMMENT is a token in the file's tree and the ROOT of the doc comment's own), so it has to be
     * put into the cache rather than found there. Without this the root gets a second wrapper the moment a
     * child asks for its parent, and the identity maps the analysis engine keys on would see two of it.
     */
    internal fun <T : KtElement> claimRoot(node: LightNode, element: T): T {
        wrappers[node.index] = element
        return element
    }

    private val kdocs = HashMap<Int, KDoc>()

    /** The parsed contents of the doc-comment token at [index], parsed once per comment. */
    internal fun kdoc(index: Int, create: () -> KDoc): KDoc = kdocs.getOrPut(index, create)

    /** Children with whitespace and comments left out, which is what every accessor here wants. */
    internal fun childrenOf(node: LightNode): List<LightNode> =
        tree.getChildren(node).filter { !isTrivia(tree.getType(it)) }

    internal fun isTrivia(type: SyntaxElementType): Boolean =
        type in KtTokens.WHITESPACES || type in KtTokens.COMMENTS
}

/**
 * Parses the bodies a lazy file parse collapsed, on demand. An interface so the host decides what to keep:
 * the editor keeps an identical body's parse across keystrokes (a keystroke changes one body, and every other
 * one would otherwise be parsed again), and it holds that cache behind its own lock, since sessions over
 * different files are read on different threads.
 */
interface LazyBodyParser {
    enum class Kind { BLOCK, LAMBDA }

    /** The tree for [text], a body of [kind] with its braces, with offsets relative to [text]. */
    fun parse(text: String, kind: Kind): LightSyntaxTree

    companion object {
        /** Parses every body afresh. */
        val UNCACHED: LazyBodyParser = object : LazyBodyParser {
            override fun parse(text: String, kind: Kind): LightSyntaxTree = when (kind) {
                Kind.BLOCK -> dev.ide.kotlin.syntax.KotlinSyntax.parseBlock(text)
                Kind.LAMBDA -> dev.ide.kotlin.syntax.KotlinSyntax.parseLambda(text)
            }
        }
    }
}

/**
 * Whether a lazy parse left [node] collapsed: a block opened by `{` holding only tokens (and the one-token
 * composites the parser builds for `?:`, `!!` and the like), or a lambda whose literal carries no body.
 * Judged by shape because the tree records no flag; a block wrongly taken as collapsed would only be parsed
 * again into the same tree, which `LazyBlockParityTest` pins.
 */
internal fun isCollapsedBody(tree: LightSyntaxTree, node: LightNode, type: SyntaxElementType = tree.getType(node)): Boolean {
    if (type == KtNodeTypes.LAMBDA_EXPRESSION) {
        val literal = tree.findChildByType(node, KtNodeTypes.FUNCTION_LITERAL) ?: return false
        return tree.findChildByType(literal, KtNodeTypes.BLOCK) == null
    }
    if (type != KtNodeTypes.BLOCK) return false
    val children = tree.getChildren(node)
    val first = children.firstOrNull() ?: return false
    if (!tree.isToken(first) || tree.getType(first) != KtTokens.LBRACE) return false
    for (c in children) {
        if (tree.isToken(c)) continue
        for (g in tree.getChildren(c)) if (!tree.isToken(g)) return false
    }
    return true
}

/**
 * Whether every body [tree] (a lazy parse) collapsed is closed by its own `}`. When one is not, the braces or a
 * comment run unbalanced to the end of the file, and there the lazy grammar binds the trailing text around
 * that body differently from the full one; such a file must be parsed in full.
 */
internal fun collapsedBodiesAreClosed(tree: LightSyntaxTree, node: LightNode = tree.getRoot()): Boolean {
    if (tree.isToken(node)) return true
    val type = tree.getType(node)
    if (isCollapsedBody(tree, node, type)) {
        val holder = if (type == KtNodeTypes.LAMBDA_EXPRESSION) tree.findChildByType(node, KtNodeTypes.FUNCTION_LITERAL)!! else node
        val last = tree.getChildren(holder).lastOrNull() ?: return false
        return tree.isToken(last) && tree.getType(last) == KtTokens.RBRACE
    }
    for (c in tree.getChildren(node)) if (!collapsedBodiesAreClosed(tree, c)) return false
    return true
}
