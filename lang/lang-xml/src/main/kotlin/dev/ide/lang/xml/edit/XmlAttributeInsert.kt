package dev.ide.lang.xml.edit

import dev.ide.lang.xml.XmlNode

/**
 * Where a newly written attribute goes inside a start tag, and what whitespace comes before it. One place
 * decides "same line or its own line" so every writer of an attribute (the XML quick-fixes and the layout
 * attribute editor) lands it the way the element is already written: Android XML is conventionally one
 * attribute per line, and an attribute appended to the end of such an element belongs on a fresh line with
 * its siblings' indent, not stuck onto the last one.
 *
 * The style is read off the element itself rather than a formatter setting, so a compact single-line element
 * (`<TextView android:id="@+id/x"/>`) keeps its shape and a multi-line one keeps its column.
 */
object XmlAttributeInsert {

    /** Just after [tag]'s last attribute, else just after its name: where an *appended* attribute goes. */
    fun offsetAfterAttributes(tag: XmlNode): Int =
        tag.attributes.maxByOrNull { it.endOffset }?.endOffset ?: offsetAfterName(tag)

    /** Just after [tag]'s name: where an attribute that must come FIRST (an `xmlns:` declaration) goes. */
    fun offsetAfterName(tag: XmlNode): Int = tag.startOffset + 1 + (tag.name?.length ?: 0)

    /**
     * The whitespace to write before an attribute appended at [offsetAfterAttributes]: a newline plus the
     * indent of the last attribute that starts its own line (the one-per-line style), else a single space
     * when the element keeps every attribute on the tag's line.
     */
    fun separatorForAppend(src: CharSequence, tag: XmlNode): String {
        val indent = tag.attributes.sortedBy { it.startOffset }.asReversed()
            .firstNotNullOfOrNull { ownLineIndent(src, it.startOffset) }
        return if (indent != null) "\n$indent" else " "
    }

    /**
     * The whitespace to write before an attribute inserted at [offsetAfterName] (an `xmlns:` declaration,
     * which belongs before the element's other attributes): a newline plus the FIRST attribute's indent when
     * the element writes one attribute per line, else a single space.
     */
    fun separatorForPrepend(src: CharSequence, tag: XmlNode): String {
        val first = tag.attributes.minByOrNull { it.startOffset } ?: return " "
        val indent = ownLineIndent(src, first.startOffset)
        return if (indent != null) "\n$indent" else " "
    }

    /** The indent of [offset]'s line when everything before [offset] on that line is whitespace (i.e. it
     *  starts its own line), else null. Null at the very start of the buffer, which is the tag itself. */
    private fun ownLineIndent(src: CharSequence, offset: Int): String? {
        val end = offset.coerceIn(0, src.length)
        var i = end
        while (i > 0) {
            val c = src[i - 1]
            if (c == '\n' || c == '\r') return src.substring(i, end)
            if (!c.isWhitespace()) return null
            i--
        }
        return null
    }
}
