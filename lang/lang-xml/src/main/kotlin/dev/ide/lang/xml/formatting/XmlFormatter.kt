package dev.ide.lang.xml.formatting

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlComment
import com.intellij.psi.xml.XmlDoctype
import com.intellij.psi.xml.XmlDocument
import com.intellij.psi.xml.XmlProcessingInstruction
import com.intellij.psi.xml.XmlProlog
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.intellij.psi.xml.XmlTokenType
import dev.ide.lang.formatting.FormatStyle
import dev.ide.lang.formatting.WrapPolicy
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.xml.PsiXmlProjection

/**
 * The XML formatter, a pass over IntelliJ's XML PSI. The bundled platform ships the XML parser but not
 * IntelliJ's formatting engine, so the layout rules are applied here directly:
 *  - each child tag, comment and processing instruction goes on its own line, one indent deeper than its
 *    parent; the end tag lines up with its start tag;
 *  - attributes wrap by the file's [XmlLayoutRules] (Android Studio's per-file-type rules, or the
 *    configured policy), continuation lines one indent in;
 *  - spacing inside tags is normalized: `name="value"` with no space around `=`, one space between
 *    attributes, none before `>`, and `<View />` or `<View/>` by option;
 *  - runs of blank lines collapse to [FormatStyle.blankLinesToKeep], and the layout/manifest rules add the
 *    blank lines Android Studio adds;
 *  - the prolog, the root and any top-level comments start at column 0 and the file ends with one newline.
 *
 * It only ever rewrites runs of whitespace (re-indenting a multi-line comment's lines included), so text
 * content, attribute values, CDATA and comment text come through untouched. An element that holds any
 * non-whitespace text (`<string>Hello <b>you</b></string>`), or declares `xml:space="preserve"`, keeps its
 * content exactly as written. An element the parser could not make sense of is left alone. The one
 * non-whitespace change is the optional [XmlFormatOptions.rearrangeAttributes].
 */
object XmlFormatter {

    /**
     * Reformat [src] (the file at [path], whose name and folder select the Android rules). Only whitespace
     * runs on lines overlapping `[rangeStart, rangeEnd)` are rewritten. Returns non-overlapping edits in
     * ascending order against [src], or empty when it is already formatted.
     */
    fun reformat(
        path: String,
        src: String,
        style: FormatStyle,
        options: XmlFormatOptions = XmlFormatOptions(),
        rangeStart: Int = 0,
        rangeEnd: Int = src.length,
    ): List<DocumentEdit> {
        if (src.isBlank()) return emptyList()
        val file = PsiXmlProjection.parsePsi(path, src)
        val document = PsiTreeUtil.getChildOfType(file, XmlDocument::class.java) ?: return emptyList()
        val rules = XmlLayoutRules.of(XmlFileKind.of(path), options)
        val run = Pass(src, style, options, rules, rangeStart, rangeEnd)
        if (!run.document(document)) return emptyList()
        return run.edits()
    }

