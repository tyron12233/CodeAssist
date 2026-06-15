package dev.ide.lang.xml

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentSnapshot

/**
 * A hand-written, **error-tolerant** XML parser. It never throws on malformed input — the editor buffer
 * is almost always malformed mid-keystroke — and always returns a [XmlParsedFile] spanning the whole
 * document. Unrecoverable spans become [NodeKind.ERROR] nodes; an unterminated tag is closed implicitly
 * with a diagnostic. This recovery is what lets completion fire on half-typed source.
 *
 * Recovery strategy for the one genuinely ambiguous case — mismatched close tags — uses an explicit stack
 * of open element names: `</x>` is consumed by the matching open element if one exists on the stack
 * (intervening unclosed elements are closed implicitly), and is reported as a stray close otherwise.
 */
class XmlTreeParser(snapshot: DocumentSnapshot) {

    private val text: CharSequence = snapshot.text
    private val len = text.length
    private var pos = 0
    private val diagnostics = ArrayList<Diagnostic>()
    private val openStack = ArrayDeque<String>()

    fun parse(): Pair<XmlNode, List<Diagnostic>> {
        val root = XmlNode(XmlNodeKinds.DOCUMENT, 0, len, text)
        parseContent(root)
        // Anything left (e.g. a stray close at top level the loop didn't consume) — make progress.
        if (pos < len) {
            val node = XmlNode(NodeKind.ERROR, pos, len, text)
            root.add(node)
            pos = len
        }
        root.close(len)
        return root to diagnostics
    }

    /** Parse element content into [into] until EOF or a close tag belonging to an open ancestor. */
    private fun parseContent(into: XmlNode) {
        while (pos < len) {
            val c = text[pos]
            if (c == '<') {
                when {
                    startsWith("</") -> {
                        val closeName = peekCloseName()
                        if (closeName != null && openStack.contains(closeName)) return // an ancestor owns it
                        // Stray close tag (matches nothing open) — consume as an error, keep going.
                        val start = pos
                        val end = consumeCloseTag()
                        val node = XmlNode(NodeKind.ERROR, start, end, text)
                        into.add(node)
                        report(start, end, "Unexpected closing tag", "xml.strayClose")
                    }
                    startsWith("<!--") -> into.add(consumeUntil(XmlNodeKinds.COMMENT, "-->"))
                    startsWith("<![CDATA[") -> into.add(consumeUntil(XmlNodeKinds.CDATA, "]]>"))
                    startsWith("<?") -> into.add(consumeUntil(XmlNodeKinds.PROLOG, "?>"))
                    startsWith("<!") -> into.add(consumeDoctype())
                    pos + 1 < len && isNameStart(text[pos + 1]) -> into.add(parseElement())
                    else -> {
                        // A bare '<' that doesn't start a tag — consume it so we make progress.
                        val node = XmlNode(NodeKind.ERROR, pos, pos + 1, text)
                        pos++
                        into.add(node)
                    }
                }
            } else {
                into.add(parseText())
            }
        }
    }

    private fun parseText(): XmlNode {
        val start = pos
        while (pos < len && text[pos] != '<') pos++
        return XmlNode(XmlNodeKinds.TEXT, start, pos, text)
    }

    private fun parseElement(): XmlNode {
        val start = pos
        pos++ // consume '<'
        val nameStart = pos
        val name = readName()
        if (name.isEmpty()) report(start, pos, "Expected element name", "xml.expectedName")
        val element = XmlNode(XmlNodeKinds.TAG, start, pos, text, name = name)
        // record the name token as a child range is unnecessary; name is on the node.
        nameStart // (kept for clarity)

        when (parseAttributes(element)) {
            TagEnd.SELF_CLOSED -> {
                element.selfClosed = true
                element.close(pos)
                return element
            }
            TagEnd.UNCLOSED -> {
                report(start, pos, "Malformed start tag for <$name>", "xml.malformedTag")
                element.close(pos)
                return element
            }
            TagEnd.OPEN -> { /* body + close tag follow */ }
        }

        openStack.addLast(name)
        parseContent(element)
        openStack.removeLast()

        if (pos < len && startsWith("</")) {
            val closeName = peekCloseName()
            if (closeName == name || closeName == null || closeName.isEmpty()) {
                val end = consumeCloseTag()
                element.close(end)
            } else {
                // Belongs to an outer ancestor — close this element implicitly, leave the tag for the parent.
                report(start, pos, "Missing closing tag </$name>", "xml.unclosedTag")
                element.close(pos)
            }
        } else {
            report(start, len, "Missing closing tag </$name>", "xml.unclosedTag")
            element.close(pos)
        }
        return element
    }

