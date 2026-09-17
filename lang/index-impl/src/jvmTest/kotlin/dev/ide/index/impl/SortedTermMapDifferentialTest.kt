package dev.ide.index.impl

import java.util.TreeMap
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [SortedTermMap] against the `java.util.TreeMap` it replaces, on the same operations in the same order.
 *
 * The term dictionary is the one thing in the index whose replacement could be wrong without failing: a
 * mis-ordered walk still returns hits, just not the right ones, and the cap then keeps the wrong ones. So the
 * structure it replaced is kept as the oracle rather than a hand-written expectation, the way the parser and
 * the class-file decoder are checked against the libraries they replace.
 */
class SortedTermMapDifferentialTest {

    private fun walk(map: SortedTermMap<String>, from: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        map.forEachFrom(from) { term, value -> out.add(term to value); true }
        return out
    }

    private fun walk(map: TreeMap<String, String>, from: String): List<Pair<String, String>> =
        map.tailMap(from).map { it.key to it.value }

    @Test
    fun theTwoAgreeOverAQuarterOfAMillionOperations() {
        val random = Random(20260917)
        val ours = SortedTermMap<String>()
        val theirs = TreeMap<String, String>()

        // A vocabulary shaped like real terms: a long tail of near-duplicates sharing prefixes, which is what
        // a prefix walk is sensitive to, plus repeats so getOrPut's existing-key path is exercised.
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_0123456789$"
        fun term(): String {
            val length = 1 + random.nextInt(12)
            return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
        }

        val vocabulary = List(20_000) { term() }
        repeat(250_000) { step ->
            val t = vocabulary[random.nextInt(vocabulary.size)]
            assertEquals(theirs.getOrPut(t) { "v:$t" }, ours.getOrPut(t) { "v:$t" }, "getOrPut at step $step")

            if (step % 2_000 == 0) {
                assertEquals(theirs.size, ours.size, "size at step $step")
                assertEquals(theirs[t], ours[t], "get at step $step")
                for (from in listOf("", t, t.take(1), t + "~", "~")) {
                    assertEquals(walk(theirs, from), walk(ours, from), "walk from '$from' at step $step")
                }
            }
        }
        assertEquals(theirs.size, ours.size)
        assertEquals(walk(theirs, ""), walk(ours, ""), "the whole dictionary, in order")
    }

    @Test
    fun theTwoAgreeOnEveryPrefixWindow() {
        // The query the index actually runs: walk from a prefix, stop at the first term that does not carry
        // it. Checked for every one- and two-character prefix over the same corpus.
        val random = Random(4242)
        val ours = SortedTermMap<String>()
        val theirs = TreeMap<String, String>()
        repeat(30_000) {
            val t = "sym${random.nextInt(9000)}${"abcde"[random.nextInt(5)]}"
            ours.getOrPut(t) { "v:$t" }
            theirs.getOrPut(t) { "v:$t" }
        }

        fun oursPrefix(p: String): List<String> {
            val out = ArrayList<String>()
            ours.forEachFrom(p) { term, _ -> if (!term.startsWith(p)) false else { out.add(term); true } }
            return out
        }

        fun theirsPrefix(p: String): List<String> {
            val out = ArrayList<String>()
            for ((term, _) in theirs.tailMap(p)) {
                if (!term.startsWith(p)) break
                out.add(term)
            }
            return out
        }

        val alphabet = "sym0123456789abcde"
        for (a in alphabet) {
            assertEquals(theirsPrefix("$a"), oursPrefix("$a"), "prefix '$a'")
            for (b in alphabet) assertEquals(theirsPrefix("$a$b"), oursPrefix("$a$b"), "prefix '$a$b'")
        }
    }
}
