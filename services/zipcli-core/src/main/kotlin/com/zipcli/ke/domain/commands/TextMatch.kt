package com.zipcli.ke.domain.commands

/**
 * Matching and replacing text that spans lines and indentation levels, shared by `find` and `replace`.
 *
 * Guarantees:
 *  - Line endings: a typed "\n" matches both LF and CRLF files; inserted line breaks use the file's own style.
 *    Lines that are not touched stay byte-for-byte identical (no whole-file normalisation).
 *    This holds for literal patterns, [flexIndent] patterns and regex patterns alike (see [translateRegex]).
 *  - Regex mode: ^ and $ match at every line (MULTILINE); [dotAll] lets . cross lines; `-w` wraps the
 *    pattern in \b…\b; the replacement understands $1, ${name}, \n, \t and \$.
 *  - Flexible indentation ([flexIndent], multi-line patterns only): each pattern line matches regardless of
 *    its leading whitespace and trailing blanks, so tabs/spaces/depth differences don't matter. The
 *    replacement is re-based onto the indentation found in the file, keeping its own relative nesting and
 *    converting between tabs and spaces (and between 2/4/8-space indent widths) to the file's style.
 *    A pattern that ends with a line break matches whole lines and leaves the next line untouched.
 *  - Only "\n" starts a line for literal and flex patterns (U+2028, U+0085 or a lone CR inside a line
 *    never create a false line start).
 */
