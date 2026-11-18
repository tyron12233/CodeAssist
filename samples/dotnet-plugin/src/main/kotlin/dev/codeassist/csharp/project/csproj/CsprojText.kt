package dev.codeassist.csharp.project.csproj

import dev.codeassist.csharp.project.csproj.CsprojText.Element

/**
 * Extracts elements and their source positions from project file text.
 *
 * Diagnostics use the recorded ranges, while completion uses the element containing the caret.
 * This works directly on text without an XML parser, so it can handle partially typed files.
 * Comments, CDATA sections, and processing instructions are masked first, and unfinished tags
 * are skipped.
 */
internal object CsprojText {

    /** An attribute with its name, value, and the range of its unquoted value. */
    class Attr(
        val name: String,
        val value: String,
        val valueStart: Int,
        val valueEnd: Int,
    )

    /**
     * An element and its source positions.
     *
     * [start] is the position of `<`, and [nameStart]..[nameEnd] is the tag name.
     * [parent] is the name of the containing element.
     *
     * [text] contains the content after the start tag up to the next `<`, with its range
     * recorded by [textStart]..[textEnd]. For example, this is the value in
     * `<OutputType>Exe</OutputType>`. Self-closing elements have empty text.
     */
    class Element(
        val name: String,
        val parent: String?,
        val start: Int,
        val nameStart: Int,
        val nameEnd: Int,
        val attrs: List<Attr>,
        val selfClosing: Boolean,
        val textStart: Int,
        val textEnd: Int,
        val text: String,
    ) {
        fun attr(name: String): Attr? = attrs.firstOrNull { it.name == name }
    }

    private val TAG = Regex(
        """<(/?)([A-Za-z_][\w.\-:]*)((?:\s+[\w:.\-]+\s*=\s*(?:"[^"]*"|'[^']*'))*)\s*(/?)>""",
    )

    private val ATTRIBUTE = Regex("""([\w:.\-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    private val BLANKED = Regex(
        """<!--.*?-->|<!\[CDATA\[.*?]]>|<\?.*?\?>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** Returns [text] with comments, CDATA sections, and processing instructions replaced by spaces. */
    fun mask(text: String): String {
        val out = StringBuilder(text)

        for (match in BLANKED.findAll(text)) {
            for (i in match.range) {
                if (out[i] != '\n' && out[i] != '\r') out.setCharAt(i, ' ')
            }
        }

        return out.toString()
    }

    fun elements(text: String): List<Element> {
        val clean = mask(text)
        val out = ArrayList<Element>()
        val stack = ArrayList<String>()

        for (match in TAG.findAll(clean)) {
            val closing = match.groupValues[1] == "/"
            val name = match.groupValues[2]

            if (closing) {
                val at = stack.lastIndexOf(name)
                if (at >= 0) {
                    while (stack.size > at) stack.removeAt(stack.lastIndex)
                }
                continue
            }

            val selfClosing = match.groupValues[4] == "/"
            val attrsGroup = match.groups[3]!!

            val attrs = ATTRIBUTE.findAll(attrsGroup.value).map { a ->
                val inDouble = a.groups[2] != null
                val group = a.groups[if (inDouble) 2 else 3]!!
                val base = attrsGroup.range.first

                Attr(
                    a.groupValues[1],
                    group.value,
                    base + group.range.first,
                    base + group.range.last + 1,
                )
            }.toList()

            val nameStart = match.range.first + 1
            val textStart = match.range.last + 1
            val next = clean.indexOf('<', textStart).let {
                if (it < 0) clean.length else it
            }
            val textEnd = if (selfClosing) textStart else next

            out += Element(
                name,
                stack.lastOrNull(),
                match.range.first,
                nameStart,
                nameStart + name.length,
                attrs,
                selfClosing,
                textStart,
                textEnd,
                text.substring(textStart, textEnd),
            )

            if (!selfClosing) stack += name
        }

        return out
    }

    // Open elements

    /** Returns the names of elements open at [caret], from outermost to innermost. */
    fun openAt(text: String, caret: Int): List<String> {
        val clean = mask(text.substring(0, caret.coerceIn(0, text.length)))
        val stack = ArrayList<String>()

        for (match in TAG.findAll(clean)) {
            val name = match.groupValues[2]

            if (match.groupValues[1] == "/") {
                val at = stack.lastIndexOf(name)
                if (at >= 0) {
                    while (stack.size > at) stack.removeAt(stack.lastIndex)
                }
            } else if (match.groupValues[4] != "/") {
                stack += name
            }
        }

        return stack
    }

    // Completion position

    /** Describes what kind of text the caret is currently positioned in. */
    sealed class Position {

        /** A tag name after `<`, inside [parent], or at the root when null. */
        class TagName(val typed: String, val parent: String?) : Position()

        /** A closing tag name after `</`, with the name of the element left open. */
        class ClosingName(val typed: String, val open: String?) : Position()

        /** An attribute name inside the start tag of [element]. */
        class AttributeName(val element: String, val typed: String) : Position()

        /** A quoted attribute value inside [element], for [attribute]. */
        class AttributeValue(val element: String, val attribute: String, val typed: String) : Position()

        /** Text content inside [element], including property values. */
        class Value(val element: String, val parent: String?, val typed: String) : Position()
    }

    fun positionAt(text: String, caret: Int): Position? {
        val at = caret.coerceIn(0, text.length)
        val raw = text.substring(0, at)

        // Do not offer completion inside an unfinished comment, CDATA section, or processing instruction.
        if (raw.lastIndexOf("<!--") > raw.lastIndexOf("-->")) return null
        if (raw.lastIndexOf("<![CDATA[") > raw.lastIndexOf("]]>")) return null

        val before = mask(raw)
        val lt = before.lastIndexOf('<')
        val gt = before.lastIndexOf('>')

        if (lt > gt) {
            val inside = before.substring(lt + 1)

            // Declarations and processing instructions are not completion positions.
            if (inside.startsWith("!") || inside.startsWith("?")) return null

            val open = openAt(text, lt)

            if (inside.startsWith("/")) {
                return Position.ClosingName(inside.drop(1), open.lastOrNull())
            }

            if (inside.all { it.isLetterOrDigit() || it in "_.-:" }) {
                return Position.TagName(inside, open.lastOrNull())
            }

            val element = inside.takeWhile { !it.isWhitespace() }
            val rest = inside.substring(element.length)
            val quote = rest.count { it == '"' } % 2 == 1

            if (quote) {
                val upToQuote = rest.substring(0, rest.lastIndexOf('"'))
                val attribute = Regex("""([\w:.\-]+)\s*=\s*$""")
                    .find(upToQuote)?.groupValues?.get(1) ?: return null

                return Position.AttributeValue(
                    element,
                    attribute,
                    rest.substring(rest.lastIndexOf('"') + 1),
                )
            }

            return Position.AttributeName(
                element,
                rest.takeLastWhile { it.isLetterOrDigit() || it in "_.-:" },
            )
        }

        val open = openAt(text, at)
        val element = open.lastOrNull() ?: return null
        val typed = before.substring(gt + 1)

        if ('<' in typed) return null

        return Position.Value(
            element,
            open.getOrNull(open.size - 2),
            typed.trim(),
        )
    }
}