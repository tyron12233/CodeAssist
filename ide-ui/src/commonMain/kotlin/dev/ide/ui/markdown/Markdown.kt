package dev.ide.ui.markdown

import dev.ide.ui.theme.Ide
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.theme.Ca

/**
 * The one shared Markdown renderer. A pure, line-oriented parser ([parseMarkdown]) + inline scanner
 * ([buildInline]) with a Compose renderer ([Markdown]) on top. Everywhere Markdown text is displayed — the
 * `.md` file preview, the AI chat, lesson prose — routes through here, so there is a single Markdown dialect
 * implementation in the app. (Javadoc/KDoc doc comments are a different dialect; those render through
 * `QuickDoc`.)
 *
 * The parser + inline pass are Compose-free (styles passed in) so they stay unit-testable.
 */
@Composable
fun Markdown(
    text: String,
    modifier: Modifier = Modifier,
    // Body typography for paragraphs / list items / quotes. Headings scale off the theme regardless, tinted
    // with [color]. Callers wanting a tighter look (the chat) pass a smaller style + spacing.
    paragraphStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
    spacing: Dp = Ca.spacing.s3,
    // Fenced-code rendering is pluggable so a caller can drop in a syntax-highlighted card (the chat's
    // CodeSample) without the markdown module depending on the highlighter. Defaults to a plain mono card.
    codeBlock: @Composable (code: String, lang: String) -> Unit = { code, _ -> DefaultCodeBlock(code) },
) {
    val blocks = remember(text) { parseMarkdown(text) }
    val styles = MdStyles(
        base = SpanStyle(color = color),
        code = SpanStyle(
            fontFamily = Ide.type.codeFamily,
            background = MaterialTheme.colorScheme.surfaceContainerHigh,
            color = MaterialTheme.colorScheme.onSurface,
        ),
        link = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline),
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        for (block in blocks) MarkdownBlock(block, styles, paragraphStyle, color, codeBlock)
    }
}

// ---- rendering ----------------------------------------------------------------------------------

/** The span styles the inline renderer draws with — passed in so [buildInline] stays theme-free (testable). */
class MdStyles(val base: SpanStyle, val code: SpanStyle, val link: SpanStyle)

@Composable
private fun MarkdownBlock(
    block: MdBlock,
    styles: MdStyles,
    paragraphStyle: TextStyle,
    color: Color,
    codeBlock: @Composable (code: String, lang: String) -> Unit,
) {
    when (block) {
        is MdBlock.Heading -> HeadingBlock(block, styles, color)
        is MdBlock.Paragraph -> Text(buildInline(block.text, styles), style = paragraphStyle, color = color)
        is MdBlock.Code -> codeBlock(block.code, block.lang)
        is MdBlock.Items -> ListBlock(block.items, styles, paragraphStyle, color)
        is MdBlock.Quote -> QuoteBlock(block.blocks, styles, paragraphStyle, codeBlock)
        MdBlock.Divider -> Box(
            Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

@Composable
private fun HeadingBlock(heading: MdBlock.Heading, styles: MdStyles, color: Color) {
    val style = when (heading.level) {
        1 -> MaterialTheme.typography.headlineMedium
        2 -> MaterialTheme.typography.headlineSmall
        3 -> MaterialTheme.typography.titleLarge
        4 -> MaterialTheme.typography.titleMedium
        5 -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
        else -> MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
    }
    Column(verticalArrangement = Arrangement.spacedBy(Ca.spacing.s2)) {
        Text(buildInline(heading.text, styles), style = style, color = color)
        // A rule under the top two levels, matching common Markdown rendering.
        if (heading.level <= 2) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        }
    }
}

/** The plain fenced-code card (mono text on a soft surface); the default when a caller supplies no highlighter. */
@Composable
private fun DefaultCodeBlock(code: String) {
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(Ca.radius.sm))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.sm)),
    ) {
        Text(
            code,
            style = Ide.type.codeSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.horizontalScroll(rememberScrollState())
                .padding(horizontal = Ca.spacing.s3, vertical = Ca.spacing.s2),
        )
    }
}

@Composable
private fun ListBlock(items: List<MdListItem>, styles: MdStyles, paragraphStyle: TextStyle, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(Ca.spacing.s1)) {
        for (item in items) {
            Row(Modifier.padding(start = (item.level * 16).dp)) {
                Text(
                    if (item.ordered) "${item.ordinal}." else "•",
                    style = paragraphStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(if (item.ordered) 24.dp else 16.dp),
                )
                Text(buildInline(item.text, styles), style = paragraphStyle, color = color)
            }
        }
    }
}

