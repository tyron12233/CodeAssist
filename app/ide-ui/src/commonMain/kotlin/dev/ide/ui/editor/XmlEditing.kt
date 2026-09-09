package dev.ide.ui.editor

import dev.ide.ui.editor.core.RangeEdit

/**
 * Small XML editor conveniences (parity with Android Studio), kept pure + testable. The editor stays
 * language-neutral; these are invoked only for `.xml` files.
 */
object XmlEditing {

    /** Bounded look-ahead so the per-keystroke "is this tag already closed?" scan can't cost O(N) on a huge file. */
    private const val BALANCE_SCAN_LIMIT = 50_000

    private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == ':' || c == '.' || c == '-'

    /**
     * Decide whether typing `>` at [insertAt] (the offset the freshly typed `>` will occupy) should
     * auto-insert a matching close tag, returning that tag's name (`<TextView|` + `>` → `TextView`) or null.
     *
     * This is the **on-keystroke** entry point — the `>` is NOT yet in [text]. Auto-close therefore fires
     * only when the user actually types `>`, never on a caret move, deletion, paste, or programmatic edit.
     *
     * Returns null when nothing should be closed: a self-closing `/>`, a closing/comment/PI tag, a `>` that
     * doesn't open an element (typed inside an attribute value or after an already-closed `<…>`), or a tag
     * that is **already closed** by a matching `</name>` ahead (so re-completing a well-formed element's
     * open tag doesn't duplicate the closer).
     */
    fun tagToCloseOnType(text: CharSequence, insertAt: Int): String? {
        if (insertAt < 1 || insertAt > text.length) return null
        if (text[insertAt - 1] == '/') return null              // self-closing <View/>
        val name = openTagNameBefore(text, insertAt) ?: return null
        if (tagAlreadyClosed(text, insertAt, name)) return null
        return name
    }

    /**
     * The name of the open tag the `>` at [insertAt] would complete, or null. Scans back from just before
     * the insert point to the opening `<`, skipping quoted attribute values (so a `>` inside `text="a > b"`
     * doesn't read as a tag boundary). Returns null for a closing/comment/PI `<`, an already-closed `<…>`
     * (a `>` is seen first), or a `>` typed inside an attribute value (the scan eats the `<` in quote mode).
     */
    private fun openTagNameBefore(text: CharSequence, insertAt: Int): String? {
        var i = insertAt - 1
        var quote: Char? = null
        while (i >= 0) {
            val c = text[i]
            if (quote != null) { if (c == quote) quote = null; i--; continue }
            when (c) {
                '"', '\'' -> quote = c
                '>' -> return null                              // the previous '<' already closed
                '<' -> {
                    val after = text.getOrNull(i + 1) ?: return null
                    if (after == '/' || after == '!' || after == '?') return null
                    var j = i + 1
                    val s = j
                    while (j < text.length && isNameChar(text[j])) j++
                    return text.subSequence(s, j).toString().ifEmpty { null }
                }
            }
            i--
        }
        return null
    }

    /**
     * Whether the element opened by the `>` landing at [insertAt] already has a matching `</name>` ahead,
     * accounting for nested same-name elements. A bounded forward scan (quote- and self-close-aware); when
     * the matching close isn't found within the window we assume the tag is NOT closed and auto-insert it.
     */
    private fun tagAlreadyClosed(text: CharSequence, insertAt: Int, name: String): Boolean {
        var depth = 1                                           // the element we are completing
        var i = insertAt                                        // content begins just after the typed `>`
        val end = minOf(text.length, insertAt + BALANCE_SCAN_LIMIT)
        var quote: Char? = null
        while (i < end) {
            val c = text[i]
            if (quote != null) { if (c == quote) quote = null; i++; continue }
            if (c == '"' || c == '\'') { quote = c; i++; continue }
            if (c == '<') {
                val close = i + 1 < text.length && text[i + 1] == '/'
                val s = if (close) i + 2 else i + 1
                var j = s
                while (j < text.length && isNameChar(text[j])) j++
                val matches = text.subSequence(s, j).toString() == name
                if (close) {
                    if (matches && --depth == 0) return true
                    i = j
                } else {
                    val gt = tagEnd(text, j)
                    val selfClosing = gt > 0 && text[gt - 1] == '/'
                    if (matches && !selfClosing) depth++
                    i = if (gt >= 0) gt + 1 else j
                }
                continue
            }
            i++
        }
        return false
    }

    /**
     * Offset of the `>` ending the tag whose name ends at [from], skipping quoted values; -1 when the tag is
     * **unterminated** — either no `>` remains, or an unquoted `<` (the start of the next tag) is reached
     * first. Stopping at `<` is what keeps a half-typed `<a` (no `>` of its own, a sibling/child tag below)
     * from being read as closed by a *later* tag's `>` and wrongly paired with that tag's closer.
     */
    private fun tagEnd(text: CharSequence, from: Int): Int {
        var i = from
        var quote: Char? = null
        while (i < text.length) {
            val c = text[i]
            if (quote != null) { if (c == quote) quote = null }
            else if (c == '"' || c == '\'') quote = c
            else if (c == '>') return i
            else if (c == '<') return -1            // a new tag opens before this one closed → unterminated
            i++
        }
        return -1
    }

