package dev.ide.core.completion

import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionResultSet
import dev.ide.lang.completion.CompletionContributor

/**
 * Hippie / word completion as a language-agnostic contributor (formerly `IdeServices.withBufferWords`).
 * Appends identifier-like words already present in the live buffer that extend the prefix under the caret,
 * as low-priority [CompletionItemKind.WORD] items below the semantic ones. Works in any file — even one
 * with no completion backend — so the popup can still offer a name the user typed five lines up that no
 * resolver knows about. Runs LAST (after the language backend and every semantic contributor) so it can
 * de-duplicate against everything already added.
 */
object BufferWordsContributor : CompletionContributor {
    override val id = "platform.bufferWords"

    /** Registered with this order so it runs after the language backend and the semantic contributors. */
    const val ORDER = 10_000

    override suspend fun fillCompletionVariants(params: CompletionParams, result: CompletionResultSet) {
        val text = params.document.text.toString()
        val len = text.length
        val caret = params.offset.coerceIn(0, len)
        var start = caret
        while (start > 0 && isWordChar(text[start - 1])) start--
        val prefix = text.substring(start, caret)
        if (prefix.isEmpty()) return
        // Graded matching (prefix/camel-hump/substring) — hippie words are the pre-index fallback, so
        // `mDL` must reach a buffer's `myDynamicList` even before the symbol backends know the name.
        val matcher = dev.ide.lang.completion.PrefixMatcher(prefix)
        // Below the substring threshold every tier anchors on the first character (a prefix, or a camel hump
        // starting at the word's first letter), so a word starting elsewhere is rejected before it is copied.
        val anchored = prefix.length < dev.ide.lang.completion.PrefixMatcher.MIN_SUBSTRING_QUERY
        val first = prefix[0]

        val existing = HashSet<String>()
        result.elements.forEach { existing.add(it.label) }
        existing.add(prefix) // never re-offer the partial word the caret sits in

        // word -> nearest distance from the caret (so the closest occurrence wins the ordering)
        val nearest = HashMap<String, Int>()
        val words = WordTable(text)
        var i = 0
        while (i < len) {
            if (isWordStart(text[i])) {
                var j = i + 1
                while (j < len && isWordChar(text[j])) j++
                val isCaretToken = caret in i..j // the very token under the caret — skip it
                if (!isCaretToken && j - i >= prefix.length && (!anchored || text[i].equals(first, ignoreCase = true)) &&
                    holdsInOrder(text, i, j, prefix)
                ) {
                    val word = words.wordAt(i, j)
                    if (word !in existing && matcher.matches(word)) {
                        val dist = if (caret < i) i - caret else caret - j
                        val prev = nearest[word]
                        if (prev == null || dist < prev) nearest[word] = dist
                    }
                }
                i = j
            } else {
                // An assignment, not `i++`: in a suspend function the compiler boxes the value of a trailing
                // `i++` and drops it, one Integer per character of the buffer on ART (no escape analysis).
                i += 1
            }
        }
        // Capped to the nearest few, so a page holding fewer words than matched is not the whole answer.
        if (nearest.size > MAX_WORDS) result.markIncomplete()
        if (nearest.isEmpty()) return

        val baseSort = (result.elements.maxOfOrNull { it.sortPriority } ?: 0) + 1000
        nearest.entries.sortedBy { it.value }.take(MAX_WORDS).forEachIndexed { idx, e ->
            result.addElement(
                CompletionItem(
                    label = e.key,
                    insertText = e.key,
                    kind = CompletionItemKind.WORD,
                    sortPriority = baseSort + idx,
                    caret = CaretAction.AtEnd,
                ),
            )
        }
    }

    /** Most buffer words offered per request, nearest to the caret first. */
    private const val MAX_WORDS = 20

    /**
     * Whether [prefix]'s characters occur in `text[start, end)` in order, ignoring case. Every
     * [dev.ide.lang.completion.PrefixMatcher] grade (prefix, camel hump, substring) implies it, so a word that fails it is rejected here, before it is
     * copied out of the buffer: a short prefix's first letter alone admits thousands of words in a long file.
     */
    private fun holdsInOrder(text: String, start: Int, end: Int, prefix: String): Boolean {
        var q = 0
        var i = start
        while (q < prefix.length && i < end) {
            if (text[i].equals(prefix[q], ignoreCase = true)) q++
            i++
        }
        return q == prefix.length
    }

    /**
     * The distinct words of one scan of [text], each copied out of the buffer once. A long file repeats its
     * words thousands of times (`fun`, `val`, a class name), and a short prefix admits most of them, so copying
     * every occurrence to look it up made a string per occurrence. Open addressing over the characters' hash,
     * so a repeat is found without allocating.
     */
    private class WordTable(private val text: String) {
        private var hashes = IntArray(256)
        private var strings = arrayOfNulls<String>(256)
        private var size = 0

        fun wordAt(start: Int, end: Int): String {
            var h = 0
            for (k in start until end) h = 31 * h + text[k].code
            var slot = h and (strings.size - 1)
            while (true) {
                val existing = strings[slot] ?: break
                if (hashes[slot] == h && existing.length == end - start && text.regionMatches(start, existing, 0, end - start)) {
                    return existing
                }
                slot = (slot + 1) and (strings.size - 1)
            }
            val word = text.substring(start, end)
            hashes[slot] = h
            strings[slot] = word
            if (++size * 2 > strings.size) grow()
            return word
        }

        private fun grow() {
            val oldHashes = hashes
            val oldStrings = strings
            hashes = IntArray(oldHashes.size * 2)
            strings = arrayOfNulls(oldStrings.size * 2)
            for (k in oldStrings.indices) {
                val w = oldStrings[k] ?: continue
                var slot = oldHashes[k] and (strings.size - 1)
                while (strings[slot] != null) slot = (slot + 1) and (strings.size - 1)
                hashes[slot] = oldHashes[k]
                strings[slot] = w
            }
        }
    }

    private fun isWordStart(c: Char): Boolean = c.isLetter() || c == '_' || c == '$'
    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'
}
