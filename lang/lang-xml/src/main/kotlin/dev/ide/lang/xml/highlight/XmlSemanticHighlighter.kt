package dev.ide.lang.xml.highlight

import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.TextRange
import dev.ide.lang.highlight.HighlightKind
import dev.ide.lang.highlight.SemanticHighlightService
import dev.ide.lang.highlight.SemanticToken
import dev.ide.lang.xml.XmlNode
import dev.ide.lang.xml.XmlNodeKinds
import dev.ide.platform.EngineCancellation
import dev.ide.vfs.VirtualFile

/**
 * Structural semantic coloring for XML, layered over the editor's lexical line-scanner. The line-scanner is
 * fast but blind to structure: it colors a whole `android:text` as one attribute and a whole `"@string/x"`
 * as one string. This pass walks the tolerant DOM (which knows exact tag / attribute / value boundaries) and
 * emits the two tokens the line-scanner cannot place reliably:
 *  - the **namespace prefix** of an attribute (`android` in `android:text`) as its own color, and
 *  - a **resource / theme reference** in an attribute value (`@string/x`, `@+id/y`, `?attr/z`) so it stands
 *    out from a plain string literal.
 * Everything else (tag names, the attribute's local name) is already well colored by the lexical layer, so it
 * is deliberately left alone (semantic only wins on overlap). Pure over the DOM, so it stays Android-agnostic
 * and needs no resolution; the kinds are the open string-backed [HighlightKind]s the UI maps to colors.
 */
class XmlSemanticHighlighter(private val parseOf: suspend (VirtualFile) -> ParsedFile?) :
    SemanticHighlightService {

    override suspend fun highlight(file: VirtualFile): List<SemanticToken> {
        val parsed = parseOf(file) ?: return emptyList()
        val out = ArrayList<SemanticToken>(64)
        var seen = 0
        fun walk(node: DomNode) {
            if (seen++ % 64 == 0) EngineCancellation.checkCanceled()
            if (node is XmlNode) when (node.kind) {
                XmlNodeKinds.ATTRIBUTE -> namespacePrefixToken(node)?.let { out += it }
                XmlNodeKinds.ATTR_VALUE -> referenceToken(node)?.let { out += it }
                else -> {}
            }
            node.children.forEach(::walk)
        }
        walk(parsed)
        return out
    }

    /** The `prefix` of a namespaced attribute name (`android` in `android:text`), as a namespace-prefix token. */
    private fun namespacePrefixToken(attr: XmlNode): SemanticToken? {
        val name = attr.name ?: return null
        val colon = name.indexOf(':')
        if (colon <= 0) return null
        return SemanticToken(
            TextRange(attr.startOffset, attr.startOffset + colon),
            NAMESPACE_PREFIX
        )
    }

    /** A resource/theme reference filling an attribute value (`@…`, `?…`), as a REFERENCE token over its text. */
    private fun referenceToken(value: XmlNode): SemanticToken? {
        val text = value.text()
        val first = text.indexOfFirst { !it.isWhitespace() }
        if (first < 0) return null
        val sigil = text[first]
        if (sigil != '@' && sigil != '?') return null
        if (text.length <= first + 1) return null
        return SemanticToken(value.range, REFERENCE)
    }

    private companion object {
        /** An XML namespace prefix (`android`/`app`/`tools`) before the `:` in an attribute name. */
        val NAMESPACE_PREFIX = HighlightKind("xmlNamespace")

        /** A resource / theme reference (`@type/name`, `@+id/name`, `?attr/name`) in an attribute value. */
        val REFERENCE = HighlightKind("xmlReference")
    }
}
