package dev.ide.ui.editor

import dev.ide.ui.backend.UiCompletionItem
import dev.ide.ui.backend.UiCompletionKind
import dev.ide.ui.backend.UiCompletionResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [CompletionSession.filtered] against a straightforward reference: keep the fuzzy matches, then stable-sort
 * by (buffer word last, match tier). The editor's filter is written for cost, so it must pick and order the
 * same items as the plain formulation, and [matchTier] must agree with the copy-the-name formulation.
 */
class CompletionFilterOrderTest {

    private fun referenceTier(candidate: String, query: String): Int {
        if (query.isEmpty()) return 0
        val name = candidate.takeWhile { isIdentifierChar(it) }
        return when {
            candidate == query || name == query -> 0
            candidate.equals(query, ignoreCase = true) || name.equals(query, ignoreCase = true) -> 1
            candidate.startsWith(query) -> 2
            candidate.startsWith(query, ignoreCase = true) -> 3
            else -> 4
        }
    }

    private fun referenceFiltered(base: List<UiCompletionItem>, prefix: String): List<UiCompletionItem> =
        base.filter { matchPositions(it.label, prefix) != null }
            .sortedWith(compareBy({ if (it.kind == UiCompletionKind.Word) 1 else 0 }, { referenceTier(it.label, prefix) }))

    private val alphabet = "aAbBtTeExX_(: 1"

    private fun randomWord(rnd: Random, max: Int): String =
        buildString { repeat(rnd.nextInt(1, max + 1)) { append(alphabet[rnd.nextInt(alphabet.length)]) } }

    @Test
    fun filterMatchesTheReferenceOrdering() {
        val rnd = Random(7)
        val kinds = listOf(UiCompletionKind.Method, UiCompletionKind.Word, UiCompletionKind.Class, UiCompletionKind.Field)
        repeat(400) { iteration ->
            val items = List(rnd.nextInt(0, 30)) { i ->
                val label = randomWord(rnd, 8)
                UiCompletionItem(label = label, insertText = label, detail = "d$i", kind = kinds[rnd.nextInt(kinds.size)], sortPriority = i)
            }
            val session = CompletionSession.from(UiCompletionResult(items, replaceStart = 0, replaceEnd = 0))
            val prefix = randomWord(rnd, 3)
            assertEquals(referenceFiltered(items, prefix), session.filtered(prefix), "iteration $iteration, prefix '$prefix'")
        }
    }

    @Test
    fun matchTierAgreesWithTheReference() {
        val rnd = Random(11)
        repeat(3000) {
            val candidate = randomWord(rnd, 10)
            val query = randomWord(rnd, 4)
            assertEquals(referenceTier(candidate, query), matchTier(candidate, query), "'$candidate' vs '$query'")
        }
        assertEquals(0, matchTier("Text(text: String)", "Text"))
        assertEquals(1, matchTier("Text(text: String)", "text"))
    }

    @Test
    fun rowKeysAreUniqueEvenForIdenticalItems() {
        val a = UiCompletionItem(label = "print", insertText = "print", detail = "Unit", kind = UiCompletionKind.Method, sortPriority = 0)
        val keys = completionRowKeys(listOf(a, a.copy(), a.copy(detail = "Int"), a))
        assertEquals(keys.size, keys.toSet().size)
    }
}
