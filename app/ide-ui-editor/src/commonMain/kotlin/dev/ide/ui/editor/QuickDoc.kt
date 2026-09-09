package dev.ide.ui.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Quick-doc markup rendering — pure (only the text types), so it's unit-testable. Turns a RAW Javadoc / KDoc
 * comment (markup preserved) into a structured [QuickDocContent]: a description plus tag sections
 * (`@param`/`@return`/`@throws`/…). The inline pass ([inlineMarkup]) handles the common subset of BOTH
 * dialects — `{@code}`/`{@link}`, HTML `<code>/<b>/<i>/<p>/<br>/<li>`, Markdown `` `code` ``/`**bold**`, and
 * HTML entities — via a single hand-written scanner, degrading anything exotic to plain text. PLAIN docs
 * render as paragraphs only (no markup recognized beyond breaks).
 */
internal data class QuickDocContent(val description: AnnotatedString, val sections: List<DocSection>)

internal data class DocSection(val title: String, val items: List<AnnotatedString>)

internal fun parseQuickDoc(doc: String, codeStyle: SpanStyle): QuickDocContent {
    val body = stripDocMarkers(doc)
    // Partition into the leading description and the trailing block tags (a line whose first non-space is `@`).
    val descLines = ArrayList<String>()
    val tagLines = ArrayList<String>()
    var inTags = false
    for (raw in body.lines()) {
        if (!inTags && raw.trim().startsWith("@")) inTags = true
        (if (inTags) tagLines else descLines).add(raw)
    }
    val description = inlineMarkup(descLines.joinToString("\n").trim(), codeStyle)
    return QuickDocContent(description, parseTags(tagLines, codeStyle))
}

