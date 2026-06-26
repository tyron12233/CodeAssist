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

        val existing = HashSet<String>()
        result.elements.forEach { existing.add(it.label) }
        existing.add(prefix) // never re-offer the partial word the caret sits in

        // word -> nearest distance from the caret (so the closest occurrence wins the ordering)
        val nearest = HashMap<String, Int>()
        var i = 0
        while (i < len) {
            if (isWordStart(text[i])) {
                var j = i + 1
                while (j < len && isWordChar(text[j])) j++
                val isCaretToken = caret in i..j // the very token under the caret — skip it
                if (!isCaretToken && j - i >= prefix.length) {
                    val word = text.substring(i, j)
                    if (word !in existing && word.startsWith(prefix, ignoreCase = true)) {
                        val dist = if (caret < i) i - caret else caret - j
                        val prev = nearest[word]
                        if (prev == null || dist < prev) nearest[word] = dist
                    }
                }
                i = j
            } else i++
        }
        if (nearest.isEmpty()) return

        val baseSort = (result.elements.maxOfOrNull { it.sortPriority } ?: 0) + 1000
        nearest.entries.sortedBy { it.value }.take(20).forEachIndexed { idx, e ->
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

    private fun isWordStart(c: Char): Boolean = c.isLetter() || c == '_' || c == '$'
    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'
}
