package dev.ide.lang.kotlin.completion

/**
 * The name matcher IntelliJ's completion builds for a typed prefix: `CamelHumpMatcher` with middle matching on
 * (`"*" + prefix`) and the default "match case: first letter" setting, i.e. a `MinusculeMatcherImpl` in
 * `MatchingMode.FIRST_LETTER` with no hard separators. Ported from IntelliJ's `MinusculeMatcherImpl` (Apache 2.0),
 * keeping only the yes/no answer: the matched fragments it also returns only feed IntelliJ's highlighting.
 *
 * What it adds over a plain prefix: a match may start at any word of the name (`ba` finds `fooBar`, `S` finds
 * `IllegalStateException`), continue at later word starts (`fB` finds `fooBar`), and a fragment in the middle of a
 * word counts once the whole pattern fits in it (`ze` finds `size`). The first typed letter's case must be the
 * case of the name's first letter. [isStartMatch] tells a match anchored at the name's start from a middle one,
 * which IntelliJ's `PreferStartMatching` ranks first.
 */
internal class KotlinNameMatcher(prefix: String) {
    // The pattern as `CamelHumpMatcher.applyMiddleMatching` builds it: `*` + the prefix, `.` read as `. `.
    private val pattern: CharArray = ("*" + prefix.replace(".", ". ").trim()).toCharArray()
    private val isLowerCase = BooleanArray(pattern.size) { pattern[it].isLowerCase() }
    private val isUpperCase = BooleanArray(pattern.size) { pattern[it].isUpperCase() }
    private val isWordSeparator = BooleanArray(pattern.size) { isWordSeparator(pattern[it]) }
    private val toUpper = CharArray(pattern.size) { pattern[it].uppercaseChar() }
    private val toLower = CharArray(pattern.size) { pattern[it].lowercaseChar() }
    private val mixedCase: Boolean
    private val hasSeparators: Boolean
    private val hasDots: Boolean
    private val meaningful: CharArray
    private val empty = prefix.isEmpty()

    init {
        var seenNonWildcard = false
        var seenLower = false
        var seenUpperNotAfterWildcard = false
        var dots = false
        var separators = false
        val m = ArrayList<Char>()
        for (k in pattern.indices) {
            val c = pattern[k]
            if (isLowerCase[k]) seenLower = true
            if (c == '.') dots = true
            if (seenNonWildcard && isUpperCase[k]) seenUpperNotAfterWildcard = true
            if (!isWildcard(c)) {
                seenNonWildcard = true
                m += toLower[k]
                m += toUpper[k]
            }
            if (seenNonWildcard && isWordSeparator[k]) separators = true
        }
        mixedCase = seenLower && seenUpperNotAfterWildcard
        hasSeparators = separators
        hasDots = dots
        meaningful = m.toCharArray()
    }

    /** Whether [name] matches anywhere IntelliJ's completion would accept it. */
    fun matches(name: String): Boolean = empty || match(name) != NO_MATCH

    /** Whether the match of [name] begins at its first character (after leading underscores). */
    fun isStartMatch(name: String): Boolean {
        if (empty) return true
        val start = match(name)
        if (start == NO_MATCH) return false
        var i = 0
        while (i < name.length && name[i] == '_') i++
        return start <= i
    }

    /** The offset the match starts at, or [NO_MATCH]. */
    private fun match(name: String): Int {
        if (name.length < meaningful.size / 2) return NO_MATCH
        if (pattern.size > MAX_CAMEL_HUMP_MATCHING_LENGTH) return matchBySubstring(name)
        if (!containsMeaningfulInOrder(name)) return NO_MATCH
        return matchWildcards(name, 0, 0)
    }

    private fun containsMeaningfulInOrder(name: String): Boolean {
        var i = 0
        var n = 0
        while (i < meaningful.size) {
            val lo = meaningful[i]
            val up = meaningful[i + 1]
            while (n < name.length && name[n] != lo && name[n] != up) n++
            if (n >= name.length) return false
            n++
            i += 2
        }
        return true
    }

    private fun matchBySubstring(name: String): Int {
        val count = meaningful.size / 2
        for (i in 0..name.length - count) if (meaningfulCharsMatchAt(name, i) != 0) return i
        return NO_MATCH
    }

    private fun meaningfulCharsMatchAt(name: String, at: Int): Int {
        var mi = 0
        var pos = at
        while (pos < name.length && mi + 1 < meaningful.size) {
            val c = name[pos]
            when {
                c == meaningful[mi] || c == meaningful[mi + 1] -> { mi += 2; pos++ }
                isWildcard(c) -> pos++
                else -> return 0
            }
        }
        return pos - at
    }

