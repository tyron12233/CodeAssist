package dev.ide.index.impl

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The term dictionary, which is the structure that let [IndexData] off the JVM.
 *
 * It is worth its own suite because it is not a data structure anyone would recognise at a glance: the order
 * lives in two places at once, a sorted array and a pending list, and a walk merges them. Every bug that
 * shape can have is an ordering bug, and an ordering bug in a term dictionary does not crash — it drops
 * completions, or returns them in the wrong order so the cap keeps the wrong ones.
 *
 * `SortedTermMapDifferentialTest` in `jvmTest` runs the same operations against `java.util.TreeMap`, which is
 * what this replaces. These are the cases worth stating outright.
 */
class SortedTermMapTest {

    private fun mapOf(vararg terms: String): SortedTermMap<String> {
        val map = SortedTermMap<String>()
        for (t in terms) map.getOrPut(t) { "v:$t" }
        return map
    }

    private fun <V : Any> SortedTermMap<V>.from(key: String): List<String> {
        val out = ArrayList<String>()
        forEachFrom(key) { term, _ -> out.add(term); true }
        return out
    }

    @Test
    fun aWalkIsAscendingAcrossBothHalves() {
        // Fewer terms than the fold threshold, so every one of them is still in the pending list: this is the
        // case where NOTHING is in the sorted array and the walk is driven entirely by the other half.
        val map = mapOf("delta", "alpha", "charlie", "bravo")
        assertEquals(listOf("alpha", "bravo", "charlie", "delta"), map.from(""))
    }

    @Test
    fun aWalkStartsAtTheFirstKeyNotBeforeIt() {
        val map = mapOf("ant", "ape", "bee", "cat")
        assertEquals(listOf("bee", "cat"), map.from("bee"), "the start key is included")
        assertEquals(listOf("bee", "cat"), map.from("bad"), "a key that is not present starts at the next one")
        assertEquals(emptyList(), map.from("zebra"), "past the end is empty, not the whole map")
    }

    @Test
    fun aWalkStopsWhenTheActionSaysSo() {
        // The prefix queries abandon the walk the moment the prefix stops matching, and that has to actually
        // stop it: a dictionary of a hundred thousand terms is walked for the five a query asked for.
        val map = mapOf("a", "ab", "abc", "b", "bc")
        val seen = ArrayList<String>()
        map.forEachFrom("a") { term, _ -> seen.add(term); term.startsWith("a") }
        assertEquals(listOf("a", "ab", "abc", "b"), seen, "the first non-matching term is seen, then the walk ends")
    }

    @Test
    fun foldingDoesNotDisturbTheOrder() {
        // Enough terms to cross the fold threshold several times, inserted in an order that shares nothing
        // with the sorted one, and then walked. This is the case the two-halves design exists to get right.
        val map = SortedTermMap<String>()
        val terms = (0 until 2000).map { "term${(it * 7919) % 2000}" }
        for (t in terms) map.getOrPut(t) { "v:$t" }
        assertEquals(2000, map.size)
        assertEquals(terms.sorted(), map.from(""))
    }

    @Test
    fun aWalkSeesTermsAddedSinceTheLastWalk() {
        // Queries and edits interleave in the editor, and a walk after an edit has to sort the pending half
        // again. Getting the lazy flag wrong shows up here and nowhere else.
        val map = mapOf("beta")
        assertEquals(listOf("beta"), map.from(""))
        map.getOrPut("alpha") { "v:alpha" }
        assertEquals(listOf("alpha", "beta"), map.from(""), "and in the right order, not appended")
        map.getOrPut("gamma") { "v:gamma" }
        assertEquals(listOf("alpha", "beta", "gamma"), map.from(""))
    }

    @Test
    fun getOrPutReturnsTheSameValueForTheSameTerm() {
        val map = SortedTermMap<StringBuilder>()
        val first = map.getOrPut("dup") { StringBuilder("one") }
        val second = map.getOrPut("dup") { StringBuilder("two") }
        assertTrue(first === second, "a repeated term appends to its existing posting list")
        assertEquals("one", second.toString())
        assertEquals(1, map.size, "and is not counted twice")
        assertEquals(listOf("dup"), map.from(""), "nor walked twice")
    }

    @Test
    fun anAbsentTermIsNull() {
        val map = mapOf("present")
        assertEquals("v:present", map["present"])
        assertNull(map["absent"])
    }

    @Test
    fun theOrderIsExactlyStringComparison() {
        // Not a locale collation and not case-insensitive: the on-disk segments are sorted by the same rule,
        // and the two have to agree or a query that spans both returns them interleaved wrongly.
        val terms = listOf("Zebra", "apple", "Apple", "_under", "9nine", "ápple", "APPLE")
        val map = SortedTermMap<String>()
        for (t in terms) map.getOrPut(t) { "v:$t" }
        assertEquals(terms.sorted(), map.from(""))
    }

    @Test
    fun aRandomisedRunMatchesASortedList() {
        // The cheap stand-in for the TreeMap differential, so the property is checked on iOS too.
        val random = Random(20260917)
        val map = SortedTermMap<String>()
        val model = HashSet<String>()
        repeat(5000) {
            val term = "t${random.nextInt(1500)}"
            map.getOrPut(term) { "v:$term" }
            model.add(term)
            if (random.nextInt(50) == 0) {
                val from = "t${random.nextInt(1500)}"
                assertEquals(model.filter { it >= from }.sorted(), map.from(from))
            }
        }
        assertEquals(model.size, map.size)
        assertEquals(model.sorted(), map.from(""))
    }
}
