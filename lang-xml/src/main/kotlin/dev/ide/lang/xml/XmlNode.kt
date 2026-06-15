package dev.ide.lang.xml

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.TextRange
import dev.ide.vfs.VirtualFile

/**
 * The concrete, error-tolerant DOM node the XML parser builds. Mutable only during construction (the
 * parser appends children and closes the end offset as it advances); above the parser it is read as the
 * immutable neutral [DomNode]. [name] carries the tag name for [XmlNodeKinds.TAG] and the attribute name
 * for [XmlNodeKinds.ATTRIBUTE]; it is null for every other kind.
 */
class XmlNode(
    override val kind: NodeKind,
    start: Int,
    end: Int,
    private val source: CharSequence,
    val name: String? = null,
) : DomNode {

    var startOffset: Int = start
        internal set
    var endOffset: Int = end
        internal set

    /** True for `<View/>` self-closing elements (no body, no separate close tag). */
    var selfClosed: Boolean = false
        internal set

    override val range: TextRange get() = TextRange(startOffset, endOffset)

    override var parent: DomNode? = null
        internal set

    private val kids = ArrayList<XmlNode>()
    override val children: List<DomNode> get() = kids

    internal fun add(child: XmlNode) {
        child.parent = this
        kids.add(child)
    }

    internal fun close(end: Int) { endOffset = end }

    override fun text(): CharSequence {
        val s = startOffset.coerceIn(0, source.length)
        val e = endOffset.coerceIn(s, source.length)
        return source.subSequence(s, e)
    }

    /** Element attributes (the [XmlNodeKinds.ATTRIBUTE] children), in source order. Empty for non-tags. */
    val attributes: List<XmlNode> get() = kids.filter { it.kind == XmlNodeKinds.ATTRIBUTE }

    /** Child elements (the [XmlNodeKinds.TAG] children), in source order. Empty for non-tags. */
    val childTags: List<XmlNode> get() = kids.filter { it.kind == XmlNodeKinds.TAG }

    /** For an [XmlNodeKinds.ATTRIBUTE]: the value node (text between the quotes), or null if absent. */
    val valueNode: XmlNode? get() = kids.firstOrNull { it.kind == XmlNodeKinds.ATTR_VALUE }
}

/**
 * The parsed XML file: the document root plus the file/version/diagnostics bookkeeping the SPI requires.
 * Delegates the [DomNode] surface to its [root] element-list container and adds the position queries.
 */
class XmlParsedFile(
    private val root: XmlNode,
    override val file: VirtualFile,
    override val documentVersion: Long,
    override val diagnostics: List<Diagnostic>,
) : ParsedFile, DomNode by root {

    override fun nodeAt(offset: Int): DomNode {
        var best: DomNode = root
        fun descend(node: DomNode) {
            for (child in node.children) {
                if (offset in child.range) {
                    // Prefer the deepest node; on ties (zero-width / adjacent), the later/inner one wins.
                    best = child
                    descend(child)
                }
            }
        }
        descend(root)
        return best
    }

    override fun nodesIn(range: TextRange): Sequence<DomNode> = sequence {
        suspend fun SequenceScope<DomNode>.walk(node: DomNode) {
            if (node.range.intersects(range)) {
                yield(node)
                for (child in node.children) walk(child)
            }
        }
        walk(root)
    }
}
