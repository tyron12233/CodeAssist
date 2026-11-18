package dev.codeassist.csharp.completion

import dev.codeassist.csharp.project.CsMainScan

/**
 * Finds names already used in a file for completion.
 *
 * This is text-based and does not understand scope, so a name from another method can also be offered.
 * Comments, strings, and preprocessor lines are masked by [CsMainScan.mask].
 */
internal object CsWords {

    /** A word found in the file, marked when it looks like a declaration. */
    class Word(val name: String, val declared: Boolean)

    private val wordPattern = Regex("""[A-Za-z_]\w*""")

    /**
     * Matches a type followed by a variable name and a declaration-like token.
     * Covers locals, fields, parameters, and foreach variables.
     *
     * This is heuristic: expressions such as `return x;` can also match.
     */
    private val declarationPattern = Regex(
        """(?<![\w.@])(?!(?:return|else|throw|new|case|goto|in|is|as|yield|await)\b)""" +
            """[A-Za-z_]\w*(?:<[^;=(){}]*>)?(?:\[[,\s]*]\??)*\??\s+([A-Za-z_]\w*)\s*(?=[=;,)]|\bin\b)"""
    )

    /**
     * Returns up to [limit] distinct accepted words, nearest to [caret] first.
     * Excludes keywords, member names after dots, and the word containing the caret.
     */
    fun nearby(
        text: String,
        caret: Int,
        accept: (String) -> Boolean,
        limit: Int = 30,
    ): List<Word> {
        val clean = CsMainScan.mask(text)
        val declared = declarationPattern.findAll(clean)
            .map { it.groupValues[1] }
            .toSet()

        val distance = HashMap<String, Int>()

        for (match in wordPattern.findAll(clean)) {
            val start = match.range.first
            val end = match.range.last + 1

            if (caret in start..end) continue
            if (start > 0 && (clean[start - 1] == '.' || clean[start - 1].isDigit())) continue

            val word = match.value
            if (word in CsKeywords.ALL || !accept(word)) continue

            val away = if (caret < start) start - caret else caret - end
            distance.merge(word, away, ::minOf)
        }

        return distance.entries
            .sortedBy { it.value }
            .take(limit)
            .map { Word(it.key, it.key in declared) }
    }
}
