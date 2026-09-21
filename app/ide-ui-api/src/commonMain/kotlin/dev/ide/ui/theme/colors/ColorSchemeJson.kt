package dev.ide.ui.theme.colors

/**
 * The on-disk / on-the-wire form of an [EditorColorScheme]: one small JSON document.
 *
 * JSON because a scheme is meant to leave the device — exported, mailed, pasted into an issue, committed
 * next to a project — and it has to be something a person can read and hand-edit. The reader below is a
 * few dozen lines rather than a dependency because this module also builds for iOS and the schema is one
 * fixed, flat shape; it is still a real parser (it rejects malformed input rather than pattern-matching its
 * way through) because the input is a file someone else wrote.
 *
 * ```json
 * { "schema": 1, "id": "midnight", "name": "Midnight", "basedOn": "one",
 *   "dark":  { "keyword": { "fg": "#C678DD", "bold": true },
 *              "kotlin.function.extension": { "inherit": true, "italic": true } },
 *   "light": { "keyword": { "fg": "#A626A4" } } }
 * ```
 *
 * A scheme records only what it overrides, so an absent key means "no opinion" and stays that way through a
 * round trip — writing out the resolved values instead would freeze every fallback the day it was exported.
 */
object ColorSchemeJson {

    const val SCHEMA_VERSION = 1

    fun encode(scheme: EditorColorScheme, pretty: Boolean = true): String {
        val nl = if (pretty) "\n" else ""
        val sp = if (pretty) "  " else ""
        val sb = StringBuilder()
        sb.append('{').append(nl)
        sb.append(sp).append("\"schema\": ").append(SCHEMA_VERSION).append(',').append(nl)
        sb.append(sp).append("\"id\": ").append(quote(scheme.id)).append(',').append(nl)
        sb.append(sp).append("\"name\": ").append(quote(scheme.name)).append(',').append(nl)
        scheme.basedOn?.let { sb.append(sp).append("\"basedOn\": ").append(quote(it)).append(',').append(nl) }
        sb.append(sp).append("\"dark\": ").append(encodeVariant(scheme.dark, pretty)).append(',').append(nl)
        sb.append(sp).append("\"light\": ").append(encodeVariant(scheme.light, pretty)).append(nl)
        sb.append('}')
        return sb.toString()
    }

    private fun encodeVariant(styles: Map<String, AttributeStyle>, pretty: Boolean): String {
        if (styles.isEmpty()) return "{}"
        val nl = if (pretty) "\n" else ""
        val sb = StringBuilder("{").append(nl)
        // Sorted so two exports of the same scheme are the same bytes: a scheme kept in version control
        // should produce a diff when it changes and nothing when it does not.
        val keys = styles.keys.sorted()
        keys.forEachIndexed { index, key ->
            if (pretty) sb.append("    ")
            sb.append(quote(key)).append(": ").append(encodeStyle(styles.getValue(key)))
            if (index != keys.lastIndex) sb.append(',')
            sb.append(nl)
        }
        if (pretty) sb.append("  ")
        return sb.append('}').toString()
    }

    private fun encodeStyle(style: AttributeStyle): String {
        val parts = ArrayList<String>(7)
        style.foreground?.let { parts.add("\"fg\": ${quote(it.toHex())}") }
        style.background?.let { parts.add("\"bg\": ${quote(it.toHex())}") }
        style.bold?.let { parts.add("\"bold\": $it") }
        style.italic?.let { parts.add("\"italic\": $it") }
        style.underline?.let { parts.add("\"underline\": $it") }
        style.strikethrough?.let { parts.add("\"strikethrough\": $it") }
        if (style.inheritParent) parts.add("\"inherit\": true")
        return parts.joinToString(", ", "{ ", " }")
    }

