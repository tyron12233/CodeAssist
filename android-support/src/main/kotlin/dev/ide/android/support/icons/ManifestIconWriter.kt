package dev.ide.android.support.icons

/**
 * Points a manifest's `<application>` at a launcher icon, editing the text surgically.
 *
 * A launcher icon is only actually used if the manifest references it, and a project may already say
 * `android:icon="@mipmap/something_else"`. Rather than rewriting the document (which would lose comments and
 * formatting), this replaces just the attribute values it needs to, and inserts the attributes only when they
 * are absent. It is deliberately a text edit and not a DOM round-trip for exactly that reason.
 */
object ManifestIconWriter {

    /**
     * [manifestText] with `<application>`'s icon attributes set from [edit], or null when nothing needed
     * changing (already correct, or there is no `<application>` element to edit).
     */
    fun apply(manifestText: String, edit: ManifestIconEdit): String? {
        val tag = applicationTag(manifestText) ?: return null
        var text = manifestText
        var changed = false

        // Later edits shift earlier offsets, so apply the last attribute first and re-locate between passes.
        val updates = buildList {
            add(ICON to edit.iconRef)
            edit.roundIconRef?.let { add(ROUND_ICON to it) }
        }
        for ((attribute, value) in updates) {
            val currentTag = applicationTag(text) ?: return if (changed) text else null
            val result = upsert(text, currentTag, attribute, value)
            if (result != null) {
                text = result
                changed = true
            }
        }
        return if (changed) text else null
    }

    /** The current `android:icon` reference in [manifestText], or null. */
    fun currentIcon(manifestText: String): String? =
        applicationTag(manifestText)?.let { attributeValue(manifestText, it, ICON)?.second }

    /** The half-open range of the `<application …>` start tag, or null when there isn't one. */
    private fun applicationTag(text: String): IntRange? {
        var from = 0
        while (true) {
            val start = text.indexOf("<application", from)
            if (start < 0) return null
            val after = text.getOrNull(start + "<application".length)
            // Guard against a longer element name that merely starts the same way.
            if (after != null && (after.isWhitespace() || after == '>' || after == '/')) {
                val end = tagEnd(text, start) ?: return null
                return start..end
            }
            from = start + 1
        }
    }

    /** The index of the `>` closing the tag that starts at [start], skipping any inside quoted values. */
    private fun tagEnd(text: String, start: Int): Int? {
        var i = start
        var quote: Char? = null
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i
            }
            i++
        }
        return null
    }

    /** The value range and text of [attribute] within [tag], or null when it isn't present. */
    private fun attributeValue(text: String, tag: IntRange, attribute: String): Pair<IntRange, String>? {
        val region = text.substring(tag)
        val match = Regex("""\b${Regex.escape(attribute)}\s*=\s*(["'])(.*?)\1""").find(region) ?: return null
        val group = match.groups[2] ?: return null
        val offset = tag.first
        return (offset + group.range.first)..(offset + group.range.last) to group.value
    }

    /** [text] with [attribute] set to [value] inside [tag], or null when it already says that. */
    private fun upsert(text: String, tag: IntRange, attribute: String, value: String): String? {
        val existing = attributeValue(text, tag, attribute)
        if (existing != null) {
            if (existing.second == value) return null
            return text.replaceRange(existing.first.first, existing.first.last + 1, value)
        }
        // Insert as the first attribute, right after the element name: valid wherever the tag is formatted,
        // including a self-closing `<application/>`.
        val insertAt = tag.first + "<application".length
        return text.replaceRange(insertAt, insertAt, "\n        $attribute=\"$value\"")
    }

    private const val ICON = "android:icon"
    private const val ROUND_ICON = "android:roundIcon"
}