/** Strip `/**` … `*/` markers and per-line leading `*`, so the inline pass sees just the comment text. */
private fun stripDocMarkers(raw: String): String {
    var s = raw.trim()
    if (s.startsWith("/**")) s = s.substring(3) else if (s.startsWith("/*")) s = s.substring(2)
    if (s.endsWith("*/")) s = s.substring(0, s.length - 2)
    return s.lines().joinToString("\n") { line ->
        val t = line.trim()
        if (t.startsWith("*")) t.removePrefix("*").removePrefix(" ") else line
    }
}

/** Group block tags: all `@param` under "Parameters", `@return` under "Returns", etc. */
private fun parseTags(tagLines: List<String>, codeStyle: SpanStyle): List<DocSection> {
    if (tagLines.isEmpty()) return emptyList()
    // Re-fold wrapped continuation lines onto their tag line (a line not starting with `@` continues the prior).
    val tags = ArrayList<String>()
    for (line in tagLines) {
        val t = line.trim()
        if (t.startsWith("@") || tags.isEmpty()) tags.add(t) else tags[tags.size - 1] = tags.last() + " " + t
    }
    val params = ArrayList<AnnotatedString>()
    val returns = ArrayList<AnnotatedString>()
    val throws = ArrayList<AnnotatedString>()
    val see = ArrayList<AnnotatedString>()
    val other = ArrayList<AnnotatedString>()
    fun named(rest: String) = buildAnnotatedString {
        val nm = rest.substringBefore(' ')
        val desc = rest.substringAfter(' ', "").trim()
        withStyle(codeStyle) { append(nm) }
        if (desc.isNotEmpty()) { append(" — "); append(inlineMarkup(desc, codeStyle)) }
    }
    for (tag in tags) {
        val name = tag.substringBefore(' ')
        val rest = tag.substringAfter(' ', "").trim()
        when (name) {
            "@param" -> params.add(named(rest))
            "@return", "@returns" -> if (rest.isNotEmpty()) returns.add(inlineMarkup(rest, codeStyle))
            "@throws", "@exception" -> throws.add(named(rest))
            "@see" -> if (rest.isNotEmpty()) see.add(inlineMarkup(rest, codeStyle))
            "@deprecated" -> other.add(buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("Deprecated. ") }
                append(inlineMarkup(rest, codeStyle))
            })
            else -> {} // @since/@author/@version etc. omitted to keep the popup focused
        }
    }
    return buildList {
        if (params.isNotEmpty()) add(DocSection("Parameters", params))
        if (returns.isNotEmpty()) add(DocSection("Returns", returns))
        if (throws.isNotEmpty()) add(DocSection("Throws", throws))
        if (see.isNotEmpty()) add(DocSection("See also", see))
        if (other.isNotEmpty()) add(DocSection("", other))
    }
}

/**
 * Inline markup → styled text, via a single left-to-right scanner. At each position it tries (in order) a
 * code span (`` `…` ``, `{@code …}`, `<code>…</code>`), a `{@link …}` (rendered as code), bold
 * (`**…**`, `<b>/<strong>`), italic (`<i>/<em>`), the block tags normalized to breaks/bullets, then a bare
 * HTML tag (skipped); anything else is literal text. Entities are decoded; styles never overlap.
 */
internal fun inlineMarkup(input: String, codeStyle: SpanStyle): AnnotatedString {
    val s = input
        .replace(Regex("(?i)<p\\s*/?>"), "\n\n")
        .replace(Regex("(?i)</p>"), "")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)<li>"), "\n• ")
        .replace(Regex("(?i)</li>|</?ul>|</?ol>"), "")
    return buildAnnotatedString {
        val plain = StringBuilder()
        fun flush() { if (plain.isNotEmpty()) { append(decodeEntities(plain.toString())); plain.clear() } }
        fun code(text: String) { flush(); withStyle(codeStyle) { append(decodeEntities(text)) } }
        fun styled(text: String, style: SpanStyle) { flush(); withStyle(style) { append(decodeEntities(stripTags(text))) } }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '`') {
                val end = s.indexOf('`', i + 1)
                if (end > i) { code(s.substring(i + 1, end)); i = end + 1; continue }
            }
            if (c == '{' && s.startsWith("{@", i)) {
                val close = s.indexOf('}', i)
                if (close > i) { code(linkOrCodeContent(s.substring(i + 2, close))); i = close + 1; continue }
            }
            if (c == '<') {
                matchTag(s, i, "code", "tt")?.let { code(it.first); i = it.second; continue }
                matchTag(s, i, "b", "strong")?.let { styled(it.first, SpanStyle(fontWeight = FontWeight.Bold)); i = it.second; continue }
                matchTag(s, i, "i", "em")?.let { styled(it.first, SpanStyle(fontStyle = FontStyle.Italic)); i = it.second; continue }
                val close = s.indexOf('>', i)
                if (close > i && Regex("</?[a-zA-Z][^>]*>").matches(s.substring(i, close + 1))) { i = close + 1; continue }
            }
            if (c == '*' && s.startsWith("**", i)) {
                val end = s.indexOf("**", i + 2)
                if (end > i) { styled(s.substring(i + 2, end), SpanStyle(fontWeight = FontWeight.Bold)); i = end + 2; continue }
            }
            plain.append(c); i++
        }
        flush()
    }
}

/** The displayable content of a `{@tag …}` body: a `{@link pkg.Foo#bar label}` keeps its label (or simple
 *  member name); `{@code x}` / `{@value}` keep their text. */
private fun linkOrCodeContent(inner: String): String {
    val tag = inner.substringBefore(' ')
    val content = inner.substringAfter(' ', "").trim()
    return when (tag) {
        "link", "linkplain" ->
            content.substringAfter(' ', "").trim().ifEmpty { content.substringBefore(' ').substringAfterLast('.').ifEmpty { content } }
        else -> content.ifEmpty { inner }
    }
}

/** If [s] at [i] opens `<name>` (any of [names], case-insensitive), the inner text + the index past `</name>`. */
private fun matchTag(s: String, i: Int, vararg names: String): Pair<String, Int>? {
    for (n in names) {
        val open = "<$n>"
        if (s.regionMatches(i, open, 0, open.length, ignoreCase = true)) {
            val close = "</$n>"
            var k = i + open.length
            while (k + close.length <= s.length) {
                if (s.regionMatches(k, close, 0, close.length, ignoreCase = true)) {
                    return s.substring(i + open.length, k) to (k + close.length)
                }
                k++
            }
        }
    }
    return null
}

private fun stripTags(s: String): String = s.replace(Regex("</?[a-zA-Z][^>]*>"), "")

private fun decodeEntities(s: String): String = s
    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
    .replace("&#64;", "@").replace("&nbsp;", " ").replace("&amp;", "&")
