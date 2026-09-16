package dev.ide.kotlin.syntax.tree

/** A half-open text range, mirroring `com.intellij.openapi.util.TextRange`. */
class TextRange(val startOffset: Int, val endOffset: Int) {
    val length: Int get() = endOffset - startOffset

    operator fun contains(offset: Int): Boolean = offset in startOffset until endOffset

    fun containsRange(start: Int, end: Int): Boolean = start >= startOffset && end <= endOffset

    override fun equals(other: Any?): Boolean =
        other is TextRange && other.startOffset == startOffset && other.endOffset == endOffset

    override fun hashCode(): Int = startOffset * 31 + endOffset

    override fun toString(): String = "($startOffset,$endOffset)"
}

/**
 * A node of the parsed tree, mirroring `com.intellij.lang.ASTNode`.
 *
 * The tree is the whole file with nothing dropped: whitespace, comments and error markers are all real
 * nodes, so concatenating the leaves reproduces the source byte for byte. `AstNodeTest` asserts exactly that,
 * because it is the property an editor depends on — every offset the analysis engine hands back has to point
 * at the text the user is looking at.
 *
 * Children are parented eagerly at build time. That costs one pass and buys constant-time [treeParent], which
 * the facade's `getParentOfType` walks on every resolve.
 */
sealed class AstNode(val source: CharSequence) {

    abstract val elementType: IElementType

    /** Start offset in the original text, inclusive. */
    abstract val startOffset: Int

    /** End offset in the original text, exclusive. */
    abstract val endOffset: Int

    var treeParent: CompositeNode? = null
        internal set

    /**
     * The facade wrapper for this node, created once.
     *
     * Identity has to be stable: the analysis engine keys identity maps and caches on PSI elements, and a
     * facade that minted a new wrapper per call would silently turn those into leaks. Typed as [Any] because
     * the facade lives a package up and this one is the leaf.
     */
    internal var cachedPsi: Any? = null

    val textLength: Int get() = endOffset - startOffset

    val textRange: TextRange get() = TextRange(startOffset, endOffset)

    val text: String get() = source.substring(startOffset, endOffset)

    /** The previous sibling, or null at the start of the parent's children. */
    val treePrev: AstNode?
        get() {
            val parent = treeParent ?: return null
            val index = parent.children.indexOf(this)
            return if (index > 0) parent.children[index - 1] else null
        }

    /** The next sibling, or null at the end of the parent's children. */
    val treeNext: AstNode?
        get() {
            val parent = treeParent ?: return null
            val index = parent.children.indexOf(this)
            return if (index >= 0 && index + 1 < parent.children.size) parent.children[index + 1] else null
        }

    open val firstChildNode: AstNode? get() = null
    open val lastChildNode: AstNode? get() = null

    /** Direct children, in source order. Empty for a leaf. */
    open val children: List<AstNode> get() = emptyList()

    fun findChildByType(type: IElementType): AstNode? = children.firstOrNull { it.elementType === type }

    fun findChildByType(types: TokenSet): AstNode? = children.firstOrNull { it.elementType in types }

    fun findChildrenByType(type: IElementType): List<AstNode> = children.filter { it.elementType === type }

    /** Children matching [filter], or all of them when it is null. Mirrors `ASTNode.getChildren`. */
    fun getChildren(filter: TokenSet?): List<AstNode> =
        if (filter == null) children else children.filter { it.elementType in filter }

    /** This node and every descendant, depth first, in source order. */
    fun descendants(): Sequence<AstNode> = sequence {
        yield(this@AstNode)
        for (child in children) yieldAll(child.descendants())
    }

    /** The innermost node whose range contains [offset], preferring leaves. */
    fun findElementAt(offset: Int): AstNode? {
        if (offset !in textRange) return null
        for (child in children) {
            val found = child.findElementAt(offset)
            if (found != null) return found
        }
        return this
    }

    /**
     * A one-line rendering of the subtree, used by the tests and by the differential oracle: an element as
     * `(TYPE child child)` and a leaf as its type name. Trivia is left out because an oracle that compared it
     * would be testing the whitespace binder rather than the grammar.
     */
    fun treeString(includeTrivia: Boolean = false): String {
        val builder = StringBuilder()
        appendTree(builder, includeTrivia)
        return builder.toString()
    }

    private fun appendTree(builder: StringBuilder, includeTrivia: Boolean) {
        when (this) {
            is LeafNode -> builder.append(elementType.debugName)
            is CompositeNode -> {
                val kept = if (includeTrivia) children else children.filter { !it.isTrivia() }
                builder.append('(').append(elementType.debugName)
                for (child in kept) {
                    builder.append(' ')
                    child.appendTree(builder, includeTrivia)
                }
                builder.append(')')
            }
        }
    }

    /** Whitespace and comments: present in the tree, excluded from structural comparisons. */
    fun isTrivia(): Boolean =
        elementType === TokenType.WHITE_SPACE || elementType.debugName.endsWith("COMMENT")
}

/** A token: one contiguous run of source with no structure inside it. */
class LeafNode(
    override val elementType: IElementType,
    override val startOffset: Int,
    override val endOffset: Int,
    source: CharSequence,
) : AstNode(source) {
    override fun toString(): String = "${elementType.debugName}$textRange"
}

/** An element with children. Its range spans them; an empty one is a zero-width marker at its position. */
class CompositeNode(
    override val elementType: IElementType,
    private val fallbackOffset: Int,
    source: CharSequence,
) : AstNode(source) {

    private val childList = ArrayList<AstNode>()

    override val children: List<AstNode> get() = childList

    override val startOffset: Int get() = childList.firstOrNull()?.startOffset ?: fallbackOffset
    override val endOffset: Int get() = childList.lastOrNull()?.endOffset ?: fallbackOffset

    override val firstChildNode: AstNode? get() = childList.firstOrNull()
    override val lastChildNode: AstNode? get() = childList.lastOrNull()

    internal fun addChild(child: AstNode) {
        child.treeParent = this
        childList.add(child)
    }

    /** Swap the last child for [replacement]. Used when a marker is collapsed into a single leaf. */
    internal fun replaceLastChild(replacement: AstNode) {
        if (childList.isNotEmpty()) childList.removeAt(childList.size - 1)
        addChild(replacement)
    }

    override fun toString(): String = "${elementType.debugName}$textRange"
}
