package dev.ide.deps.impl

/**
 * One element of a parsed document: its tag, its text, and its child elements.
 *
 * Attributes are not kept. A Maven POM puts everything in elements — there is no attribute anywhere in the
 * subset [PomParser] reads — and a field nothing reads is a field that can be wrong without anyone noticing.
 */
class XmlElement(
    val tag: String,
    /** The element's own character data, concatenated and trimmed. Empty for an element holding only elements. */
    val text: String,
    val children: List<XmlElement>,
) {
    /** The first direct child named [tag], or null. */
    fun child(tag: String): XmlElement? = children.firstOrNull { it.tag == tag }

    /** The trimmed text of the first direct child named [tag], or null when absent or empty. */
    fun childText(tag: String): String? = child(tag)?.text?.ifEmpty { null }

    /** Every direct child named [tag], in document order. */
    fun childrenNamed(tag: String): List<XmlElement> = children.filter { it.tag == tag }
}

/**
 * A small XML reader, for POMs.
 *
 * `javax.xml.parsers` is a JVM API and there is no multiplatform XML parser in this build, so this is the
 * subset a Maven `.pom` is written in: elements, character data, comments, CDATA, processing instructions,
 * and the five predefined entities plus numeric character references. It is a READER, not a validator — it
 * is fed bytes from a repository, and the useful behaviour for anything it does not understand is to fail
 * rather than to guess.
 *
 * **A DOCTYPE is rejected outright.** The JVM parser this replaces went out of its way to disable DTDs and
 * external entities, because an entity that expands to a file path or a URL turns parsing an untrusted
 * document into reading the device's disk. Not supporting them at all is the same defence with nothing to
 * switch off, and no POM in a repository has one.
 *
 * Namespaces are IGNORED, matching `isNamespaceAware = false` in the parser this replaces: a POM's own
 * `xmlns` is uniform and its elements are read by local name.
 */
object Xml {

    /** Parse [bytes] as UTF-8 XML, or throw [XmlException] when it is not XML this understands. */
    fun parse(bytes: ByteArray): XmlElement = parse(bytes.decodeToString())

    fun parse(text: String): XmlElement = Parser(text).document()

    class XmlException(message: String) : RuntimeException(message)

    private class Parser(private val s: String) {
        private var i = 0

        fun document(): XmlElement {
            skipProlog()
            val root = element() ?: fail("no root element")
            skipMisc()
            return root
        }

        /** The XML declaration, comments, processing instructions and whitespace before the root. */
        private fun skipProlog() {
            while (true) {
                skipWhitespace()
                when {
                    s.startsWith("<?", i) -> skipUntil("?>")
                    s.startsWith("<!--", i) -> skipUntil("-->")
                    // See the class doc: a DTD is not supported, deliberately.
                    s.startsWith("<!DOCTYPE", i) -> fail("DOCTYPE is not supported")
                    else -> return
                }
            }
        }

        private fun skipMisc() {
            while (true) {
                skipWhitespace()
                when {
                    s.startsWith("<!--", i) -> skipUntil("-->")
                    s.startsWith("<?", i) -> skipUntil("?>")
                    else -> return
                }
            }
        }

