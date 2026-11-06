package com.zipcli.ke.domain.script

/** How a statement is linked to the NEXT one. */
enum class Connector { SEQ, AND, OR }

sealed interface Node { val line: Int }

/**
 * One command, kept as raw text (quotes intact) so variables/globs are expanded at run time.
 * [heredoc] is the byte-exact body of a `<<EOF` block attached to the command (null when there is none).
 */
data class CmdNode(val raw: String, override val line: Int, val heredoc: String? = null) : Node

data class ForNode(
    val variable: String,
    val itemsRaw: String,
    val body: List<Item>,
    override val line: Int,
) : Node

data class Item(val node: Node, val next: Connector?)

/**
 * A script that cannot be parsed. [lineText] / [column] (0-based) locate the problem on its physical line
 * so the runner can print the line with a caret under it.
 */
class ScriptSyntaxException(
    message: String,
    val line: Int,
    val lineText: String? = null,
    val column: Int? = null,
    /** Text printed next to the caret, e.g. "opened here, never closed". */
    val caretNote: String? = null,
) : Exception(message) {
    /** The message plus, when known, the offending line and a caret (see docs/SCRIPTING.md). */
    fun render(): List<String> {
        val head = "Syntax error (line $line): $message"
        if (lineText == null || column == null) return listOf(head)
        val num = line.toString()
        val shown = lineText.take(200)
        val caretCol = column.coerceAtMost(shown.length)
        return listOf(
            head,
            "  $num | $shown",
            "  " + " ".repeat(num.length) + " | " + " ".repeat(caretCol) + "^" + (caretNote?.let { " $it" } ?: ""),
        )
    }
}

/**
 * Grammar:
 *   script    := item (sep item)*
 *   sep       := ';' | newline | '&&' | '||'
 *   item      := command | 'for' NAME 'in' words sep 'do' list sep 'done'
 *
 * Comments (`# ...`), blank lines and trailing `\` continuations are ignored.
 * A newline directly after `&&` or `||` continues the statement on the next line.
 * Quotes may span lines, which is how multi-line file contents are written.
 *
 * Heredoc: `create f <<EOF` / `update f <<'EOF'` followed by lines up to a line holding only `EOF`.
 * The body is byte-exact (no escapes, no variable expansion, whichever way the delimiter is quoted) and ends
 * with a line break, like POSIX. `<<-EOF` additionally strips leading tabs from body and terminator.
 *
 * Quote rules the scanner must share with Lexer: inside '...' and "..." a backslash pairs with the next
 * character; outside quotes only \' and \" (and \\) are pairs, which is what makes 'it'\''s' one word.
 */
object ScriptParser {

    private sealed interface Tok
    private data class Seg(val raw: String, val line: Int, val heredoc: String? = null) : Tok
    private data class Op(val c: Connector, val line: Int) : Tok

    fun parse(text: String): List<Item> {
        val toks = scan(text).toMutableList()
        val p = Cursor(toks)
        return parseList(p, inLoop = false, openLine = 1)
    }

    private class Cursor(val toks: MutableList<Tok>) { var pos = 0 }

    // ───────────────────────── scanning ─────────────────────────

    private class Heredoc(val delim: String, val stripTabs: Boolean, val line: Int)

    private fun lineTextAt(src: String, idx: Int): String {
        val st = src.lastIndexOf('\n', idx - 1) + 1
        val e = src.indexOf('\n', idx).let { if (it < 0) src.length else it }
        return src.substring(st, e)
    }

    private fun columnAt(src: String, idx: Int): Int = idx - (src.lastIndexOf('\n', idx - 1) + 1)