@Composable
private fun QuoteBlock(
    blocks: List<MdBlock>,
    styles: MdStyles,
    paragraphStyle: TextStyle,
    codeBlock: @Composable (code: String, lang: String) -> Unit,
) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.fillMaxHeight().width(3.dp).background(MaterialTheme.colorScheme.outline))
        Column(
            Modifier.padding(start = Ca.spacing.s3),
            verticalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
        ) {
            for (block in blocks) {
                when (block) {
                    is MdBlock.Paragraph -> Text(
                        buildInline(block.text, styles),
                        style = paragraphStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> MarkdownBlock(block, styles, paragraphStyle, MaterialTheme.colorScheme.onSurfaceVariant, codeBlock)
                }
            }
        }
    }
}

// ---- model + parser (pure, no Compose so it's unit-testable) ------------------------------------

/** A parsed top-level Markdown block. */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Items(val items: List<MdListItem>) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    object Divider : MdBlock
}

/** One list item; [level] is the nesting depth, [ordered] with [ordinal] for numbered lists. */
data class MdListItem(
    val level: Int,
    val ordered: Boolean,
    val ordinal: Int,
    val text: String,
)

/**
 * Parse a Markdown document into top-level blocks. Supports ATX headings, fenced code (``` / ~~~),
 * block quotes (recursively), bullet + ordered lists (with nesting by indent), thematic breaks, and
 * paragraphs. Deliberately line-oriented and tolerant — never throws on partial input; unrecognized
 * syntax degrades to a paragraph. Inline markup (`**bold**`, `` `code` ``, links) is left in the block
 * text and resolved at render time by [buildInline].
 */
fun parseMarkdown(src: String): List<MdBlock> {
    val lines = src.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = ArrayList<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.isEmpty() -> i++

            isFence(trimmed) -> {
                val fence = trimmed.take(3)
                val lang = trimmed.drop(3).trim()
                val body = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(fence)) {
                    body.add(lines[i]); i++
                }
                if (i < lines.size) i++ // consume the closing fence
                blocks.add(MdBlock.Code(lang, body.joinToString("\n")))
            }

            isThematicBreak(trimmed) -> { blocks.add(MdBlock.Divider); i++ }

            headingLevel(trimmed) > 0 -> {
                val level = headingLevel(trimmed)
                val text = trimmed.drop(level).trim().trimEnd('#').trim()
                blocks.add(MdBlock.Heading(level, text)); i++
            }

            trimmed.startsWith(">") -> {
                val inner = ArrayList<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    inner.add(lines[i].trimStart().removePrefix(">").removePrefix(" ")); i++
                }
                blocks.add(MdBlock.Quote(parseMarkdown(inner.joinToString("\n"))))
            }

            listItemOf(line) != null -> {
                val items = ArrayList<MdListItem>()
                while (i < lines.size) {
                    val item = listItemOf(lines[i]) ?: break
                    items.add(item); i++
                }
                blocks.add(MdBlock.Items(items))
            }

            else -> {
                val para = ArrayList<String>()
                while (i < lines.size) {
                    val l = lines[i]
                    val t = l.trimStart()
                    if (t.isEmpty() || isFence(t) || isThematicBreak(t) || headingLevel(t) > 0 ||
                        t.startsWith(">") || listItemOf(l) != null
                    ) break
                    para.add(l.trim()); i++
                }
                if (para.isNotEmpty()) blocks.add(MdBlock.Paragraph(para.joinToString("\n")))
            }
        }
    }
    return blocks
}

private fun isFence(trimmed: String): Boolean =
    trimmed.startsWith("```") || trimmed.startsWith("~~~")

/** ATX heading level 1..6, or 0 if the line is not a heading. */
private fun headingLevel(trimmed: String): Int {
    var hashes = 0
    while (hashes < trimmed.length && trimmed[hashes] == '#') hashes++
    if (hashes in 1..6 && (hashes == trimmed.length || trimmed[hashes] == ' ')) return hashes
    return 0
}

/** A thematic break: three or more `-`, `*`, or `_` with only spaces between (e.g. `---`, `***`, `_ _ _`). */
private fun isThematicBreak(trimmed: String): Boolean {
    val bare = trimmed.replace(" ", "")
    if (bare.length < 3) return false
    val c = bare[0]
    return (c == '-' || c == '*' || c == '_') && bare.all { it == c }
}

private val ORDERED = Regex("""^(\d{1,9})[.)]\s+(.*)$""")

/** Parse [line] as a list item (bullet or ordered), or null if it is not one. */
private fun listItemOf(line: String): MdListItem? {
    val indent = line.takeWhile { it == ' ' || it == '\t' }.sumOf { if (it == '\t') 4 else 1 }
    val level = indent / 2
    val rest = line.trimStart()
    if (rest.length >= 2 && rest[0] in "-*+" && rest[1] == ' ') {
        return MdListItem(level, ordered = false, ordinal = 0, text = rest.drop(2).trim())
    }
    val m = ORDERED.matchEntire(rest) ?: return null
    return MdListItem(level, ordered = true, ordinal = m.groupValues[1].toInt(), text = m.groupValues[2].trim())
}