        /**
         * One element and everything under it, or null where a close tag is next.
         *
         * Text and children are collected together because an element may hold both; the text is the
         * concatenation of its own character data, which is what a POM's leaf values are.
         */
        private fun element(): XmlElement? {
            if (!s.startsWith("<", i)) fail("expected '<' at $i")
            if (s.startsWith("</", i)) return null
            i++ // consume '<'
            val name = readName()
            var selfClosing = false
            // Attributes are skipped rather than parsed: see the XmlElement doc.
            while (i < s.length) {
                skipWhitespace()
                when {
                    s.startsWith("/>", i) -> { i += 2; selfClosing = true }
                    s.startsWith(">", i) -> i++
                    else -> { skipAttribute(); continue }
                }
                break
            }
            if (selfClosing) return XmlElement(name, "", emptyList())

            val text = StringBuilder()
            val children = ArrayList<XmlElement>()
            while (true) {
                if (i >= s.length) fail("unclosed <$name>")
                when {
                    s.startsWith("<!--", i) -> skipUntil("-->")
                    s.startsWith("<![CDATA[", i) -> {
                        val end = s.indexOf("]]>", i)
                        if (end < 0) fail("unterminated CDATA")
                        text.append(s, i + 9, end)
                        i = end + 3
                    }

                    s.startsWith("<?", i) -> skipUntil("?>")
                    s.startsWith("</", i) -> {
                        i += 2
                        val close = readName()
                        if (close != name) fail("</$close> closes <$name>")
                        skipWhitespace()
                        if (!s.startsWith(">", i)) fail("malformed </$close>")
                        i++
                        return XmlElement(name, text.toString().trim(), children)
                    }

                    s.startsWith("<", i) -> element()?.let(children::add) ?: fail("stray '</'")
                    else -> text.append(readCharData())
                }
            }
        }

        private fun readName(): String {
            val start = i
            while (i < s.length && !s[i].isWhitespace() && s[i] != '>' && s[i] != '/') i++
            if (i == start) fail("empty tag name at $start")
            // A namespace prefix is dropped: elements are read by local name (see the class doc).
            return s.substring(start, i).substringAfterLast(':')
        }

        /** One `name="value"` (or `name='value'`), consumed and discarded. */
        private fun skipAttribute() {
            while (i < s.length && s[i] != '=' && s[i] != '>' && !s.startsWith("/>", i)) i++
            if (i < s.length && s[i] == '=') {
                i++
                skipWhitespace()
                val quote = if (i < s.length) s[i] else fail("attribute value expected")
                if (quote != '"' && quote != '\'') fail("unquoted attribute value at $i")
                i++
                val end = s.indexOf(quote, i)
                if (end < 0) fail("unterminated attribute value")
                i = end + 1
            }
        }

        /** Character data up to the next markup, with entity references resolved. */
        private fun readCharData(): String {
            val out = StringBuilder()
            while (i < s.length && s[i] != '<') {
                if (s[i] == '&') out.append(readReference()) else out.append(s[i++])
            }
            return out.toString()
        }

        /**
         * One `&...;`.
         *
         * The five predefined entities and numeric character references are the whole vocabulary: with no
         * DTD there is nothing that could define another, so an unknown name is malformed rather than
         * something to pass through.
         */
        private fun readReference(): String {
            val end = s.indexOf(';', i)
            if (end < 0 || end - i > MAX_REFERENCE) fail("unterminated entity reference at $i")
            val body = s.substring(i + 1, end)
            i = end + 1
            return when {
                body == "lt" -> "<"
                body == "gt" -> ">"
                body == "amp" -> "&"
                body == "quot" -> "\""
                body == "apos" -> "'"
                body.startsWith("#x") || body.startsWith("#X") ->
                    codePoint(body.drop(2).toIntOrNull(16) ?: fail("bad character reference &$body;"))

                body.startsWith("#") ->
                    codePoint(body.drop(1).toIntOrNull() ?: fail("bad character reference &$body;"))

                else -> fail("unknown entity &$body;")
            }
        }

        private fun codePoint(value: Int): String {
            if (value < 0 || value > MAX_CODE_POINT) fail("character reference out of range: $value")
            // Above the BMP a code point is two chars, and `Char(value)` would silently truncate it.
            if (value <= 0xFFFF) return Char(value).toString()
            val v = value - 0x10000
            return charArrayOf(Char(0xD800 + (v shr 10)), Char(0xDC00 + (v and 0x3FF))).concatToString()
        }

        private fun skipWhitespace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun skipUntil(terminator: String) {
            val end = s.indexOf(terminator, i)
            if (end < 0) fail("unterminated '$terminator'")
            i = end + terminator.length
        }

        private fun fail(message: String): Nothing = throw XmlException("$message (offset $i)")
    }

    private const val MAX_REFERENCE = 16
    private const val MAX_CODE_POINT = 0x10FFFF
}