    private fun scan(text: String): List<Tok> {
        val src = text.replace("\r\n", "\n").replace('\r', '\n')
        val n = src.length
        val toks = ArrayList<Tok>()
        val sb = StringBuilder()
        var line = 1
        var segLine = 1
        var hasContent = false
        var quote: Char? = null
        var quoteLine = 1
        var quoteIdx = 0
        var wordStart = true
        var pending: Heredoc? = null
        var i = 0

        fun endSeg(heredoc: String? = null) {
            val s = sb.toString().trim()
            if (s.isNotEmpty()) toks += Seg(s, segLine, heredoc)
            sb.setLength(0)
            hasContent = false
        }
        fun noHeredocHere(what: String) {
            if (pending != null)
                throw ScriptSyntaxException("a heredoc (<<${pending!!.delim}) must be the last thing on its line; found '$what' after it", line)
        }
        fun mark() { if (!hasContent) { segLine = line; hasContent = true } }
        fun addOp(c: Connector) {
            val last = toks.lastOrNull()
            when {
                c == Connector.SEQ && (last == null || last is Op) -> {}       // ignore ';;', leading ';'
                last == null || last is Op ->
                    throw ScriptSyntaxException("unexpected '${if (c == Connector.AND) "&&" else "||"}'", line)
                else -> toks += Op(c, line)
            }
        }

        while (i < n) {
            val c = src[i]
            val q = quote
            if (q != null) {
                sb.append(c)
                when {
                    c == '\\' && i + 1 < n -> {
                        sb.append(src[i + 1]); if (src[i + 1] == '\n') line++
                        i += 2; continue
                    }
                    c == q -> quote = null
                    c == '\n' -> line++
                }
                i++; continue
            }
            when {
                c == '\n' && pending != null -> {
                    val h = pending!!
                    pending = null
                    var p = i + 1
                    val body = StringBuilder()
                    var consumed = 0
                    var found = false
                    while (p < n) {
                        val e = src.indexOf('\n', p).let { if (it < 0) n else it }
                        var text = src.substring(p, e)
                        if (h.stripTabs) text = text.trimStart('\t')
                        consumed++
                        if (text.trimEnd() == h.delim) { found = true; p = e; break }
                        body.append(text).append('\n')
                        p = e + 1
                    }
                    if (!found) throw ScriptSyntaxException(
                        "heredoc <<${h.delim} is never terminated: add a line containing only ${h.delim}", h.line)
                    endSeg(body.toString())
                    line += consumed
                    i = p
                    wordStart = true
                }
                c == '\n' -> {
                    endSeg()
                    val last = toks.lastOrNull()
                    if (!(last is Op && last.c != Connector.SEQ)) addOp(Connector.SEQ)
                    line++; i++; wordStart = true
                }
                c == ';' -> { noHeredocHere(";"); endSeg(); addOp(Connector.SEQ); i++; wordStart = true }
                c == '&' && i + 1 < n && src[i + 1] == '&' -> { noHeredocHere("&&"); endSeg(); addOp(Connector.AND); i += 2; wordStart = true }
                c == '|' && i + 1 < n && src[i + 1] == '|' -> { noHeredocHere("||"); endSeg(); addOp(Connector.OR); i += 2; wordStart = true }
                c == '<' && wordStart && src.startsWith("<<", i) && !src.startsWith("<<<", i) -> {
                    if (pending != null) throw ScriptSyntaxException("only one heredoc per command is supported", line)
                    var j = i + 2
                    val strip = j < n && src[j] == '-'
                    if (strip) j++
                    while (j < n && (src[j] == ' ' || src[j] == '\t')) j++
                    val open = if (j < n && (src[j] == '\'' || src[j] == '"')) src[j++] else null
                    val st = j
                    while (j < n && (src[j].isLetterOrDigit() || src[j] == '_' || src[j] == '.' || src[j] == '-')) j++
                    val delim = src.substring(st, j)
                    if (delim.isEmpty() || (open != null && (j >= n || src[j] != open)))
                        throw ScriptSyntaxException(
                            "malformed heredoc: write <<EOF or <<'EOF' (a word, optionally quoted) after the command",
                            line, lineTextAt(src, i), columnAt(src, i), "here")
                    if (open != null) j++
                    pending = Heredoc(delim, strip, line)
                    mark()
                    i = j
                    wordStart = false
                }
                // Unquoted \' \" \\ are pairs (see class comment), so 'it'\''s' stays one word.
                c == '\\' && i + 1 < n && (src[i + 1] == '\'' || src[i + 1] == '"' || src[i + 1] == '\\') -> {
                    mark(); sb.append(c).append(src[i + 1]); i += 2; wordStart = false
                }
                c == '#' && wordStart -> { while (i < n && src[i] != '\n') i++ }
                c == '\\' && i + 1 < n && src[i + 1] == '\n' -> { sb.append(' '); line++; i += 2; wordStart = true }
                c == '\'' || c == '"' -> { mark(); quote = c; quoteLine = line; quoteIdx = i; sb.append(c); i++; wordStart = false }
                else -> {
                    if (!c.isWhitespace()) mark()
                    sb.append(c)
                    wordStart = c.isWhitespace()
                    i++
                }
            }
        }
        if (quote != null) throw ScriptSyntaxException(
            "unterminated quote", quoteLine, lineTextAt(src, quoteIdx), columnAt(src, quoteIdx), "opened here, never closed")
        pending?.let { throw ScriptSyntaxException(
            "heredoc <<${it.delim} is never terminated: add a line containing only ${it.delim}", it.line) }
        endSeg()
        val last = toks.lastOrNull()
        if (last is Op && last.c != Connector.SEQ)
            throw ScriptSyntaxException("command expected after '${if (last.c == Connector.AND) "&&" else "||"}'", last.line)
        return toks
    }

