package dev.ide.android.layouteditor

import dev.ide.android.layout.LayoutAttribute
import dev.ide.android.layout.LayoutDocument
import dev.ide.android.layout.LayoutElement

/**
 * A minimal XML reader for the plugin's tests, standing in for the host's parser (which the plugin, compiling
 * against the SPI only, cannot see). Well-formed input only: elements, attributes in double quotes, comments.
 */
internal fun parseTestLayout(text: String): LayoutDocument {
    var i = 0
    fun skipWs() { while (i < text.length && text[i].isWhitespace()) i++ }
    fun element(): LayoutElement? {
        while (i < text.length) {
            if (text.startsWith("<!--", i)) { i = text.indexOf("-->", i) + 3; continue }
            if (text.startsWith("<?", i)) { i = text.indexOf("?>", i) + 2; continue }
            if (text[i] == '<' && i + 1 < text.length && text[i + 1] != '/') break
            if (text[i] == '<' && text[i + 1] == '/') return null
            i++
        }
        if (i >= text.length) return null
        val start = i
        i++
        val nameStart = i
        while (i < text.length && !text[i].isWhitespace() && text[i] != '>' && text[i] != '/') i++
        val tag = text.substring(nameStart, i)
        val attrs = ArrayList<LayoutAttribute>()
        while (true) {
            skipWs()
            if (text.startsWith("/>", i)) {
                i += 2
                return LayoutElement(tag, start, i, i, true, attrs, emptyList())
            }
            if (text[i] == '>') { i++; break }
            val aStart = i
            while (text[i] != '=') i++
            val name = text.substring(aStart, i).trim()
            i = text.indexOf('"', i) + 1
            val vStart = i
            i = text.indexOf('"', i)
            attrs += LayoutAttribute(name, text.substring(vStart, i), aStart, i + 1, vStart, i)
            i++
        }
        val startTagEnd = i
        val children = ArrayList<LayoutElement>()
        while (true) {
            val child = element() ?: break
            children += child
        }
        i = text.indexOf('>', i) + 1
        return LayoutElement(tag, start, i, startTagEnd, false, attrs, children)
    }
    return LayoutDocument(text, element())
}