    /**
     * Parse a scheme document, or null when it is not one.
     *
     * [fallbackId]/[fallbackName] cover an imported file that names neither — an id is derived from the
     * name so an import always lands somewhere addressable rather than being rejected on a technicality.
     */
    fun decode(text: String, fallbackId: String? = null, fallbackName: String? = null): EditorColorScheme? {
        val root = runCatching { JsonReader(text).readValue() }.getOrNull() as? Map<*, *> ?: return null
        val name = (root["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallbackName?.takeIf { it.isNotEmpty() }
            ?: return null
        val id = (root["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallbackId?.takeIf { it.isNotEmpty() }
            ?: slugOf(name)
        return EditorColorScheme(
            id = id,
            name = name,
            builtIn = false,
            dark = decodeVariant(root["dark"]),
            light = decodeVariant(root["light"]),
            basedOn = (root["basedOn"] as? String)?.takeIf { it.isNotEmpty() },
        )
    }

    private fun decodeVariant(node: Any?): Map<String, AttributeStyle> {
        val map = node as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, AttributeStyle>()
        for ((rawKey, rawValue) in map) {
            val key = rawKey as? String ?: continue
            val style = decodeStyle(rawValue) ?: continue
            if (!style.isEmpty) out[key] = style
        }
        return out
    }

    private fun decodeStyle(node: Any?): AttributeStyle? {
        // A bare string is accepted as shorthand for a foreground: `"keyword": "#CC7832"` is what a person
        // writes by hand, and rejecting it would make the format harder to use than it needs to be.
        if (node is String) return hexToColor(node)?.let { AttributeStyle.fg(it) }
        val map = node as? Map<*, *> ?: return null
        fun flag(vararg names: String): Boolean? = names.firstNotNullOfOrNull { map[it] as? Boolean }
        fun color(vararg names: String) = names.firstNotNullOfOrNull { (map[it] as? String)?.let(::hexToColor) }
        return AttributeStyle(
            foreground = color("fg", "foreground", "color"),
            background = color("bg", "background"),
            bold = flag("bold"),
            italic = flag("italic"),
            underline = flag("underline"),
            strikethrough = flag("strikethrough", "strike"),
            inheritParent = flag("inherit", "inheritParent") ?: false,
        )
    }

    /** A filesystem- and preference-key-safe id derived from a display name. */
    fun slugOf(name: String): String {
        val slug = name.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
        return slug.ifEmpty { "scheme" }
    }

    private fun quote(text: String): String {
        val sb = StringBuilder(text.length + 2).append('"')
        for (c in text) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }
}

/**
 * A minimal JSON reader: objects, arrays, strings, numbers, booleans and null.
 *
 * Deliberately whole rather than a shape-matcher — it is fed files from outside the app, and the cost of
 * being a real parser is that malformed input throws here instead of half-decoding into a scheme with three
 * silently dropped colors.
 */
internal class JsonReader(private val text: String) {
    private var i = 0

    fun readValue(): Any? {
        skipWhitespace()
        val value = value()
        skipWhitespace()
        if (i != text.length) fail("trailing content")
        return value
    }

    private fun value(): Any? {
        skipWhitespace()
        if (i >= text.length) fail("unexpected end of input")
        return when (val c = text[i]) {
            '{' -> obj()
            '[' -> array()
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c.isDigit()) number() else fail("unexpected '$c'")
        }
    }

    private fun obj(): Map<String, Any?> {
        expect('{')
        val out = LinkedHashMap<String, Any?>()
        skipWhitespace()
        if (peek() == '}') {
            i++
            return out
        }
        while (true) {
            skipWhitespace()
            val key = string()
            skipWhitespace()
            expect(':')
            out[key] = value()
            skipWhitespace()
            when (next()) {
                ',' -> continue
                '}' -> return out
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun array(): List<Any?> {
        expect('[')
        val out = ArrayList<Any?>()
        skipWhitespace()
        if (peek() == ']') {
            i++
            return out
        }
        while (true) {
            out.add(value())
            skipWhitespace()
            when (next()) {
                ',' -> continue
                ']' -> return out
                else -> fail("expected ',' or ']'")
            }
        }
    }

    private fun string(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (i >= text.length) fail("unterminated string")
            val c = text[i++]
            if (c == '"') return sb.toString()
            if (c != '\\') {
                sb.append(c)
                continue
            }
            if (i >= text.length) fail("unterminated escape")
            when (val e = text[i++]) {
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                'b' -> sb.append('\b')
                'f' -> sb.append('')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'u' -> {
                    if (i + 4 > text.length) fail("truncated unicode escape")
                    sb.append(text.substring(i, i + 4).toInt(16).toChar())
                    i += 4
                }
                else -> fail("bad escape '$e'")
            }
        }
    }

    private fun number(): Double {
        val start = i
        if (peek() == '-') i++
        while (i < text.length && (text[i].isDigit() || text[i] in ".eE+-")) i++
        return text.substring(start, i).toDoubleOrNull() ?: fail("bad number")
    }

    private fun <T> literal(word: String, result: T): T {
        if (!text.startsWith(word, i)) fail("expected $word")
        i += word.length
        return result
    }

    private fun skipWhitespace() {
        while (i < text.length && text[i].isWhitespace()) i++
    }

    private fun peek(): Char? = if (i < text.length) text[i] else null

    private fun next(): Char? = if (i < text.length) text[i++] else null

    private fun expect(c: Char) {
        if (peek() != c) fail("expected '$c'")
        i++
    }

    private fun fail(message: String): Nothing = throw IllegalArgumentException("$message at offset $i")
}