    // ───────────────────────── parsing ─────────────────────────

    private val forHeader = Regex("^for\\s+([A-Za-z_][A-Za-z0-9_]*)(?:\\s+in(?:\\s+(.*))?)?$", RegexOption.DOT_MATCHES_ALL)

    private fun firstWord(raw: String) = raw.takeWhile { !it.isWhitespace() }

    private fun parseList(p: Cursor, inLoop: Boolean, openLine: Int): List<Item> {
        val items = ArrayList<Item>()
        while (true) {
            while (p.pos < p.toks.size && p.toks[p.pos] is Op) {
                val op = p.toks[p.pos] as Op
                if (op.c != Connector.SEQ) throw ScriptSyntaxException("unexpected operator", op.line)
                p.pos++
            }
            if (p.pos >= p.toks.size) {
                if (inLoop) throw ScriptSyntaxException("'for' has no matching 'done'", openLine)
                return items
            }
            val seg = p.toks[p.pos] as Seg
            val node: Node = when (firstWord(seg.raw)) {
                "done" -> {
                    if (!inLoop) throw ScriptSyntaxException("unexpected 'done'", seg.line)
                    if (seg.raw.trim() != "done") throw ScriptSyntaxException("unexpected text after 'done'", seg.line)
                    p.pos++
                    return items
                }
                "do" -> throw ScriptSyntaxException("unexpected 'do'", seg.line)
                "for" -> parseFor(p, seg)
                else -> { p.pos++; CmdNode(seg.raw, seg.line, seg.heredoc) }
            }
            val next = if (p.pos < p.toks.size && p.toks[p.pos] is Op) {
                val c = (p.toks[p.pos] as Op).c; p.pos++; c
            } else null
            items += Item(node, next)
        }
    }

    private fun parseFor(p: Cursor, seg: Seg): ForNode {
        val m = forHeader.find(seg.raw)
            ?: throw ScriptSyntaxException("usage: for NAME in item...; do ...; done", seg.line)
        p.pos++
        while (p.pos < p.toks.size && p.toks[p.pos] is Op) {
            val op = p.toks[p.pos] as Op
            if (op.c != Connector.SEQ) throw ScriptSyntaxException("unexpected operator", op.line)
            p.pos++
        }
        val doSeg = p.toks.getOrNull(p.pos) as? Seg
        if (doSeg == null || firstWord(doSeg.raw) != "do")
            throw ScriptSyntaxException("expected 'do' after 'for ${m.groupValues[1]} ...'", seg.line)
        val rest = doSeg.raw.removePrefix("do").trim()
        if (rest.isEmpty()) p.pos++ else p.toks[p.pos] = Seg(rest, doSeg.line)
        val body = parseList(p, inLoop = true, openLine = seg.line)
        return ForNode(m.groupValues[1], m.groupValues[2], body, seg.line)
    }
}
