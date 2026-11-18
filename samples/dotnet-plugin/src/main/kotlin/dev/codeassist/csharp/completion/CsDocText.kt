package dev.codeassist.csharp.completion

/**
 * Converts Roslyn's XML documentation (`/// <summary>...`) into plain text for popups.
 *
 * Roslyn sends only the `summary` and `param` elements of a member's XML, escaped onto one line.
 * [unescape] reverses that escaping.
 *
 * Pure, so it can be tested without a device.
 */
internal object CsDocText {

    /** Maximum popup text length. Longer text is cut at a word and ended with an ellipsis. */
    private const val LIMIT = 600

    // Markers for characters that cannot occur in documentation XML.
    private const val LINE = '\u0000'
    private const val OPEN = '\u0001'
    private const val CLOSE = '\u0002'

    private val SUMMARY = Regex("<summary>(.*?)</summary>", RegexOption.DOT_MATCHES_ALL)
    private val INHERIT = Regex("<inheritdoc")
    private val CREF = Regex("<(?:see|seealso)\\s+[^>]*?cref=\"([^\"]*)\"[^>]*?/?>")
    private val LANGWORD = Regex("<see\\s+[^>]*?langword=\"([^\"]*)\"[^>]*?/?>")
    private val HREF = Regex("<see\\s+[^>]*?href=\"([^\"]*)\"[^>]*?(?:/>|>(.*?)</see>)", RegexOption.DOT_MATCHES_ALL)
    private val REF = Regex("<(?:paramref|typeparamref)\\s+[^>]*?name=\"([^\"]*)\"[^>]*?/?>")
    private val BREAK = Regex("</para>|<br\\s*/?>")
    private val TAG = Regex("<[^>]*>")
    private val SPACES = Regex("[ \\t\\r\\n]+")

    /** Returns the member's summary text, or null when no non-empty summary exists. */
    fun summary(xml: String): String? {
        val inner = SUMMARY.find(xml)?.groupValues?.get(1) ?: return null
        return render(inner).takeIf { it.isNotEmpty() }
    }

    /** Returns the documentation for parameter [name], or null when none exists. */
    fun param(xml: String, name: String): String? {
        val pattern = Regex(
            "<param\\s+name=\"" + Regex.escape(name) + "\"\\s*>(.*?)</param>",
            RegexOption.DOT_MATCHES_ALL,
        )
        val inner = pattern.find(xml)?.groupValues?.get(1) ?: return null
        return render(inner).takeIf { it.isNotEmpty() }
    }

    /** Whether the member inherits its documentation from another declaration. */
    fun inherits(xml: String): Boolean = INHERIT.containsMatchIn(xml)

    /**
     * Converts an XML fragment to plain text.
     *
     * References are replaced with their target names (`T:System.String` becomes `String`),
     * parameter references with their names, paragraph breaks with newlines, and other tags
     * are removed. XML entities are decoded.
     */
    fun render(fragment: String): String {
        var text = fragment
        text = HREF.replace(text) { it.groupValues[2].ifBlank { it.groupValues[1] } }

        // Protect generic angle brackets from the tag removal below.
        text = CREF.replace(text) {
            shortName(it.groupValues[1]).replace('<', OPEN).replace('>', CLOSE)
        }
        text = LANGWORD.replace(text) { it.groupValues[1] }
        text = REF.replace(text) { it.groupValues[1] }
        text = BREAK.replace(text, LINE.toString())
        text = TAG.replace(text, "")
        text = decode(text)

        // Source line breaks are spaces; only explicit paragraph breaks create new lines.
        val lines = text.split(LINE)
            .map { SPACES.replace(it, " ").trim() }
            .filter { it.isNotEmpty() }

        return cut(lines.joinToString("\n").replace(OPEN, '<').replace(CLOSE, '>'))
    }

    /**
     * Extracts a short name from an XML documentation reference.
     *
     * Examples:
     * - `T:System.Collections.Generic.List{T}` becomes `List<T>`.
     * - `M:System.Math.Max(System.Int32,System.Int32)` becomes `Max`.
     */
    fun shortName(cref: String): String {
        var name = cref.substringAfter(':', cref).substringBefore('(')
        name = name.substringAfterLast('.')
        return name.replace('{', '<').replace('}', '>').replace(Regex("`+\\d+"), "")
    }

    /**
     * Decodes the line protocol escaping:
     * `\\n` becomes a newline, `\\t` a tab, and `\\\\` a backslash.
     */
    fun unescape(text: String): String {
        if ('\\' !in text) return text

        val out = StringBuilder(text.length)
        var i = 0

        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (val next = text[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    else -> out.append(next)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }

        return out.toString()
    }

    /** Decodes the XML entities used in documentation text. */
    private fun decode(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    /** Truncates long text at a nearby word boundary. */
    private fun cut(text: String): String {
        if (text.length <= LIMIT) return text

        val at = text.lastIndexOf(' ', LIMIT).takeIf { it > LIMIT / 2 } ?: LIMIT
        return text.substring(0, at).trimEnd() + "…"
    }
}