package dev.ide.lang.xml.completion

import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.TextRange
import dev.ide.lang.xml.XmlNode
import dev.ide.lang.xml.XmlNodeKinds

/**
 * Derives the [XmlCompletionPosition] at a caret from the raw text + the tolerant DOM. The fine-grained
 * "what token is under the caret" decision is made by a small backward/forward text scan (robust against
 * the half-typed tag the DOM can only approximate mid-edit); structural context — the enclosing element —
 * comes from the DOM. This mirrors the completion-marker technique's goal (precise context on broken
 * source) without needing a second parse: for XML a local scan is enough.
 */
object XmlContextScanner {

    fun scan(text: CharSequence, offset: Int, parsed: ParsedFile, filePath: String): XmlCompletionPosition {
        val caret = offset.coerceIn(0, text.length)

        // The governing '<' is the last one before the caret; there is no other '<' between it and the caret.
        val lt = lastIndexOfBefore(text, '<', caret)
        if (lt < 0) {
            return content(parsed, caret, filePath) // no open tag — element text content
        }
        val afterLt = if (lt + 1 < text.length) text[lt + 1] else ' '
        if (afterLt == '/' || afterLt == '!' || afterLt == '?') {
            return unknown(filePath, caret) // close tag / comment / prolog / doctype — not completed
        }

        // Walk the start tag from just after '<' to the caret, tracking quote state and the current attribute.
        var i = lt + 1
        val nameEnd = readNameEnd(text, i)
        i = nameEnd
        var inQuote: Char? = null
        var valueStart = -1
        var curAttr: String? = null
        var sawEquals = false
        val existing = LinkedHashSet<String>()

        while (i < caret) {
            val c = text[i]
            if (inQuote != null) {
                if (c == inQuote) { inQuote = null; curAttr = null; sawEquals = false }
                i++
                continue
            }
            when {
                c == '>' -> return content(parsed, caret, filePath) // tag already closed before the caret
                c == '"' || c == '\'' -> { inQuote = c; valueStart = i + 1; i++ }
                c == '=' -> { sawEquals = true; i++ }
                c.isWhitespace() || c == '/' -> i++
                isNameStart(c) -> {
                    val s = i
                    val e = readNameEnd(text, i)
                    curAttr = text.subSequence(s, e).toString()
                    existing.add(curAttr!!)
                    sawEquals = false
                    i = e
                }
                else -> i++
            }
        }

        val tagName = text.subSequence(lt + 1, nameEnd).toString()
        val parentTag = enclosingTagName(parsed, (lt - 1).coerceAtLeast(0))

        // 1) Inside an attribute value.
        if (inQuote != null) {
            return XmlCompletionPosition(
                kind = XmlCompletionKind.ATTRIBUTE_VALUE,
                tag = tagName.ifEmpty { null },
                parentTag = parentTag,
                attributeName = curAttr,
                existingAttributes = existing,
                prefix = text.subSequence(valueStart.coerceAtMost(caret), caret).toString(),
                replacementRange = TextRange(valueStart.coerceAtMost(caret), caret),
                filePath = filePath,
            )
        }
        // 2) Right after `name=` with no quote yet — still an attribute value position.
        if (sawEquals && curAttr != null) {
            return XmlCompletionPosition(
                kind = XmlCompletionKind.ATTRIBUTE_VALUE,
                tag = tagName.ifEmpty { null },
                parentTag = parentTag,
                attributeName = curAttr,
                existingAttributes = existing,
                prefix = "",
                replacementRange = TextRange(caret, caret),
                filePath = filePath,
            )
        }
        // 3) Caret within the tag-name token → completing the element name.
        if (caret <= nameEnd) {
            return XmlCompletionPosition(
                kind = XmlCompletionKind.TAG_NAME,
                tag = tagName.ifEmpty { null },
                parentTag = parentTag,
                attributeName = null,
                existingAttributes = emptySet(),
                prefix = text.subSequence(lt + 1, caret).toString(),
                replacementRange = TextRange(lt + 1, caret),
                filePath = filePath,
            )
        }
        // 4) Otherwise we're typing an attribute name. The partial token runs back to the last boundary.
        var tokenStart = caret
        while (tokenStart > lt + 1 && isNameChar(text[tokenStart - 1])) tokenStart--
        existing.remove(text.subSequence(tokenStart, caret).toString()) // don't count the token being typed
        val root = rootTag(parsed)
        return XmlCompletionPosition(
            kind = XmlCompletionKind.ATTRIBUTE_NAME,
            tag = tagName.ifEmpty { null },
            parentTag = parentTag,
            attributeName = null,
            existingAttributes = existing,
            prefix = text.subSequence(tokenStart, caret).toString(),
            replacementRange = TextRange(tokenStart, caret),
            filePath = filePath,
            declaredNamespaces = declaredNamespaces(root),
            namespaceInsertOffset = root?.let { it.startOffset + 1 + (it.name?.length ?: 0) } ?: -1,
        )
    }

    /** The prefixes declared by `xmlns:*` attributes on the root element (the Android convention for layouts). */
    private fun declaredNamespaces(root: XmlNode?): Set<String> =
        root?.attributes?.mapNotNull { it.name }
            ?.filter { it.startsWith("xmlns:") }
            ?.mapTo(LinkedHashSet()) { it.removePrefix("xmlns:") } ?: emptySet()

    /** The document's root element (first TAG node), or null. Namespace declarations are spliced here. */
    private fun rootTag(parsed: ParsedFile): XmlNode? {
        var found: XmlNode? = null
        fun walk(n: DomNode) {
            if (found != null) return
            if (n is XmlNode && n.kind == XmlNodeKinds.TAG) { found = n; return }
            n.children.forEach(::walk)
        }
        walk(parsed)
        return found
    }

    private fun content(parsed: ParsedFile, caret: Int, filePath: String) = XmlCompletionPosition(
        kind = XmlCompletionKind.TEXT,
        tag = null,
        parentTag = enclosingTagName(parsed, caret),
        attributeName = null,
        existingAttributes = emptySet(),
        prefix = "",
        replacementRange = TextRange(caret, caret),
        filePath = filePath,
    )

    private fun unknown(filePath: String, caret: Int) = XmlCompletionPosition(
        XmlCompletionKind.UNKNOWN, null, null, null, emptySet(), "", TextRange(caret, caret), filePath,
    )

    /** Name of the nearest enclosing element at [offset], or null at the document level. */
    private fun enclosingTagName(parsed: ParsedFile, offset: Int): String? {
        var node: DomNode? = parsed.nodeAt(offset)
        while (node != null && node.kind != XmlNodeKinds.TAG) node = node.parent
        return (node as? XmlNode)?.name?.ifEmpty { null }
    }

    private fun lastIndexOfBefore(text: CharSequence, ch: Char, before: Int): Int {
        var i = before - 1
        while (i >= 0) { if (text[i] == ch) return i; i-- }
        return -1
    }

    private fun readNameEnd(text: CharSequence, from: Int): Int {
        var i = from
        if (i < text.length && isNameStart(text[i])) {
            i++
            while (i < text.length && isNameChar(text[i])) i++
        }
        return i
    }

    private fun isNameStart(c: Char): Boolean = c.isLetter() || c == '_' || c == ':'
    private fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == ':' || c == '.' || c == '-'
}
