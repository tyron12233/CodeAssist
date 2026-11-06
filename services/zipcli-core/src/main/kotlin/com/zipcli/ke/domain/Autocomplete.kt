package com.zipcli.ke.domain

import com.zipcli.ke.fs.FileEntry
import com.zipcli.ke.fs.TerminalFileSystem
import com.zipcli.ke.fs.VirtualPath
import kotlinx.coroutines.CancellationException

/**
 * @param newText  the whole input line after completion, or null when nothing can be inserted
 * @param suggestions what to show when several candidates remain
 */
data class Completion(val newText: String?, val suggestions: List<String> = emptyList())

/**
 * Tab completion. Works on the LAST statement of the line (after `;`, `&&`, `||`, newline),
 * understands quotes, and completes command names, flags, `$variables`, sub-commands and paths.
 * Several matches complete to their longest common prefix, like a real shell.
 */
class Autocomplete(
    private val registry: CommandRegistry,
    private val fs: TerminalFileSystem,
) {
    private val dirOnlyCommands = setOf("cd")
    private val builtinVars = listOf("PWD", "CWD", "HOME", "OLDPWD")

    suspend fun complete(input: String, cwd: String, varNames: Collection<String> = emptyList()): Completion {
        val split = lastBoundary(input)
        var head = input.substring(0, split)
        val seg = input.substring(split)
        val lead = seg.takeWhile { it.isWhitespace() }
        head += lead
        val body = seg.substring(lead.length)

        val t = tokenize(body)
        val cur = t.values.lastOrNull() ?: ""
        val prefixText = head + body.substring(0, t.lastStart)

        // 1. command name
        if (t.values.size <= 1) {
            if (cur.isEmpty()) return Completion(null)
            val names = (registry.names() + listOf("for")).filter { it.startsWith(cur, ignoreCase = true) }.sorted()
            return finish(prefixText, cur, names, trailingSpaceWhenUnique = true)
        }

        val spec = registry.find(t.values[0])

        // 2. flags
        if (t.lastQuote == null && cur.startsWith("-") && spec != null) {
            val longs = spec.flags.map { "--${it.long}" }
            val matches = if (cur == "-" || cur == "--") longs else longs.filter { it.startsWith(cur, ignoreCase = true) }
            return finish(prefixText, cur, matches.sorted(), trailingSpaceWhenUnique = true)
        }

        // 3. variables
        if (cur.startsWith("$") && !cur.startsWith("\${")) {
            val partial = cur.substring(1)
            val names = (varNames + builtinVars).distinct().filter { it.startsWith(partial, ignoreCase = true) }.sorted()
            return finish(prefixText, cur, names.map { "$$it" }, trailingSpaceWhenUnique = false)
        }

        // 4. fixed sub-commands
        when (t.values[0].lowercase()) {
            "saf" -> if (t.values.size == 2)
                return finish(prefixText, cur, listOf("grant", "list", "revoke").filter { it.startsWith(cur, true) }, true)
            "help", "?" -> if (t.values.size == 2)
                return finish(prefixText, cur,
                    (registry.names() + "scripting").filter { it.startsWith(cur, true) }.sorted(), true)
        }

        // 5. paths (but never inside the <pattern>/<new text> of find and replace)
        if (spec != null && !isPathPosition(spec, t.values)) return Completion(null)
        return completePath(prefixText, cur, t.lastQuote, cwd, dirOnly = t.values[0].lowercase() in dirOnlyCommands)
    }

    // ───────────────────────── helpers ─────────────────────────

    /**
     * `find <pattern> [path]` and `replace <path> <old> <new>` mix text and paths, so a word typed in a
     * pattern position must not be "completed" into a file name (that would silently rewrite the pattern).
     * Only the path position, or the value of --old-file/--new-file, completes. Other commands always do.
     */
    private fun isPathPosition(spec: CommandSpec, values: List<String>): Boolean {
        if (spec.name != "find" && spec.name != "replace") return true
        var positional = 0
        var awaiting: FlagSpec? = null
        for (k in 1 until values.size - 1) {
            val tok = values[k]
            if (awaiting != null) { awaiting = null; continue }       // this token is a flag's value
            when {
                tok.startsWith("--") && tok.length > 2 ->
                    if ('=' !in tok) awaiting = spec.flags.firstOrNull { it.long == tok.substring(2) && it.takesValue }
                tok.length == 2 && tok[0] == '-' -> awaiting = spec.flags.firstOrNull { it.short == tok[1] && it.takesValue }
                tok.length > 2 && tok[0] == '-' -> {}                  // bundled short flags such as -ri
                else -> positional++
            }
        }
        awaiting?.let { return it.long == "old-file" || it.long == "new-file" }
        return if (spec.name == "replace") positional == 0 else positional == 1
    }

    private fun finish(prefixText: String, typed: String, matches: List<String>, trailingSpaceWhenUnique: Boolean): Completion {
        if (matches.isEmpty()) return Completion(null)
        if (matches.size == 1) {
            return Completion(prefixText + matches[0] + if (trailingSpaceWhenUnique) " " else "")
        }
        val lcp = Suggest.commonPrefix(matches)
        val newText = if (lcp.length > typed.length) prefixText + lcp else null
        return Completion(newText, matches)
    }

    private suspend fun completePath(prefixText: String, cur: String, quote: Char?, cwd: String, dirOnly: Boolean): Completion {
        val slash = cur.lastIndexOf('/')
        val dirPrefix = if (slash >= 0) cur.substring(0, slash + 1) else ""
        val partial = cur.substring(slash + 1)
        val dir = if (dirPrefix.isEmpty()) cwd else VirtualPath.resolve(cwd, dirPrefix)

        val entries: List<FileEntry> = try { fs.list(dir) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { return Completion(null) }

        val matches = entries
            .filter { it.name.startsWith(partial, ignoreCase = true) }
            .filter { !it.name.startsWith(".") || partial.startsWith(".") }
            .filter { !dirOnly || it.isDirectory }
            .sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenComparator { a, b -> NaturalOrder.compare(a.name, b.name) })
        if (matches.isEmpty()) return Completion(null)

        if (matches.size == 1) {
            val m = matches[0]
            val value = dirPrefix + m.name + if (m.isDirectory) "/" else ""
            return Completion(prefixText + render(value, quote, closeQuote = !m.isDirectory) + if (!m.isDirectory) " " else "")
        }

        val lcp = Suggest.commonPrefix(matches.map { it.name })
        val shown = matches.take(40).map { if (it.isDirectory) it.name + "/" else it.name } +
            if (matches.size > 40) listOf("… +${matches.size - 40} more") else emptyList()
        val newText = if (lcp.length > partial.length) prefixText + render(dirPrefix + lcp, quote, closeQuote = false) else null
        return Completion(newText, shown)
    }

    /** Wraps [value] in quotes when it contains characters that would otherwise split or end the word. */
    private fun render(value: String, quote: Char?, closeQuote: Boolean): String {
        val special = value.any { it.isWhitespace() || it in ";&|#'\"" }
        if (quote == null && !special) return value
        val q = quote ?: '"'
        val escaped = value.replace("\\", "\\\\").replace(q.toString(), "\\$q")
        return q + escaped + if (closeQuote) q.toString() else ""
    }

    private class Tokens(val values: List<String>, val lastStart: Int, val lastQuote: Char?)

    /** Splits the last statement into words; the last entry is the word being completed (maybe empty). */
    private fun tokenize(body: String): Tokens {
        val values = ArrayList<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        var started = false
        var start = 0
        var i = 0
        while (i < body.length) {
            val c = body[i]
            val q = quote
            if (q != null) {
                when {
                    c == q -> quote = null
                    c == '\\' && i + 1 < body.length && body[i + 1] in "\\'\"" -> { sb.append(body[i + 1]); i++ }
                    else -> sb.append(c)
                }
            } else when {
                c == '\'' || c == '"' -> { if (!started) { started = true; start = i }; quote = c }
                c.isWhitespace() -> if (started) { values += sb.toString(); sb.setLength(0); started = false }
                else -> { if (!started) { started = true; start = i }; sb.append(c) }
            }
            i++
        }
        return if (started) {
            Tokens(values + sb.toString(), start, quote)
        } else {
            Tokens(values + "", body.length, null)
        }
    }

    /** Index just after the last `;`, newline, `&&` or `||` that is outside quotes. */
    private fun lastBoundary(s: String): Int {
        var quote: Char? = null
        var last = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val q = quote
            if (q != null) {
                if (c == '\\' && i + 1 < s.length) i++ else if (c == q) quote = null
            } else when {
                c == '\'' || c == '"' -> quote = c
                c == ';' || c == '\n' -> last = i + 1
                (c == '&' || c == '|') && i + 1 < s.length && s[i + 1] == c -> { last = i + 2; i++ }
            }
            i++
        }
        return last
    }
}
