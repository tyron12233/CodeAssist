package com.zipcli.ke.domain

/**
 * One argument produced by [Lexer].
 *
 * @param glob true when the word contained an *unquoted* `*` or `?` literally typed by the user
 *             (characters that came out of a variable expansion never count).
 */
data class Word(val text: String, val glob: Boolean = false)

/** Returns the value of a variable, or null when it is not defined (the `$name` text is then kept as-is). */
typealias VarLookup = (String) -> String?

/**
 * Splits one command (a single statement, see ScriptParser) into words.
 *
 * Rules (a superset of the original tokenizer, so existing commands keep working):
 *  - Whitespace separates words; '...' and "..." group text.
 *  - Single quotes are LITERAL (POSIX): nothing is expanded and a backslash is just a backslash, with one
 *    exception: a backslash before the quote itself (\') produces a quote, and the pair \\ is kept as two
 *    backslashes so that the scanner and the lexer always agree where the string ends.
 *    The POSIX idiom 'it'\''s' works too (an unquoted \' is a plain quote).
 *  - Inside double quotes \\ \" \' \n \t are escapes and variables expand.
 *  - `#` at the start of a word begins a comment.
 *  - `$NAME`, `${NAME}`, `${NAME:-default}`, `$1`..`$9`, `$?`, `$#` are expanded outside single quotes.
 *    A variable that is NOT defined is left untouched. That keeps regex anchors ("a$") and
 *    regex replacement groups ("$1") working in interactive use.
 *  - `\$` produces a literal `$` outside single quotes, but only when it precedes something that would
 *    otherwise expand (so a regex such as "price\$" keeps its backslash). Inside single quotes it stays `\$`.
 *  - For text that must be byte-exact (Windows paths, JSON) use a heredoc: update f <<'EOF' ... EOF.
 */
object Lexer {

    private val NO_VARS: VarLookup = { null }

    /** Shared wording for constructs this shell deliberately does not implement. */
    private const val NO_SUBSTITUTION =
        "Arithmetic expansion and command substitution are not supported. " +
            "Use 'set NAME=value' plus a variable reference, or quote the text to keep it literal."

    private fun noMetachar(c: Char): String =
        "'$c' on its own is not supported: ZipCLI has no pipes, no redirection, no command substitution and " +
            "no arithmetic. Run commands as separate lines; use 'for' to loop and 'update --append' to add to a file. " +
            "Quote it if it is literal text (e.g. echo 'a $c b'). See 'help scripting'."