    private enum class TagEnd { OPEN, SELF_CLOSED, UNCLOSED }

    private fun parseAttributes(element: XmlNode): TagEnd {
        while (pos < len) {
            skipWhitespace()
            if (pos >= len) return TagEnd.UNCLOSED
            val c = text[pos]
            when {
                c == '>' -> { pos++; return TagEnd.OPEN }
                startsWith("/>") -> { pos += 2; return TagEnd.SELF_CLOSED }
                c == '<' -> return TagEnd.UNCLOSED // next tag started before this one closed
                isNameStart(c) -> element.add(parseAttribute())
                else -> pos++ // junk character inside the tag — skip to make progress
            }
        }
        return TagEnd.UNCLOSED
    }

    private fun parseAttribute(): XmlNode {
        val start = pos
        val name = readName()
        val attr = XmlNode(XmlNodeKinds.ATTRIBUTE, start, pos, text, name = name)
        skipWhitespace()
        if (pos < len && text[pos] == '=') {
            pos++ // consume '='
            skipWhitespace()
            if (pos < len && (text[pos] == '"' || text[pos] == '\'')) {
                val quote = text[pos]
                pos++ // opening quote
                val valueStart = pos
                while (pos < len && text[pos] != quote && text[pos] != '<') pos++
                val valueEnd = pos
                attr.add(XmlNode(XmlNodeKinds.ATTR_VALUE, valueStart, valueEnd, text))
                if (pos < len && text[pos] == quote) pos++ else
                    report(valueStart, pos, "Unterminated attribute value", "xml.unterminatedValue")
            } else {
                // Missing quotes — read a bare token tolerantly (XML requires quotes, so this is an error).
                val valueStart = pos
                while (pos < len && !text[pos].isWhitespace() && text[pos] != '>' && text[pos] != '<') pos++
                attr.add(XmlNode(XmlNodeKinds.ATTR_VALUE, valueStart, pos, text))
                if (pos > valueStart) report(valueStart, pos, "Attribute value must be quoted", "xml.unquotedValue")
            }
        }
        attr.close(pos)
        return attr
    }

    private fun consumeDoctype(): XmlNode {
        val start = pos
        while (pos < len && text[pos] != '>') pos++
        if (pos < len) pos++ // consume '>'
        return XmlNode(XmlNodeKinds.DOCTYPE, start, pos, text)
    }

    private fun consumeUntil(kind: NodeKind, terminator: String): XmlNode {
        val start = pos
        val idx = indexOf(terminator, pos)
        pos = if (idx < 0) len else idx + terminator.length
        return XmlNode(kind, start, pos, text)
    }

    /** Peek the name of the `</name>` close tag at [pos] without consuming. Null if not a close tag. */
    private fun peekCloseName(): String? {
        if (!startsWith("</")) return null
        var i = pos + 2
        while (i < len && text[i].isWhitespace()) i++
        val s = i
        while (i < len && isNameChar(text[i])) i++
        return text.subSequence(s, i).toString()
    }

    /** Consume a `</name …>` close tag starting at [pos]; returns the new [pos] (past '>'), tolerant of EOF. */
    private fun consumeCloseTag(): Int {
        pos += 2 // '</'
        while (pos < len && text[pos] != '>' && text[pos] != '<') pos++
        if (pos < len && text[pos] == '>') pos++
        return pos
    }

    private fun readName(): String {
        val s = pos
        if (pos < len && isNameStart(text[pos])) {
            pos++
            while (pos < len && isNameChar(text[pos])) pos++
        }
        return text.subSequence(s, pos).toString()
    }

    private fun skipWhitespace() { while (pos < len && text[pos].isWhitespace()) pos++ }

    private fun startsWith(s: String): Boolean {
        if (pos + s.length > len) return false
        for (i in s.indices) if (text[pos + i] != s[i]) return false
        return true
    }

    private fun indexOf(needle: String, from: Int): Int {
        var i = from
        outer@ while (i + needle.length <= len) {
            for (j in needle.indices) if (text[i + j] != needle[j]) { i++; continue@outer }
            return i
        }
        return -1
    }

    private fun report(start: Int, end: Int, message: String, code: String) {
        diagnostics.add(Diagnostic(TextRange(start, end), Severity.ERROR, message, code))
    }

    private fun isNameStart(c: Char): Boolean = c.isLetter() || c == '_' || c == ':'
    private fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == ':' || c == '.' || c == '-'
}