internal class BlockMatcher(
    val regex: Regex,
    private val flexIndent: Boolean,
    private val patternIndents: List<String>,
    private val patternBlank: List<Boolean>,
    private val regexMode: Boolean,
    /** True when one match can cover several lines, so `find` has to scan whole files instead of line by line. */
    val spansLines: Boolean,
    /** `--reindent` with a one-line pattern: extra lines of the replacement are indented like the matched line. */
    private val lineIndent: Boolean = false,
) {
    /** Result of [translateRegex]. */
    internal class Translated(val pattern: String, val spansLines: Boolean)

    companion object {
        /** One line break in a file, whichever style it uses. */
        private const val NL = "(?:\\r?\\n)"

        /** Start of a line: start of text (also just after a UTF-8 BOM) or after an LF — but not after the final LF. */
        private const val LINE_START = "(?:\\A|(?<=\\n)(?!\\z)|(?<=\\A\\uFEFF))"

        /** End of a line: before an LF/CRLF, or at the end of a text that does not end with an LF. */
        private const val LINE_END = "(?=\\r\\n|(?<!\\r)\\n|(?<!\\n)\\z)"

        fun build(
            old: String,
            regexMode: Boolean,
            ignoreCase: Boolean,
            word: Boolean,
            flexIndent: Boolean,
            dotAll: Boolean,
        ): BlockMatcher {
            val src = old.replace("\r\n", "\n")
            val opts = HashSet<RegexOption>()
            if (ignoreCase) opts += RegexOption.IGNORE_CASE
            if (regexMode) {
                // Not UNIX_LINES on purpose: the default '.' and '$' stop before the CR of a CRLF.
                opts += RegexOption.MULTILINE
                if (dotAll) opts += RegexOption.DOT_MATCHES_ALL
                val t = translateRegex(src)
                val p = if (word) "\\b(?:${t.pattern})\\b" else t.pattern
                return BlockMatcher(Regex(p, opts), false, emptyList(), emptyList(), true, t.spansLines || dotAll)
            }
            opts += RegexOption.UNIX_LINES
            if (flexIndent && src.contains('\n')) {
                opts += RegexOption.MULTILINE
                val lines = src.split('\n')
                // "a\nb\n": the pattern ends with a line break, so it matches whole lines. Treating the empty
                // text after the last '\n' as one more line would swallow the next line's indentation.
                val trailingBreak = lines.last().isEmpty()
                val core = if (trailingBreak) lines.dropLast(1) else lines
                val sb = StringBuilder("(?:^|(?<=\\A\\uFEFF))")      // line start, also right after a BOM
                core.forEachIndexed { i, l ->
                    val last = i == core.lastIndex && !trailingBreak
                    sb.append("([ \\t]*)")
                    sb.append(Regex.escape(if (last) l.trimStart(' ', '\t') else l.trim(' ', '\t')))
                    if (!last) sb.append("[ \\t]*\\r?\\n")
                }
                if (word && !trailingBreak && isWordChar(core.last().trimEnd().lastOrNull())) sb.append("\\b")
                return BlockMatcher(Regex(sb.toString(), opts), true,
                    core.map { l -> l.takeWhile { it == ' ' || it == '\t' } },
                    core.map { it.isBlank() }, false, true)
            }
            var p = src.split('\n').joinToString("\\r?\\n") { Regex.escape(it) }
            if (word) {
                if (isWordChar(src.firstOrNull())) p = "\\b$p"
                if (isWordChar(src.lastOrNull())) p = "$p\\b"
            }
            return BlockMatcher(Regex(p, opts), false,
                if (flexIndent) listOf(src.takeWhile { it == ' ' || it == '\t' }) else emptyList(),
                emptyList(), false, src.contains('\n'), lineIndent = flexIndent)
        }

        private fun isWordChar(c: Char?) = c != null && (c.isLetterOrDigit() || c == '_')

        /**
         * Makes a user-typed regex line-ending agnostic: a literal line break, `\n`, or a `\n` inside a
         * `[...]` class matches LF and CRLF files alike, and `^` / `$` mean exactly "start / end of a line"
         * (Java's own MULTILINE anchors also fire after U+2028 or a lone CR, match once more after the final
         * line break, and miss line 1 of a file that starts with a BOM). Escaped backslashes (`\\n`), `\Q…\E`
         * literals and every other construct pass through untouched. Also reports whether the regex can match a line
         * break at all (literal/`\n`/`\r`/`\R`/`(?s)`), which `find` uses to decide between scanning line by
         * line and scanning whole files.
         */
        fun translateRegex(src: String): Translated {
            val sb = StringBuilder(src.length + 16)
            var spans = false
            val classes = ArrayList<Boolean>()      // one entry per open [...]; true = negated
            var i = 0
            val n = src.length

            // A line break inside [...] stands for the CR of CRLF as well: [^\n]* must not eat it,
            // and [ \t\n]+ has to cross a CRLF.
            fun classBreak(escaped: Boolean): String = if (escaped) "\\n\\r" else "\n\r"

            while (i < n) {
                val c = src[i]
                val inClass = classes.isNotEmpty()
                when {
                    c == '\\' && i + 1 < n -> {
                        val d = src[i + 1]
                        when {
                            d == 'Q' && !inClass -> {
                                val end = src.indexOf("\\E", i + 2)
                                val body = if (end < 0) src.substring(i + 2) else src.substring(i + 2, end)
                                var from = 0
                                while (from < body.length) {
                                    val lf = body.indexOf('\n', from)
                                    val stop = if (lf < 0) body.length else lf
                                    if (stop > from) sb.append(Regex.escape(body.substring(from, stop)))
                                    if (lf < 0) break
                                    sb.append(NL); spans = true
                                    from = lf + 1
                                }
                                i = if (end < 0) n else end + 2
                            }
                            d == 'n' -> {
                                sb.append(if (inClass) classBreak(true) else NL)
                                spans = true; i += 2
                            }
                            d == 'r' || d == 'R' -> { spans = true; sb.append(c).append(d); i += 2 }
                            else -> { sb.append(c).append(d); i += 2 }     // includes \\ (escaped backslash)
                        }
                    }
                    c == '\n' -> {
                        sb.append(if (inClass) classBreak(false) else NL)
                        spans = true; i++
                    }
                    c == '^' && !inClass -> { sb.append(LINE_START); i++ }
                    c == '$' && !inClass -> { sb.append(LINE_END); i++ }
                    c == '[' -> {
                        sb.append(c); i++
                        val neg = i < n && src[i] == '^'
                        if (neg) { sb.append('^'); i++ }
                        classes += neg
                    }
                    c == ']' && inClass -> { sb.append(c); classes.removeAt(classes.lastIndex); i++ }
                    c == '(' && !inClass && i + 1 < n && src[i + 1] == '?' -> {
                        var j = i + 2
                        while (j < n && (src[j].isLetter() || src[j] == '-')) j++
                        if (j < n && (src[j] == ':' || src[j] == ')') && src.substring(i + 2, j).substringBefore('-').contains('s')) spans = true
                        sb.append(c); i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
            return Translated(sb.toString(), spans)
        }

        /**
         * Smart case for `find`: a pattern without capitals is case-insensitive. In regex mode the letters of
         * escapes (`\S`, `\W`, `\p{Lu}`, `(?<Name>…)`) are syntax, not capitals, so they don't count.
         */
        fun smartCaseIgnore(pattern: String, regexMode: Boolean): Boolean {
            if (!regexMode) return pattern.none { it.isUpperCase() }
            // Hand-written scan instead of helper regexes: no pattern here can fail to compile on any platform
            // (an earlier version used an unescaped '}' that Android's regex engine rejects, which made every
            // `find --regex` fail with a misleading "Syntax error in regexp pattern").
            val n = pattern.length
            var i = 0
            while (i < n) {
                val c = pattern[i]
                when {
                    c == '\\' && i + 1 < n -> {
                        val d = pattern[i + 1]
                        i += 2
                        if (d == 'p' || d == 'P') {                                  // \p{Lu}  \pL
                            if (i < n && pattern[i] == '{') {
                                val close = pattern.indexOf('}', i)
                                i = if (close < 0) n else close + 1
                            } else if (i < n && pattern[i].isLetter()) i++
                        } else if (d == 'k' && i < n && pattern[i] == '<') {          // \k<name>
                            val close = pattern.indexOf('>', i)
                            i = if (close < 0) n else close + 1
                        }
                        // any other escape (\S, \W, \Q, \., \\) is syntax, not a capital letter
                    }
                    c == '(' && i + 3 < n && pattern[i + 1] == '?' && pattern[i + 2] == '<' && pattern[i + 3].isLetter() -> {
                        val close = pattern.indexOf('>', i + 3)                       // (?<Name>...) group name
                        i = if (close < 0) n else close + 1
                    }
                    c.isUpperCase() -> return false
                    else -> i++
                }
            }
            return true
        }

        fun dominantEol(text: String): String {
            var crlf = 0; var lf = 0
            var i = text.indexOf('\n')
            while (i >= 0) {
                if (i > 0 && text[i - 1] == '\r') crlf++ else lf++
                i = text.indexOf('\n', i + 1)
            }
            return if (crlf > lf) "\r\n" else "\n"
        }

        /** Most common indentation step (2, 4, 8, …) among the space-indented lines of [text], or null. */
        fun detectIndentUnit(text: String): Int? {
            val counts = IntArray(9)
            var prev = -1
            for (line in text.lineSequence()) {
                if (line.isBlank()) continue
                val ind = line.takeWhile { it == ' ' }.length
                if (ind < line.length && line[ind] == '\t') continue
                // The first line has no predecessor, so its indentation says nothing about the step width.
                if (prev >= 0 && ind - prev in 1..8) counts[ind - prev]++
                prev = ind
            }
            var best = 0
            for (d in 1..8) if (counts[d] > counts[best]) best = d
            return if (counts[best] < 2) null else best      // not enough evidence: don't guess
        }
    }

    // cache for detectIndentUnit (one file at a time)
    private var unitText: String? = null
    private var unitValue: Int? = null
    private fun globalUnit(text: String): Int? {
        if (unitText !== text) { unitText = text; unitValue = detectIndentUnit(text) }
        return unitValue
    }

    fun count(text: String): Int = regex.findAll(text).count()

    /** Returns the new text and how many matches were replaced. */
    fun replaceAll(text: String, new: String, eol: String = dominantEol(text)): Pair<String, Int> {
        var n = 0
        val out = regex.replace(text) { m -> n++; replacementFor(m, new, eol, text) }
        return out to n
    }

    /** The replacement text for one match of [text] (also used for previews). */
    fun replacementFor(m: MatchResult, new: String, eol: String, text: String): String {
        val newNorm = new.replace("\r\n", "\n")
        return when {
            regexMode -> expandGroups(m, if (eol == "\n") newNorm else newNorm.replace("\n", eol), eol)
            flexIndent -> reindent(m, newNorm, eol, globalUnit(text))
            lineIndent && '\n' in newNorm -> indentAtMatchedLine(m, newNorm, eol, text)
            else -> if (eol == "\n") newNorm else newNorm.replace("\n", eol)
        }
    }

    // $1, ${name}, \$ — like Kotlin's Regex.replace, evaluated per match; plus \n (file's line break) and \t.
    private fun expandGroups(m: MatchResult, rep: String, eol: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < rep.length) {
            val c = rep[i]
            if (c == '\\' && i + 1 < rep.length) {
                when (val d = rep[i + 1]) {
                    'n' -> sb.append(eol)
                    't' -> sb.append('\t')
                    else -> sb.append(d)
                }
                i += 2; continue
            }
            if (c == '$' && i + 1 < rep.length) {
                val d = rep[i + 1]
                if (d.isDigit()) {
                    val g = d - '0'
                    if (g < m.groupValues.size) sb.append(m.groupValues[g])
                    else throw IllegalArgumentException("No group $g in pattern")
                    i += 2; continue
                }
                if (d == '{') {
                    val close = rep.indexOf('}', i + 2)
                    if (close > 0) {
                        val name = rep.substring(i + 2, close)
                        val v = (m.groups as? MatchNamedGroupCollection)?.get(name)?.value
                            ?: throw IllegalArgumentException("No group named '$name'")
                        sb.append(v); i = close + 1; continue
                    }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    private fun reindent(m: MatchResult, newNorm: String, eol: String, globalUnit: Int?): String {
        val captured = (1 until m.groupValues.size).map { m.groupValues[it] }
        // Indentation of the first non-blank pattern line is the reference point.
        val ref = patternBlank.indexOfFirst { !it }.let { if (it < 0) 0 else it }
        val pBase = patternIndents.getOrElse(ref) { "" }
        val mBase = captured.getOrElse(ref) { "" }

        // Indent width of the file: from this block if it shows one, checked against the whole file.
        val local = captured.map { it.length }.distinct().sorted().zipWithNext { a, c -> c - a }
            .filter { it > 0 }.minOrNull()
        val known = when {
            local == null -> globalUnit
            globalUnit == null -> local
            local % globalUnit == 0 -> globalUnit      // the block jumped several levels at once
            else -> local
        }
        return rebase(newNorm.split('\n'), eol, pBase, mBase, captured.any { '\t' in it }, known, patternIndents, false)
    }

    /** One-line pattern + `--reindent`: the replacement's later lines follow the indentation of the matched line. */
    private fun indentAtMatchedLine(m: MatchResult, newNorm: String, eol: String, text: String): String {
        val start = m.range.first
        val ls = text.lastIndexOf('\n', start - 1) + 1
        var k = ls
        while (k < text.length && (text[k] == ' ' || text[k] == '\t')) k++
        val mBase = text.substring(ls, k)
        return rebase(newNorm.split('\n'), eol, patternIndents.firstOrNull() ?: "", mBase,
            '\t' in mBase, globalUnit(text), patternIndents, true)
    }

    /**
     * Moves [lines] (typed relative to [pBase]) onto the file's base indentation [mBase], keeping their own
     * nesting and adapting tabs/spaces and, when the file's step width ([knownUnit]) is certain, 2/4/8-space steps.
     * [firstVerbatim]: the first line continues text that is already on the line, so it is left as typed.
     */
    private fun rebase(
        lines: List<String>, eol: String, pBase: String, mBase: String,
        useTabs: Boolean, knownUnit: Int?, patternLeads: List<String>, firstVerbatim: Boolean,
    ): String {
        // Indent width the user typed: relative to the reference line, spaces only.
        val rels = (lines.map { l -> l.takeWhile { it == ' ' } } + patternLeads.map { p -> p.takeWhile { it == ' ' } })
            .filter { it.startsWith(pBase) }.map { it.length - pBase.length }.filter { it > 0 }
        val typedUnit = rels.minOrNull()
        val scale = !useTabs && typedUnit != null && knownUnit != null && typedUnit != knownUnit &&
            rels.all { it % typedUnit == 0 }
        val spaceUnit = knownUnit ?: 4

        fun convert(rel: String): String = when {
            rel.isEmpty() -> rel
            useTabs && rel.all { it == ' ' } -> {
                val u = typedUnit ?: 4
                "\t".repeat(rel.length / u) + " ".repeat(rel.length % u)
            }
            !useTabs && '\t' in rel -> rel.replace("\t", " ".repeat(spaceUnit))
            scale && rel.all { it == ' ' } -> " ".repeat(rel.length / typedUnit!! * knownUnit!! + rel.length % typedUnit)
            else -> rel
        }

        return lines.mapIndexed { idx, l ->
            if (l.isBlank()) return@mapIndexed ""
            if (idx == 0 && firstVerbatim) return@mapIndexed l
            val lead = l.takeWhile { it == ' ' || it == '\t' }
            val body = l.substring(lead.length)
            val newLead = when {
                lead.startsWith(pBase) -> mBase + convert(lead.removePrefix(pBase))
                pBase.startsWith(lead) -> mBase.dropLast(minOf(pBase.length - lead.length, mBase.length))
                else -> lead
            }
            newLead + body
        }.joinToString(eol)
    }
}

/**
 * 1-based line number of a character index. Calls with increasing indexes (matches in order) cost O(n) in
 * total instead of O(n) each, which matters for thousands of hits in a multi-megabyte file.
 */
internal class LineCounter(private val text: String) {
    private var pos = 0
    private var line = 1

    fun lineAt(index: Int): Int {
        if (index < pos) { pos = 0; line = 1 }
        var k = pos
        while (k < index) { if (text[k] == '\n') line++; k++ }
        pos = index
        return line
    }
}