    fun words(raw: String, vars: VarLookup = NO_VARS): List<Word> {
        val out = ArrayList<Word>()
        val sb = StringBuilder()
        var started = false
        var glob = false
        var quote: Char? = null
        var i = 0
        val n = raw.length

        fun flush() {
            if (started) {
                out += Word(sb.toString(), glob)
                sb.setLength(0)
                started = false
                glob = false
            }
        }

        while (i < n) {
            val c = raw[i]
            val q = quote
            if (q != null) {
                if (q == '\'') {
                    // POSIX single quotes: literal, except \' (a quote). \\ is consumed as a pair so the
                    // script scanner and this lexer agree on where the string ends.
                    when {
                        c == '\'' -> { quote = null; i++ }
                        c == '\\' && i + 1 < n && raw[i + 1] == '\'' -> { sb.append('\''); i += 2 }
                        c == '\\' && i + 1 < n && raw[i + 1] == '\\' -> { sb.append("\\\\"); i += 2 }
                        else -> { sb.append(c); i++ }
                    }
                } else {
                    when {
                        c == q -> { quote = null; i++ }
                        c == '\\' && i + 1 < n -> {
                            val nx = raw[i + 1]
                            when {
                                nx == '\\' || nx == '\'' || nx == '"' -> { sb.append(nx); i += 2 }
                                nx == 'n' -> { sb.append('\n'); i += 2 }
                                nx == 't' -> { sb.append('\t'); i += 2 }
                                nx == '$' && startsVar(raw, i + 2) -> { sb.append('$'); i += 2 }
                                else -> { sb.append(c); i++ }
                            }
                        }
                        c == '$' -> i = expandVar(raw, i, vars, sb)
                        else -> { sb.append(c); i++ }
                    }
                }
            } else {
                when {
                    c == '\'' || c == '"' -> { quote = c; started = true; i++ }
                    c == '#' && !started -> i = n
                    c.isWhitespace() -> { flush(); i++ }
                    c == '\\' && i + 1 < n && raw[i + 1] == '$' && startsVar(raw, i + 2) -> {
                        sb.append('$'); started = true; i += 2
                    }
                    // Unquoted \' and \" are plain quote characters (this is what makes 'it'\''s' work);
                    // \\ is kept as two backslashes, consumed as a pair like the script scanner does.
                    c == '\\' && i + 1 < n && (raw[i + 1] == '\'' || raw[i + 1] == '"') -> {
                        sb.append(raw[i + 1]); started = true; i += 2
                    }
                    c == '\\' && i + 1 < n && raw[i + 1] == '\\' -> {
                        sb.append("\\\\"); started = true; i += 2
                    }
                    c == '$' -> {
                        val before = sb.length
                        i = expandVar(raw, i, vars, sb)
                        if (sb.length > before) started = true
                    }
                    c == '`' -> throw CommandParseException(NO_SUBSTITUTION)
                    // '>' '<' '|' '&' standing alone mean redirection / pipe / background,
                    // which this shell does not support. Glued to other text they stay literal,
                    // so regexes such as a|b keep working.
                    (c == '>' || c == '<' || c == '|' || c == '&') && !started ->
                        throw CommandParseException(noMetachar(c))
                    else -> {
                        if (c == '*' || c == '?') glob = true
                        sb.append(c); started = true; i++
                    }
                }
            }
        }
        if (quote != null) throw CommandParseException("Unterminated quote")
        flush()
        return out
    }

    private fun startsVar(s: String, idx: Int): Boolean {
        if (idx >= s.length) return false
        val c = s[idx]
        return c.isLetterOrDigit() || c == '_' || c == '{' || c == '?' || c == '#'
    }

    private fun isNameStart(c: Char) = c.isLetter() || c == '_'
    private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '_'

    /** Handles the `$` at [i]. Appends the expansion (or the literal `$`) and returns the next index. */
    private fun expandVar(raw: String, i: Int, vars: VarLookup, sb: StringBuilder): Int {
        val n = raw.length
        if (i + 1 >= n) { sb.append('$'); return i + 1 }
        val c = raw[i + 1]

        if (c == '{') {
            val close = raw.indexOf('}', i + 2)
            if (close < 0) { sb.append('$'); return i + 1 }
            val body = raw.substring(i + 2, close)
            val sep = body.indexOf(":-")
            val name = if (sep >= 0) body.substring(0, sep) else body
            val default = if (sep >= 0) body.substring(sep + 2) else null
            if (!isValidName(name)) { sb.append('$'); return i + 1 }
            val value = vars(name)
            when {
                value != null && (value.isNotEmpty() || default == null) -> sb.append(value)
                default != null -> sb.append(default)
                else -> { sb.append('$'); return i + 1 }   // undefined, no default: keep literal text
            }
            return close + 1
        }

        if (isNameStart(c)) {
            var j = i + 1
            while (j < n && isNameChar(raw[j])) j++
            val value = vars(raw.substring(i + 1, j))
            if (value == null) { sb.append('$'); return i + 1 }
            sb.append(value)
            return j
        }

        if (c.isDigit() || c == '?' || c == '#') {
            val value = vars(c.toString())
            if (value == null) { sb.append('$'); return i + 1 }
            sb.append(value)
            return i + 2
        }

        if (c == '(') throw CommandParseException(NO_SUBSTITUTION)

        sb.append('$')
        return i + 1
    }

    private fun isValidName(name: String): Boolean =
        name.isNotEmpty() && (
            name == "?" || name == "#" ||
            (name.all { it.isDigit() } && name.length == 1) ||
            (isNameStart(name[0]) && name.all { isNameChar(it) })
        )
}
