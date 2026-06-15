package dev.ide.ui.editor

/**
 * Small XML editor conveniences (parity with Android Studio), kept pure + testable. The editor stays
 * language-neutral; these are invoked only for `.xml` files.
 */
object XmlEditing {

    private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == ':' || c == '.' || c == '-'

    /**
     * Given the buffer [text] and a [caret] positioned just after a freshly typed `>`, returns the tag name
     * whose closing tag should be auto-inserted (`<TextView>|` → `TextView`), or null when nothing should be
     * closed: a self-closing `/>`, a closing/comment/PI tag, an already-followed `<…` (re-entry guard), or a
     * `>` that doesn't actually open an element. Mirrors the backend's tag scan but runs in the editor.
     */
    fun tagToClose(text: CharSequence, caret: Int): String? {
        if (caret < 2 || caret > text.length || text[caret - 1] != '>') return null
        if (text[caret - 2] == '/') return null                  // self-closing <View/>
        if (caret < text.length && text[caret] == '<') return null // already followed by a tag (re-entry/closed)

        var i = caret - 2
        var quote: Char? = null
        while (i >= 0) {
            val c = text[i]
            if (quote != null) { if (c == quote) quote = null; i--; continue }
            when (c) {
                '"', '\'' -> quote = c
                '>' -> return null                                // the previous '<' already closed
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
}