    // The matching proper. Each returns the start of the match from its position (an offset into the name, or
    // MATCHED_EMPTY when nothing more had to be matched), or NO_MATCH; the original returns the fragments.

    private fun matchWildcards(name: String, patternIndex0: Int, nameIndex: Int): Int {
        var patternIndex = patternIndex0
        if (nameIndex < 0) return NO_MATCH
        if (!isWildcardAt(patternIndex)) {
            return if (patternIndex == pattern.size) MATCHED_EMPTY else matchFragment(name, patternIndex, nameIndex)
        }
        do patternIndex++ while (isWildcardAt(patternIndex))
        if (patternIndex == pattern.size) return MATCHED_EMPTY
        return matchSkippingWords(name, patternIndex, findNextPatternCharOccurrence(name, nameIndex, patternIndex), true)
    }

    private fun matchSkippingWords(name: String, patternIndex: Int, nameIndex0: Int, allowSpecialChars: Boolean): Int {
        var nameIndex = nameIndex0
        var maxFoundLength = 0
        while (nameIndex >= 0) {
            val fragmentLength = if (seemsLikeFragmentStart(name, patternIndex, nameIndex)) maxMatchingFragment(name, patternIndex, nameIndex) else 0
            if (fragmentLength > maxFoundLength) {
                if (!isMiddleMatch(name, patternIndex, nameIndex)) maxFoundLength = fragmentLength
                val r = matchInsideFragment(name, patternIndex, nameIndex, fragmentLength)
                if (r != NO_MATCH) return nameIndex
            }
            val next = findNextPatternCharOccurrence(name, nameIndex + 1, patternIndex)
            nameIndex = if (allowSpecialChars) next else checkForSpecialChars(name, nameIndex + 1, next, patternIndex)
        }
        return NO_MATCH
    }

    private fun findNextPatternCharOccurrence(name: String, startAt: Int, patternIndex: Int): Int =
        if (!isPatternChar(patternIndex - 1, '*') && !isWordSeparator[patternIndex]) indexOfWordStart(name, patternIndex, startAt)
        else indexOfIgnoreCase(name, startAt, patternIndex)

    private fun checkForSpecialChars(name: String, start: Int, end: Int, patternIndex: Int): Int {
        if (end < 0) return -1
        // No hard separators are configured for completion, so only the typed-dot rule applies.
        if (hasDots && !isPatternChar(patternIndex - 1, '.') && name.indexOf('.', start).let { it in start until end }) return -1
        return end
    }

    private fun seemsLikeFragmentStart(name: String, patternIndex: Int, nextOccurrence: Int): Boolean =
        !isUpperCase[patternIndex] || name[nextOccurrence].isUpperCase() || isWordStart(name, nextOccurrence) || !mixedCase

    private fun charEquals(patternIndex: Int, c: Char): Boolean =
        pattern[patternIndex] == c || toLower[patternIndex] == c || toUpper[patternIndex] == c

    private fun matchFragment(name: String, patternIndex: Int, nameIndex: Int): Int {
        val fragmentLength = maxMatchingFragment(name, patternIndex, nameIndex)
        return if (fragmentLength == 0) NO_MATCH else matchInsideFragment(name, patternIndex, nameIndex, fragmentLength).let {
            if (it == NO_MATCH) NO_MATCH else nameIndex
        }
    }

    private fun maxMatchingFragment(name: String, patternIndex: Int, nameIndex: Int): Int {
        if (!isFirstCharMatching(name, nameIndex, patternIndex)) return 0
        var i = 1
        while (nameIndex + i < name.length && patternIndex + i < pattern.size) {
            val nameChar = name[nameIndex + i]
            if (!charEquals(patternIndex + i, nameChar)) {
                if (isSkippingDigitBetweenPatternDigits(patternIndex + i, nameChar)) return 0
                break
            }
            i++
        }
        return i
    }

    private fun isSkippingDigitBetweenPatternDigits(patternIndex: Int, nameChar: Char): Boolean =
        pattern[patternIndex].isDigit() && pattern[patternIndex - 1].isDigit() && nameChar.isDigit()

    private fun matchInsideFragment(name: String, patternIndex: Int, nameIndex: Int, fragmentLength: Int): Int {
        // An exact middle match has to be at least three characters long, to keep out irrelevant ones.
        val minFragment = if (isMiddleMatch(name, patternIndex, nameIndex)) 3 else 1
        val humps = improveCamelHumps(name, patternIndex, nameIndex, fragmentLength, minFragment)
        if (humps != NO_MATCH) return humps
        return findLongestMatchingPrefix(name, patternIndex, nameIndex, fragmentLength, minFragment)
    }

    private fun isMiddleMatch(name: String, patternIndex: Int, nameIndex: Int): Boolean =
        isPatternChar(patternIndex - 1, '*') && !isWildcardAt(patternIndex + 1) &&
            name[nameIndex].isLetterOrDigit() && !isWordStart(name, nameIndex)