    // ---- linked tag editing (rename the matching close tag as the open tag's name is edited) ----

    /**
     * When the caret sits inside an element's **open**-tag name, returns the edit that rewrites the PAIRED
     * close tag's name to match (Android Studio's linked tag editing) — or null when there's nothing to sync:
     * the caret isn't in an open-tag name, the element is self-closing or unterminated, no matching close tag
     * is found, or the names already match.
     *
     * The editor applies this after each text edit, so `</TextView>` tracks `<TextView…>` keystroke by
     * keystroke as it becomes `<MyView…>`. The pairing is by **position** (a depth scan that ignores names),
     * so it stays correct while the open and close names momentarily differ mid-edit. Pure + bounded.
     */
    fun linkedTagRenameEdit(text: CharSequence, caret: Int): RangeEdit? {
        val open = openTagNameAt(text, caret) ?: return null
        if (open.gt < 0 || (open.gt > 0 && text[open.gt - 1] == '/')) return null // self-closing / unterminated
        val newName = text.subSequence(open.nameStart, open.nameEnd).toString()
        if (newName.isEmpty()) return null
        val close = matchingCloseNameRange(text, open.gt + 1) ?: return null
        if (text.subSequence(close.first, close.second).toString() == newName) return null
        // The close tag is entirely after the caret, so renaming it leaves the caret where the user is typing.
        return RangeEdit(close.first, close.second, newName, caret)
    }

    private class OpenTag(val nameStart: Int, val nameEnd: Int, val gt: Int)

    /** The open-tag name token the [caret] is inside (`<Lin|earLayout …>`), or null if the caret isn't in one. */
    private fun openTagNameAt(text: CharSequence, caret: Int): OpenTag? {
        if (caret < 0 || caret > text.length) return null
        // Between an open `<` and a caret resting in the tag's NAME there are only name chars, so a plain
        // backward scan that stops at the first `<`/`>` locates the tag without needing quote tracking.
        var i = caret - 1
        while (i >= 0) {
            val c = text[i]
            if (c == '>') return null                 // caret is past a tag / in content
            if (c == '<') break
            i--
        }
        if (i < 0) return null
        val after = text.getOrNull(i + 1) ?: return null
        if (after == '/' || after == '!' || after == '?') return null // close / comment / PI — not an open tag
        val nameStart = i + 1
        var j = nameStart
        while (j < text.length && isNameChar(text[j])) j++
        if (caret < nameStart || caret > j) return null // caret is in attributes, not the name
        return OpenTag(nameStart, j, tagEnd(text, j))
    }

    /**
     * The name range `[start, endExclusive)` of the close tag that pairs (by nesting depth) with the element
     * whose content begins at [contentStart], or null. Name-agnostic so it works while the open/close names
     * differ mid-edit; comment/CDATA/PI sections are skipped so a tag-like token inside them can't skew depth.
     */
    private fun matchingCloseNameRange(text: CharSequence, contentStart: Int): Pair<Int, Int>? {
        var depth = 1
        var i = contentStart
        val end = minOf(text.length, contentStart + BALANCE_SCAN_LIMIT)
        var quote: Char? = null
        while (i < end) {
            val c = text[i]
            if (quote != null) { if (c == quote) quote = null; i++; continue }
            if (c == '"' || c == '\'') { quote = c; i++; continue }
            if (c == '<') {
                val next = text.getOrNull(i + 1)
                if (next == '!' || next == '?') { i = skipSpecial(text, i, end); continue }
                val close = next == '/'
                val s = if (close) i + 2 else i + 1
                var j = s
                while (j < text.length && isNameChar(text[j])) j++
                if (close) {
                    if (--depth == 0) return s to j
                    val gt = tagEnd(text, j); i = if (gt in 0 until end) gt + 1 else end
                } else {
                    val gt = tagEnd(text, j)
                    if (!(gt > 0 && text[gt - 1] == '/')) depth++ // a self-closing tag doesn't nest
                    i = if (gt in 0 until end) gt + 1 else end
                }
                continue
            }
            i++
        }
        return null
    }

    /** Skip a `<!-- … -->` / `<![CDATA[ … ]]>` / `<!… >` / `<? … ?>` section starting at [open]; returns the
     *  offset just past it (so its contents never affect tag-depth counting). */
    private fun skipSpecial(text: CharSequence, open: Int, end: Int): Int {
        val terminator = when {
            text.startsWith("<!--", open) -> "-->"
            text.startsWith("<![CDATA[", open) -> "]]>"
            else -> ">"
        }
        var i = open + 1
        while (i < end) {
            if (text.startsWith(terminator, i)) return i + terminator.length
            i++
        }
        return end
    }
}