// ---- inline markup ------------------------------------------------------------------------------

/**
 * Render inline Markdown ([md]) into an [AnnotatedString]: `` `code` ``, `**bold**`/`__bold__`,
 * `*italic*`/`_italic_`, `***bold italic***`, `~~strikethrough~~`, `[text](url)` links, and `![alt](url)`
 * images (rendered as their alt text, link-styled). Emphasis nests. Unbalanced markers degrade to literal
 * text. Pure (styles passed in via [styles]) so it's unit-testable.
 */
fun buildInline(md: String, styles: MdStyles): AnnotatedString = buildAnnotatedString {
    appendInline(md, styles.base, styles)
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInline(
    s: String,
    base: SpanStyle,
    st: MdStyles,
) {
    val plain = StringBuilder()
    fun flush() {
        if (plain.isNotEmpty()) {
            pushStyle(base); append(plain.toString()); pop(); plain.clear()
        }
    }
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '\\' && i + 1 < s.length -> { plain.append(s[i + 1]); i += 2 }

            c == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end < 0) { plain.append(c); i++ } else {
                    flush(); pushStyle(st.code); append(s.substring(i + 1, end)); pop(); i = end + 1
                }
            }

            s.startsWith("***", i) -> {
                val end = s.indexOf("***", i + 3)
                if (end < 0) { plain.append(c); i++ } else {
                    flush()
                    appendInline(
                        s.substring(i + 3, end),
                        base.merge(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)),
                        st,
                    )
                    i = end + 3
                }
            }

            s.startsWith("**", i) || s.startsWith("__", i) -> {
                val marker = s.substring(i, i + 2)
                val end = s.indexOf(marker, i + 2)
                if (end < 0) { plain.append(c); i++ } else {
                    flush()
                    appendInline(s.substring(i + 2, end), base.merge(SpanStyle(fontWeight = FontWeight.Bold)), st)
                    i = end + 2
                }
            }

            s.startsWith("~~", i) -> {
                val end = s.indexOf("~~", i + 2)
                if (end < 0) { plain.append(c); i++ } else {
                    flush()
                    appendInline(s.substring(i + 2, end), base.merge(SpanStyle(textDecoration = TextDecoration.LineThrough)), st)
                    i = end + 2
                }
            }

            c == '*' -> {
                val end = s.indexOf('*', i + 1)
                if (end < 0) { plain.append(c); i++ } else {
                    flush()
                    appendInline(s.substring(i + 1, end), base.merge(SpanStyle(fontStyle = FontStyle.Italic)), st)
                    i = end + 1
                }
            }

            // `_` emphasis only at a word boundary, so `snake_case` is left literal.
            c == '_' && boundaryBefore(s, i) -> {
                val end = closingUnderscore(s, i + 1)
                if (end < 0) { plain.append(c); i++ } else {
                    flush()
                    appendInline(s.substring(i + 1, end), base.merge(SpanStyle(fontStyle = FontStyle.Italic)), st)
                    i = end + 1
                }
            }

            c == '!' && i + 1 < s.length && s[i + 1] == '[' -> {
                val link = linkAt(s, i + 1)
                if (link == null) { plain.append(c); i++ } else {
                    flush(); pushStyle(st.link); append(link.text); pop(); i = link.end
                }
            }

            c == '[' -> {
                val link = linkAt(s, i)
                if (link == null) { plain.append(c); i++ } else {
                    flush(); pushStyle(st.link); append(link.text); pop(); i = link.end
                }
            }

            else -> { plain.append(c); i++ }
        }
    }
    flush()
}

private fun boundaryBefore(s: String, i: Int): Boolean =
    i == 0 || !s[i - 1].isLetterOrDigit()

/** Index of a closing `_` that ends an italic run (must be followed by a non-word char), or -1. */
private fun closingUnderscore(s: String, from: Int): Int {
    var i = from
    while (i < s.length) {
        if (s[i] == '_' && (i + 1 == s.length || !s[i + 1].isLetterOrDigit())) return i
        i++
    }
    return -1
}

private class InlineLink(val text: String, val end: Int)

/** Parse a `[text](url)` link whose `[` is at [start]; returns the display text + index past the `)`. */
private fun linkAt(s: String, start: Int): InlineLink? {
    val close = s.indexOf(']', start + 1)
    if (close < 0 || close + 1 >= s.length || s[close + 1] != '(') return null
    val paren = s.indexOf(')', close + 2)
    if (paren < 0) return null
    val text = s.substring(start + 1, close)
    return InlineLink(text.ifEmpty { s.substring(close + 2, paren) }, paren + 1)
}