    private fun findLongestMatchingPrefix(name: String, patternIndex: Int, nameIndex: Int, fragmentLength: Int, minFragment: Int): Int {
        if (patternIndex + fragmentLength >= pattern.size) return nameIndex
        var i = fragmentLength
        while (i >= minFragment || (i > 0 && isWildcardAt(patternIndex + i))) {
            val r = if (isWildcardAt(patternIndex + i)) {
                matchWildcards(name, patternIndex + i, nameIndex + i)
            } else {
                var next = findNextPatternCharOccurrence(name, nameIndex + i + 1, patternIndex + i)
                next = checkForSpecialChars(name, nameIndex + i, next, patternIndex + i)
                if (next >= 0) matchSkippingWords(name, patternIndex + i, next, false) else NO_MATCH
            }
            if (r != NO_MATCH) return nameIndex
            i--
        }
        return NO_MATCH
    }

    /** With `CU` against `CurrentUser`, a later uppercase `U` is a better match than the `u` right after `C`. */
    private fun improveCamelHumps(name: String, patternIndex: Int, nameIndex: Int, maxFragment: Int, minFragment: Int): Int {
        for (i in minFragment until maxFragment) {
            if (isUpperCase[patternIndex + i] && pattern[patternIndex + i] != name[nameIndex + i]) {
                val nextWordStart = indexOfWordStart(name, patternIndex + i, nameIndex + i)
                if (matchWildcards(name, patternIndex + i, nextWordStart) != NO_MATCH) return nameIndex
            }
        }
        return NO_MATCH
    }

    private fun isFirstCharMatching(name: String, nameIndex: Int, patternIndex: Int): Boolean {
        if (nameIndex >= name.length) return false
        if (!charEquals(patternIndex, name[nameIndex])) return false
        // FIRST_LETTER: the first typed letter must have the case of the name's first letter.
        return !((patternIndex == 0 || (patternIndex == 1 && isWildcardAt(0))) &&
            (isUpperCase[patternIndex] || isLowerCase[patternIndex]) && isUpperCase[patternIndex] != name[0].isUpperCase())
    }

    private fun isWildcardAt(patternIndex: Int): Boolean = patternIndex in pattern.indices && isWildcard(pattern[patternIndex])

    private fun isPatternChar(patternIndex: Int, c: Char): Boolean = patternIndex in pattern.indices && pattern[patternIndex] == c

    private fun indexOfWordStart(name: String, patternIndex: Int, startFrom: Int): Int {
        if (startFrom >= name.length || (mixedCase && isLowerCase[patternIndex] && !(patternIndex > 0 && isWordSeparator[patternIndex - 1]))) {
            return -1
        }
        val special = !pattern[patternIndex].isLetterOrDigit()
        var i = startFrom
        while (true) {
            i = indexOfIgnoreCase(name, i, patternIndex)
            if (i < 0) return -1
            if (special || isWordStart(name, i)) return i
            i++
        }
    }

    private fun indexOfIgnoreCase(name: String, from: Int, patternIndex: Int): Int {
        val up = toUpper[patternIndex]
        val lo = toLower[patternIndex]
        for (i in from.coerceAtLeast(0) until name.length) {
            val c = name[i]
            if (c == up || c == lo) return i
        }
        return -1
    }

    private companion object {
        const val NO_MATCH = Int.MIN_VALUE
        const val MATCHED_EMPTY = Int.MAX_VALUE
        /** Camel-hump matching is worse than linear, so a longer pattern falls back to a substring match. */
        const val MAX_CAMEL_HUMP_MATCHING_LENGTH = 100

        fun isWildcard(c: Char): Boolean = c == ' ' || c == '*'

        fun isWordSeparator(c: Char): Boolean = c.isWhitespace() || c == '_' || c == '-' || c == ':' || c == '+' || c == '.'

        /** IntelliJ's `NameUtilCore.isWordStart`: an uppercase letter not inside an all-caps run (the run's last
         *  letter before a lowercase one starts the next word), any digit, a letter after a non-letter-or-digit. */
        fun isWordStart(text: String, i: Int): Boolean {
            val cur = text[i]
            val prev = if (i > 0) text[i - 1] else null
            if (cur.isUpperCase()) {
                if (prev != null && prev.isUpperCase()) {
                    // In an uppercase run, only the last one before a lowercase starts a word (`HTMLParser`).
                    val next = text.getOrNull(i + 1)
                    return next != null && next.isLowerCase()
                }
                return true
            }
            if (cur.isDigit()) return true
            if (!cur.isLetter()) return false
            return prev == null || !prev.isLetterOrDigit()
        }
    }
}
