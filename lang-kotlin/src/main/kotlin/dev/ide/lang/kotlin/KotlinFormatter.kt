package dev.ide.lang.kotlin

import dev.ide.lang.dom.TextRange
import dev.ide.lang.formatting.FormatStyle
import dev.ide.lang.formatting.FormattingService
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Editor-only Kotlin formatter. The bundled compiler ships the PSI parser but NOT IntelliJ's
 * formatting model, so this is a hand-rolled formatter over the parse-only PSI:
 *  - re-indents each line from its brace/paren/bracket nesting depth (a line that opens with a closer
 *    dedents to the enclosing level);
 *  - trims trailing whitespace;
 *  - collapses runs of blank lines down to the configured maximum;
 *  - ensures the file ends with exactly one newline;
 *  - normalizes inline spacing on a line (around operators, after commas, within parens, before `{`,
 *    around `->`) by rewriting the whitespace BETWEEN tokens — full-file format only.
 *
 * It deliberately does NOT reflow long lines or move braces to a different line (that needs a real
 * formatting model). Lines that begin or end inside a multi-line string or block comment are left
 * byte-for-byte untouched, so reformatting never alters string contents or comment bodies. The result is a
 * single minimal edit (prefix/suffix-trimmed), so an already-formatted buffer produces no edit (idempotent).
 */
class KotlinFormatter : FormattingService {

    override suspend fun format(file: VirtualFile, text: CharSequence, style: FormatStyle): List<DocumentEdit> =
        reformat(file.name, text.toString(), style, 0, text.length)

    override suspend fun formatRange(
        file: VirtualFile,
        text: CharSequence,
        range: TextRange,
        style: FormatStyle,
    ): List<DocumentEdit> {
        val src = text.toString()
        val start = range.start.coerceIn(0, src.length)
        val end = range.end.coerceIn(start, src.length)
        return reformat(file.name, src, style, start, end)
    }

    /** A bracket leaf token that lives in code (string/comment interiors are excluded by the scan). */
    private data class Tok(val start: Int, val ws: Boolean, val open: Boolean, val close: Boolean)

    /** Bracket tokens (code only) + the ranges of multi-line strings/comments that must stay verbatim. */
    private class Scan(val tokens: List<Tok>, val protectedRanges: List<IntArray>)

