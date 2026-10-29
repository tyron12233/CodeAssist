package dev.ide.core.services

/**
 * The text side of renaming an app's package and name, kept free of the engine so it can be tested on
 * strings: validating a package name, sweeping a package out of a file, and reading and rewriting the
 * launcher label (an `@string/` resource or a literal in the manifest).
 */
internal object AppRenameText {

    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** Java's reserved words plus Kotlin's hard keywords: either one in a package segment breaks a source. */
    private val RESERVED = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
        "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
        "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
        "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null",
        "as", "fun", "in", "is", "object", "typealias", "typeof", "val", "var", "when",
    )

    /**
     * Why [pkg] can't be a package name, or null when it can. [needsDot]: an Android app's id must have at
     * least two segments, and the package becomes that id.
     */
    fun packageError(pkg: String, needsDot: Boolean): String? {
        if (pkg.isEmpty()) return "Enter a package name."
        val segments = pkg.split('.')
        if (needsDot && segments.size < 2) return "An app's package needs at least two parts, like com.example.app."
        for (segment in segments) {
            if (segment.isEmpty()) return "'$pkg' has an empty part."
            if (!IDENTIFIER.matches(segment)) return "'$segment' can't be part of a package name."
            if (segment in RESERVED) return "'$segment' is a reserved word and can't be part of a package name."
        }
        return null
    }

    /** [id] with the package [old] (or a subpackage of it) moved under [new]; [id] itself when it is neither. */
    fun renamedPackage(id: String, old: String, new: String): String = when {
        id == old -> new
        id.startsWith("$old.") -> new + id.substring(old.length)
        else -> id
    }

    /**
     * [text] with every whole occurrence of the package [old], and of its subpackages, moved under [new], or
     * null when there is none. Whole means not part of a longer name: `com.example.app` is rewritten in
     * `com.example.app.Main` and `"com.example.app"`, but not in `com.example.application` or
     * `org.com.example.app`. With [jni], C/C++ JNI symbols (`Java_com_example_app_Main_run`) follow too.
     */
    fun rewritePackage(text: String, old: String, new: String, jni: Boolean = false): String? {
        var updated = Regex("(?<![A-Za-z0-9_$.])" + Regex.escape(old) + "(?![A-Za-z0-9_$])").replace(text) { new }
        if (jni) {
            // The digit guard keeps `Java_com_example_app_1x` (package `com.example.app_x`) out of it.
            updated = Regex("(?<![A-Za-z0-9_])Java_" + Regex.escape(jniMangled(old)) + "_(?![0-9])")
                .replace(updated) { "Java_${jniMangled(new)}_" }
        }
        return updated.takeIf { it != text }
    }

    /** [pkg] as it appears in a JNI symbol name: `_` escaped as `_1`, then dots as underscores. */
    private fun jniMangled(pkg: String): String = pkg.replace("_", "_1").replace('.', '_')

    // ---- the launcher label -------------------------------------------------------------------------

    private val APPLICATION_TAG = Regex("<application\\b[^>]*>")
    private val LABEL_ATTRIBUTE = Regex("""android:label\s*=\s*(["'])(.*?)\1""", RegexOption.DOT_MATCHES_ALL)

    /** The raw `android:label` of [manifest]'s `<application>`, or null when it has none. */
    fun applicationLabel(manifest: String): String? {
        val tag = APPLICATION_TAG.find(manifest) ?: return null
        return LABEL_ATTRIBUTE.find(tag.value)?.groupValues?.get(2)
    }

    /** The string resource a label names (`@string/app_name` → `app_name`), or null for a literal label. */
    fun stringResourceName(label: String): String? =
        label.trim().removePrefix("@string/").takeIf { it != label.trim() && it.isNotEmpty() }

    /** [manifest] with its `<application>` label set to the literal [name], or null when it has no label. */
    fun withApplicationLabel(manifest: String, name: String): String? {
        val tag = APPLICATION_TAG.find(manifest) ?: return null
        val attr = LABEL_ATTRIBUTE.find(tag.value) ?: return null
        val quote = attr.groupValues[1]
        val newTag = tag.value.replaceRange(attr.range, "android:label=$quote${encodeAttribute(name, quote)}$quote")
        return manifest.replaceRange(tag.range, newTag)
    }

    private fun stringEntry(name: String) =
        Regex("""<string\s+name\s*=\s*["']${Regex.escape(name)}["']([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

    /** The text of the string resource [name] in a `values*` file, decoded as Android reads it; null if absent. */
    fun stringValue(xml: String, name: String): String? =
        stringEntry(name).find(xml)?.groupValues?.get(2)?.let(::decodeString)

    /**
     * [xml] with the string resource [name] set to [value], or null when it has no such entry. With
     * [onlyIfEquals], an entry whose current text differs is left alone: a translated name stays translated.
     */
    fun withStringValue(xml: String, name: String, value: String, onlyIfEquals: String? = null): String? {
        val match = stringEntry(name).find(xml) ?: return null
        if (onlyIfEquals != null && decodeString(match.groupValues[2]) != onlyIfEquals) return null
        val body = match.groups[2]!!.range
        return xml.replaceRange(body, encodeString(value)).takeIf { it != xml }
    }

    /** A string resource's raw text as Android reads it: XML entities, then its backslash escapes and quotes. */
    fun decodeString(raw: String): String {
        var s = unescapeXml(raw.trim())
        if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) s = s.substring(1, s.length - 1)
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                out.append(
                    when (val next = s[i + 1]) {
                        'n' -> '\n'
                        't' -> '\t'
                        else -> next
                    },
                )
                i += 2
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }

    /** [value] as a string resource's text: Android's escapes, then XML's. */
    fun encodeString(value: String): String = escapeXml(androidEscape(value))

    private fun encodeAttribute(value: String, quote: String): String {
        val escaped = escapeXml(androidEscape(value))
        return if (quote == "'") escaped.replace("'", "&apos;") else escaped.replace("\"", "&quot;")
    }

    /** Backslash-escape what a resource value treats specially, and a leading `@`/`?` that would read as a reference. */
    private fun androidEscape(value: String): String {
        val body = value.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"").replace("\n", "\\n")
        return if (body.startsWith("@") || body.startsWith("?")) "\\$body" else body
    }

    private fun escapeXml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun unescapeXml(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
}
