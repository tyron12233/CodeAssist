package dev.ide.kotlin.syntax.psi

import com.intellij.platform.syntax.SyntaxElementType
import org.jetbrains.kotlin.kmp.lexer.KtTokens
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
class KtTreeSession internal constructor(val tree: LightSyntaxTree) {

    private val wrappers = HashMap<Int, KtElement>()

    /** The file element. */
    val file: KtFile by lazy {
        val root = tree.getRoot()
        KtFile(this, root).also { wrappers[root.index] = it }
    }

    internal fun psi(node: LightNode): KtElement = wrappers.getOrPut(node.index) { createPsi(this, node) }

    /** Children with whitespace and comments left out, which is what every accessor here wants. */
    internal fun childrenOf(node: LightNode): List<LightNode> =
        tree.getChildren(node).filter { !isTrivia(tree.getType(it)) }

    internal fun isTrivia(type: SyntaxElementType): Boolean =
        type in KtTokens.WHITESPACES || type in KtTokens.COMMENTS
}