    companion object {
        private val OPENERS: Set<IElementType> = setOf(KtTokens.LBRACE, KtTokens.LPAR, KtTokens.LBRACKET)
        private val CLOSERS: Set<IElementType> = setOf(KtTokens.RBRACE, KtTokens.RPAR, KtTokens.RBRACKET)

        /**
         * Reformat [src]; only lines overlapping `[rangeStart, rangeEnd)` are rewritten, the rest stay
         * verbatim. Returns a single minimal edit, or empty when the buffer is already formatted. Exposed for
         * tests (pure, no engine state).
         */
        fun reformat(name: String, src: String, style: FormatStyle, rangeStart: Int, rangeEnd: Int): List<DocumentEdit> {
            if (src.isEmpty()) return emptyList()
            val ktFile = KotlinParserHost.parse(name, src)
            val scan = scan(ktFile, src)
            val lines = lineRanges(src)
            val fullFile = rangeStart <= 0 && rangeEnd >= src.length

            // A line is protected when a multi-line string/comment straddles its start (its leading whitespace
            // is string/comment content) or its end (trailing trim would change it).
            val protectStart = BooleanArray(lines.size)
            val protectEnd = BooleanArray(lines.size)
            for (pr in scan.protectedRanges) {
                for (i in lines.indices) {
                    val (ls, le) = lines[i]
                    if (pr[0] < ls && pr[1] > ls) protectStart[i] = true
                    if (pr[0] < le && pr[1] > le) protectEnd[i] = true
                }
            }

            val indent = indentLevels(scan.tokens, lines)
            val unit = style.indentUnit()

            val newLines = ArrayList<String>(lines.size)
            for (i in lines.indices) {
                val (ls, le) = lines[i]
                val raw = src.substring(ls, le)
                val inRange = le > rangeStart && ls < rangeEnd
                if (!inRange || protectStart[i]) {
                    newLines += raw
                    continue
                }
                val stripped = raw.trimStart(' ', '\t')
                val body = if (protectEnd[i]) stripped else stripped.trimEnd(' ', '\t')
                newLines += if (body.isEmpty()) "" else unit.repeat(indent[i]) + body
            }

            val collapsed = if (fullFile) collapseBlankLines(newLines, protectStart, style.blankLinesToKeep.coerceAtLeast(0)) else newLines
            var dst = collapsed.joinToString("\n")
            // Single trailing newline (full-file only, and not when EOF sits inside an unterminated string/comment).
            if (fullFile && !protectStart.last() && !protectEnd.last()) {
                dst = dst.trimEnd('\n') + "\n"
            }

            // Inline spacing normalization (full-file only — it re-parses the re-indented text; range mode
            // stays indentation-only to keep edits confined to the selection).
            if (fullFile) dst = applyInlineSpacing(name, dst, style)

            if (dst == src) return emptyList()
            return listOf(minimalEdit(src, dst))
        }

        private fun collapseBlankLines(lines: List<String>, protectStart: BooleanArray, maxBlank: Int): List<String> {
            val out = ArrayList<String>(lines.size)
            var run = 0
            for (i in lines.indices) {
                val blank = lines[i].isEmpty() && !protectStart[i]
                if (blank) {
                    run++
                    if (run > maxBlank) continue
                } else {
                    run = 0
                }
                out += lines[i]
            }
            return out
        }

        /** The indentation level (in units) of each line, from bracket-nesting depth. */
        private fun indentLevels(tokens: List<Tok>, lines: List<Pair<Int, Int>>): IntArray {
            val levels = IntArray(lines.size)
            var depth = 0
            var ti = 0
            for (i in lines.indices) {
                val (ls, le) = lines[i]
                while (ti < tokens.size && tokens[ti].start < ls) ti++
                var leadingClosers = 0
                var stillLeading = true
                var net = 0
                var tj = ti
                while (tj < tokens.size && tokens[tj].start < le) {
                    val t = tokens[tj]
                    if (!t.ws) {
                        if (stillLeading) {
                            if (t.close) leadingClosers++ else stillLeading = false
                        }
                        if (t.open) net++
                        if (t.close) net--
                    }
                    tj++
                }
                levels[i] = (depth - leadingClosers).coerceAtLeast(0)
                depth = (depth + net).coerceAtLeast(0)
                ti = tj
            }
            return levels
        }

        /**
         * One PSI walk: collect bracket leaf tokens that live in CODE (interiors of multi-line strings and
         * comments are skipped, so their braces/quotes never shift the depth) plus the whole-node ranges of
         * multi-line strings / block comments / KDoc (the regions that must be reproduced byte-for-byte).
         */
        private fun scan(ktFile: KtFile, src: String): Scan {
            val tokens = ArrayList<Tok>(256)
            val protectedRanges = ArrayList<IntArray>()
            var protectDepth = 0
            fun walk(e: PsiElement) {
                val protectedNode = e is KtStringTemplateExpression || e is PsiComment || e is KDoc
                if (protectedNode) {
                    val r = e.textRange
                    if (hasNewline(src, r.startOffset, r.endOffset)) protectedRanges += intArrayOf(r.startOffset, r.endOffset)
                    protectDepth++
                }
                val first = e.firstChild
                if (first == null) {
                    if (protectDepth == 0) {
                        val r = e.textRange
                        if (r.endOffset > r.startOffset) {
                            val et = e.node?.elementType
                            tokens += Tok(r.startOffset, et == KtTokens.WHITE_SPACE, et in OPENERS, et in CLOSERS)
                        }
                    }
                } else {
                    var c: PsiElement? = first
                    while (c != null) {
                        walk(c)
                        c = c.nextSibling
                    }
                }
                if (protectedNode) protectDepth--
            }
            walk(ktFile)
            return Scan(tokens, protectedRanges)
        }

        private fun hasNewline(src: String, start: Int, end: Int): Boolean {
            for (i in start until end) if (src[i] == '\n') return true
            return false
        }

        /** One atom in the spacing scan: a code leaf token, or a whole string / comment treated as opaque. */
        private class Atom(val start: Int, val end: Int, val type: IElementType?)

        /**
         * Normalize the whitespace BETWEEN adjacent tokens on a single line (never across a newline, never
         * inside a string/comment) per the inline-spacing knobs: around binary operators and `=`, after a
         * comma, just inside parentheses, before a `{`, and around a lambda `->`. Binary operators are found
         * from [KtBinaryExpression] PSI (not token adjacency) so a unary `-x` is never spaced like `a - b`.
         * Returns [text] with the gaps rewritten; gaps with no matching rule are left exactly as they are.
         */
        private fun applyInlineSpacing(name: String, text: String, style: FormatStyle): String {
            val ktFile = KotlinParserHost.parse(name, text)
            val binaryOps = HashSet<Int>()
            val atoms = ArrayList<Atom>()
            fun walk(e: PsiElement) {
                if (e is KtBinaryExpression) binaryOps += e.operationReference.textRange.startOffset
                if (e is KtStringTemplateExpression || e is PsiComment || e is KDoc) {
                    val r = e.textRange
                    atoms += Atom(r.startOffset, r.endOffset, null)
                    return
                }
                val first = e.firstChild
                if (first == null) {
                    val r = e.textRange
                    val et = e.node?.elementType
                    if (r.endOffset > r.startOffset && et != KtTokens.WHITE_SPACE) atoms += Atom(r.startOffset, r.endOffset, et)
                    return
                }
                var c: PsiElement? = first
                while (c != null) {
                    walk(c)
                    c = c.nextSibling
                }
            }
            walk(ktFile)
            atoms.sortBy { it.start }

            val sb = StringBuilder(text)
            // Edits are independent per gap and applied descending so offsets stay valid.
            data class Edit(val start: Int, val end: Int, val replacement: String)
            val edits = ArrayList<Edit>()
            for (i in 0 until atoms.size - 1) {
                val l = atoms[i]
                val r = atoms[i + 1]
                if (r.start < l.end) continue // overlap guard; r.start == l.end is a zero-width gap (may insert)
                if (hasNewline(text, l.end, r.start)) continue
                val desired = desiredGap(l, r, binaryOps, style) ?: continue
                if (text.substring(l.end, r.start) != desired) edits += Edit(l.end, r.start, desired)
            }
            for (e in edits.sortedByDescending { it.start }) sb.replace(e.start, e.end, e.replacement)
            return sb.toString()
        }

        /** The whitespace a gap between [l] and [r] should hold, or null to leave it untouched. */
        private fun desiredGap(l: Atom, r: Atom, binaryOps: Set<Int>, style: FormatStyle): String? {
            // Binary operators (incl. `=` assignment/named-arg/default) — found via PSI, so unary is excluded.
            if (l.start in binaryOps || r.start in binaryOps || l.type == KtTokens.EQ || r.type == KtTokens.EQ) {
                return if (style.spaceAroundOperators) " " else ""
            }
            if (r.type == KtTokens.COMMA) return ""
            if (l.type == KtTokens.COMMA) return if (style.spaceAfterComma) " " else ""
            if (l.type == KtTokens.ARROW || r.type == KtTokens.ARROW) return if (style.spaceAroundLambdaArrow) " " else ""
            if (r.type == KtTokens.LBRACE && l.type != KtTokens.LPAR) return if (style.spaceBeforeBrace) " " else ""
            if (l.type == KtTokens.LPAR && r.type == KtTokens.RPAR) return "" // keep `()` tight
            if (l.type == KtTokens.LPAR || r.type == KtTokens.RPAR) return if (style.spaceWithinParens) " " else ""
            return null
        }

        /** Line spans `[start, end)` (end at the '\n' or EOF); a trailing newline yields a final empty line. */
        private fun lineRanges(src: String): List<Pair<Int, Int>> {
            val res = ArrayList<Pair<Int, Int>>()
            var start = 0
            for (i in src.indices) if (src[i] == '\n') {
                res += start to i
                start = i + 1
            }
            res += start to src.length
            return res
        }

        /** A single edit replacing only the span that differs (common prefix + suffix trimmed). */
        private fun minimalEdit(src: String, dst: String): DocumentEdit {
            var p = 0
            val max = minOf(src.length, dst.length)
            while (p < max && src[p] == dst[p]) p++
            var s = src.length
            var d = dst.length
            while (s > p && d > p && src[s - 1] == dst[d - 1]) {
                s--
                d--
            }
            return DocumentEdit(p, s - p, dst.substring(p, d))
        }
    }
}