    /** Reformat and return the resulting text, for previews and tests. */
    fun reformatText(
        path: String,
        src: String,
        style: FormatStyle,
        options: XmlFormatOptions = XmlFormatOptions(),
    ): String {
        val sb = StringBuilder(src)
        for (e in reformat(path, src, style, options).asReversed()) {
            sb.replace(e.offset, e.offset + e.oldLength, e.newText.toString())
        }
        return sb.toString()
    }

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    private class Pass(
        private val src: String,
        private val style: FormatStyle,
        private val options: XmlFormatOptions,
        private val rules: XmlLayoutRules,
        rangeStart: Int,
        rangeEnd: Int,
    ) {
        private val full = rangeStart <= 0 && rangeEnd >= src.length
        private val from = src.lastIndexOf('\n', (rangeStart - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        private val to = src.indexOf('\n', rangeEnd.coerceIn(0, src.length)).let { if (it < 0) src.length else it }
        private val nl = if (src.contains("\r\n")) "\r\n" else "\n"
        private val unit = style.indentUnit()
        private val tabWidth = style.tabWidth.coerceAtLeast(1)
        private val out = ArrayList<DocumentEdit>()

        fun edits(): List<DocumentEdit> = out.sortedBy { it.offset }

        // ---- the document ----

        /** Lay out the top level: prolog, doctype, comments and the root tag. False when the parser found
         *  nothing it could lay out safely (stray text or an error outside every element). */
        fun document(doc: XmlDocument): Boolean {
            val items = ArrayList<PsiElement>()
            fun collect(container: PsiElement): Boolean {
                var c = container.firstChild
                while (c != null) {
                    when {
                        c is XmlProlog -> if (!collect(c)) return false
                        c is PsiWhiteSpace -> Unit
                        c is XmlText -> if (c.text.isNotBlank()) return false
                        c is XmlTag || c is XmlComment || c is XmlProcessingInstruction || c is XmlDoctype -> items += c
                        else -> return false
                    }
                    c = c.nextSibling
                }
                return true
            }
            if (!collect(doc) || items.isEmpty()) return false

            var prevEnd = 0
            for ((i, item) in items.withIndex()) {
                val start = item.textRange.startOffset
                if (i == 0) gap(0, start, "") else gap(prevEnd, start, lineBreak(prevEnd, start, 0, ""))
                element(item, "")
                prevEnd = item.textRange.endOffset
            }
            gap(prevEnd, src.length, nl)
            return true
        }

        /** Lay out [item], which now starts on its own line at [indent]. */
        private fun element(item: PsiElement, indent: String) {
            when (item) {
                is XmlTag -> tag(item, indent)
                is XmlComment -> reindentComment(item, indent)
            }
        }

        // ---- tags ----

        private fun tag(tag: XmlTag, indent: String) {
            if (hasError(tag)) return
            val kids = tag.node.getChildren(null)
            val name = kids.firstOrNull { it.elementType == XmlTokenType.XML_NAME } ?: return
            val startEnd = kids.firstOrNull {
                it.elementType == XmlTokenType.XML_TAG_END || it.elementType == XmlTokenType.XML_EMPTY_ELEMENT_END
            } ?: return
            val attributes = kids.mapNotNull { it.psi as? XmlAttribute }
            startTag(tag, indent, name, attributes, startEnd)
            if (startEnd.elementType == XmlTokenType.XML_EMPTY_ELEMENT_END) return

            val endStart = kids.firstOrNull { it.elementType == XmlTokenType.XML_END_TAG_START } ?: return
            content(tag, indent, startEnd, endStart)
            // `</ name >` → `</name>`
            val endName = kids.firstAfter(endStart, XmlTokenType.XML_NAME)
            val endGt = kids.firstAfter(endStart, XmlTokenType.XML_TAG_END)
            if (endName != null) gap(endStart.end, endName.start, "")
            if (endGt != null) gap((endName ?: endStart).end, endGt.start, "")
        }

        private fun startTag(
            tag: XmlTag,
            indent: String,
            name: ASTNode,
            attributes: List<XmlAttribute>,
            startEnd: ASTNode,
        ) {
            val emptyTag = startEnd.elementType == XmlTokenType.XML_EMPTY_ELEMENT_END
            val closer = if (emptyTag && options.spaceInEmptyTag) " " else ""
            if (attributes.isEmpty()) {
                gap(name.end, startEnd.start, closer)
                return
            }

            // The attributes in their final order. Rearranging rewrites each attribute slot with the attribute
            // that belongs there (normalized, so its own `=` spacing is settled too).
            val arranged = if (options.rearrangeAttributes && full && attributes.size > 1) {
                arrange(tag, attributes)
            } else {
                attributes
            }
            val texts: List<String>
            if (arranged != attributes) {
                texts = arranged.map { normalized(it) }
                for ((i, slot) in attributes.withIndex()) replace(slot.textRange.startOffset, slot.textRange.endOffset, texts[i])
            } else {
                texts = attributes.map { it.text }
                attributes.forEach { attributeSpacing(it) }
            }

            val gapStarts = IntArray(attributes.size)
            val gapEnds = IntArray(attributes.size)
            var prevEnd = name.end
            for ((i, a) in attributes.withIndex()) {
                gapStarts[i] = prevEnd
                gapEnds[i] = a.textRange.startOffset
                prevEnd = a.textRange.endOffset
            }

            val breaks = BooleanArray(attributes.size)
            when {
                rules.wrap == WrapPolicy.ONE_PER_LINE && attributes.size > 1 -> {
                    for (i in breaks.indices) breaks[i] = true
                    breaks[0] = rules.breakBeforeFirstAttribute && !isNamespaceDeclaration(arranged[0])
                }
                rules.wrap == WrapPolicy.IF_LONG -> {
                    val closerWidth = if (emptyTag) closer.length + 2 else 1
                    var col = width(indent) + 1 + name.textLength
                    for (i in texts.indices) {
                        val text = texts[i]
                        val firstLine = text.substringBefore('\n').length +
                            if (i == texts.lastIndex && '\n' !in text) closerWidth else 0
                        // Keep the author's line breaks; add one where the next attribute would overflow.
                        val kept = src.indexOf('\n', gapStarts[i]).let { it in gapStarts[i] until gapEnds[i] }
                        breaks[i] = kept || (i > 0 && col + 1 + firstLine > style.maxLineLength)
                        col = if ('\n' in text) text.substringAfterLast('\n').length
                        else (if (breaks[i]) width(indent) + width(unit) else col + 1) + text.length
                    }
                }
                else -> Unit // NEVER, or a lone attribute: everything on the tag line
            }

            val continuation = nl + indent + unit
            for (i in attributes.indices) gap(gapStarts[i], gapEnds[i], if (breaks[i]) continuation else " ")
            gap(prevEnd, startEnd.start, closer)
        }

        /** `name = "v"` → `name="v"`. */
        private fun attributeSpacing(a: XmlAttribute) {
            val kids = a.node.getChildren(null)
            val name = kids.firstOrNull { it.elementType == XmlTokenType.XML_NAME } ?: return
            val eq = kids.firstOrNull { it.elementType == XmlTokenType.XML_EQ } ?: return
            val value = kids.firstOrNull { it.psi is XmlAttributeValue } ?: return
            gap(name.end, eq.start, "")
            gap(eq.end, value.start, "")
        }

        private fun normalized(a: XmlAttribute): String {
            val value = a.valueElement ?: return a.text
            return a.name + "=" + value.text
        }

        // ---- element content ----

        private fun content(tag: XmlTag, indent: String, startEnd: ASTNode, endStart: ASTNode) {
            if (preservesSpace(tag)) return
            val items = ArrayList<PsiElement>()
            var c = startEnd.psi.nextSibling
            while (c != null && c.node !== endStart) {
                when (c) {
                    // Any real text makes this mixed or text content, which keeps its spacing as written.
                    is XmlText -> if (c.text.isNotBlank()) return
                    is PsiWhiteSpace -> Unit
                    is XmlTag, is XmlComment, is XmlProcessingInstruction -> items += c
                    else -> return
                }
                c = c.nextSibling
            }

            val contentStart = startEnd.end
            val contentEnd = endStart.start
            if (items.isEmpty()) {
                // `<a></a>` stays put; `<a>\n\n   </a>` closes on its own line.
                gap(contentStart, contentEnd, if (hasNewline(contentStart, contentEnd)) nl + indent else "")
                return
            }

            val childIndent = indent + unit
            var prevEnd = contentStart
            var prev: PsiElement? = null
            for ((i, item) in items.withIndex()) {
                val start = item.textRange.startOffset
                if (item is XmlComment && prev != null && !hasNewline(prevEnd, start)) {
                    // A trailing comment stays on the line it annotates.
                    gap(prevEnd, start, " ")
                } else {
                    gap(prevEnd, start, lineBreak(prevEnd, start, forcedBlankLines(prev, item, items.getOrNull(i + 1)), childIndent))
                    element(item, childIndent)
                }
                prevEnd = item.textRange.endOffset
                prev = item
            }
            gap(prevEnd, contentEnd, lineBreak(prevEnd, contentEnd, 0, indent))
        }

        /** The blank lines the Android rules require before [item]. A comment travels with the tag it heads:
         *  the blank line goes above the comment, not between it and the tag. */
        private fun forcedBlankLines(prev: PsiElement?, item: PsiElement, next: PsiElement?): Int {
            if (prev is XmlComment) return 0
            return when {
                rules.blankLineBeforeTag && (item is XmlTag || (item is XmlComment && next is XmlTag)) -> 1
                rules.groupTagBlocks && prev is XmlTag && item is XmlTag && prev.name != item.name -> 1
                else -> 0
            }
        }

        // ---- comments ----

        /**
         * Shift a multi-line comment's continuation lines by as much as its first line moved, so its inner
         * alignment survives re-indentation. Skipped when the comment did not start its line before.
         */
        private fun reindentComment(comment: XmlComment, indent: String) {
            val start = comment.textRange.startOffset
            val end = comment.textRange.endOffset
            if (src.indexOf('\n', start).let { it < 0 || it >= end }) return
            val lineStart = src.lastIndexOf('\n', start - 1) + 1
            val old = src.substring(lineStart, start)
            if (old.isNotBlank()) return
            val delta = width(indent) - width(old)
            if (delta == 0) return
            var nlAt = src.indexOf('\n', start)
            while (nlAt in start until end) {
                val p = nlAt + 1
                var q = p
                while (q < end && (src[q] == ' ' || src[q] == '\t')) q++
                if (q < end && src[q] != '\n' && src[q] != '\r') {
                    gap(p, q, indentOfWidth((width(src.substring(p, q)) + delta).coerceAtLeast(0)))
                }
                nlAt = src.indexOf('\n', p)
            }
        }

        // ---- attribute order ----

        /**
         * Android Studio's attribute order: `xmlns:android`, other namespace declarations, `android:id`,
         * `android:name`, `name`, `style`, other un-prefixed attributes, `android:layout_width`,
         * `android:layout_height`, other `android:layout_*`, `android:width`, `android:height`, the rest of
         * `android:*`, then everything else (`app:`, `tools:`, …). The open-ended groups sort by name; the
         * sort is stable, so ties keep their written order.
         */
        private fun arrange(tag: XmlTag, attributes: List<XmlAttribute>): List<XmlAttribute> {
            fun rank(a: XmlAttribute): Int {
                val name = a.name
                val prefix = name.substringBefore(':', "")
                val local = name.substringAfter(':')
                val android = prefix.isNotEmpty() && (namespaceOf(tag, prefix) ?: (if (prefix == "android") ANDROID_NS else null)) == ANDROID_NS
                return when {
                    name == "xmlns:android" -> 0
                    name == "xmlns" || prefix == "xmlns" -> 1
                    android && local == "id" -> 2
                    android && local == "name" -> 3
                    prefix.isEmpty() && local == "name" -> 4
                    prefix.isEmpty() && local == "style" -> 5
                    prefix.isEmpty() -> 6
                    android && local == "layout_width" -> 7
                    android && local == "layout_height" -> 8
                    android && local.startsWith("layout_") -> 9
                    android && local == "width" -> 10
                    android && local == "height" -> 11
                    android -> 12
                    else -> 13
                }
            }
            val sortedGroups = setOf(6, 9, 12, 13)
            return attributes.sortedWith(compareBy<XmlAttribute> { rank(it) }.thenBy { if (rank(it) in sortedGroups) it.name else "" })
        }

        /** The namespace URI [prefix] is bound to at [tag], read off the `xmlns:` attributes up the tree. */
        private fun namespaceOf(tag: XmlTag, prefix: String): String? {
            var t: PsiElement? = tag
            while (t != null) {
                if (t is XmlTag) {
                    for (c in t.node.getChildren(null)) {
                        val a = c.psi as? XmlAttribute ?: continue
                        if (a.name == "xmlns:$prefix") return attributeValue(a)
                    }
                }
                t = t.parent
            }
            return null
        }

        // ---- helpers ----

        private fun isNamespaceDeclaration(a: XmlAttribute): Boolean = a.name == "xmlns" || a.name.startsWith("xmlns:")

        private fun preservesSpace(tag: XmlTag): Boolean = tag.node.getChildren(null).any {
            val a = it.psi as? XmlAttribute
            a != null && a.name == "xml:space" && attributeValue(a) == "preserve"
        }

        /** The text between an attribute's quotes, read off the source (no PSI value unescaping). */
        private fun attributeValue(a: XmlAttribute): String? {
            val r = (a.valueElement as? XmlAttributeValue)?.valueTextRange ?: return null
            return src.substring(r.startOffset, r.endOffset)
        }

        /** An error inside the tag's own markup (not inside a child tag, which judges itself). */
        private fun hasError(tag: XmlTag): Boolean = tag.node.getChildren(null).any { child ->
            child.psi is PsiErrorElement ||
                (child.psi is XmlAttribute && PsiTreeUtil.findChildOfType(child.psi, PsiErrorElement::class.java) != null)
        }

        /** Newline(s) and [indent] to replace the run `[start, end)`: the blank lines it already had, capped at
         *  the style's limit, but at least [forced]. */
        private fun lineBreak(start: Int, end: Int, forced: Int, indent: String): String {
            var newlines = 0
            for (i in start until end) if (src[i] == '\n') newlines++
            val blank = maxOf((newlines - 1).coerceIn(0, style.blankLinesToKeep.coerceAtLeast(0)), forced)
            return nl.repeat(blank + 1) + indent
        }

        private fun hasNewline(start: Int, end: Int): Boolean = src.indexOf('\n', start).let { it in start until end }

        private fun width(s: String): Int = s.sumOf { if (it == '\t') tabWidth else 1 }

        private fun indentOfWidth(w: Int): String =
            if (style.useTabs) "\t".repeat(w / tabWidth) + " ".repeat(w % tabWidth) else " ".repeat(w)

        /** Replace the whitespace run `[start, end)` with [want]. A run holding anything but whitespace is
         *  never touched, so a wrong guess about the tree can't eat content. */
        private fun gap(start: Int, end: Int, want: String) {
            if (start > end || !inRange(start, end)) return
            for (i in start until end) if (!src[i].isWhitespace()) return
            if (end - start == want.length && src.regionMatches(start, want, 0, want.length)) return
            out += DocumentEdit(start, end - start, want)
        }

        private fun replace(start: Int, end: Int, text: String) {
            if (end - start == text.length && src.regionMatches(start, text, 0, text.length)) return
            out += DocumentEdit(start, end - start, text)
        }

        private fun inRange(start: Int, end: Int): Boolean = full || (start <= to && end >= from)

        private val ASTNode.start: Int get() = startOffset
        private val ASTNode.end: Int get() = startOffset + textLength

        private fun Array<ASTNode>.firstAfter(anchor: ASTNode, type: IElementType): ASTNode? {
            var seen = false
            for (n in this) {
                if (n === anchor) seen = true else if (seen && n.elementType == type) return n
            }
            return null
        }
    }
}
