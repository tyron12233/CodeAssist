package dev.ide.index.impl

import dev.ide.index.Hit
import dev.ide.index.IndexOrigin
import dev.ide.index.MatchingMode
import java.util.TreeMap

/**
 * In-memory data for one index partition set: a sorted term dictionary (TreeMap) over postings, plus an
 * optional trigram index for fuzzy/substring. This is the pragmatic stand-in for the doc's mmap'd
 * front-coded segments — same query semantics, loaded into RAM (small: names are short). One [IndexData]
 * holds the static (SDK+libraries) side; a second holds the incremental source side.
 *
 * Postings are kept in packed [IntList]s, not `ArrayList<Int>`. The source side is rebuilt into RAM on every
 * index pass and, for a resource-heavy Android project (every dependency/AAR `res/` file is indexed here),
 * the postings - especially the trigram index's - dominate its footprint. A boxed `Integer` in an `ArrayList`
 * costs ~20 bytes; a packed `int` costs 4. Storing postings as `IntArray`s cuts the resident source-index
 * memory several-fold, which is what keeps the index inside a tight device heap during the build.
 */
internal class IndexData(private val matching: MatchingMode) {

    private class Doc(val term: String, val value: Any, val origin: IndexOrigin)

    /** A growable primitive `int` list - the posting list for one term/trigram, without `Integer` boxing. */
    private class IntList {
        private var a = IntArray(1)
        var size = 0
            private set

        fun add(v: Int) {
            if (size == a.size) a = a.copyOf(if (a.size < 1024) a.size * 2 else a.size + (a.size shr 1))
            a[size++] = v
        }

        operator fun get(i: Int): Int = a[i]
    }

    private val docs = ArrayList<Doc>()
    private val terms = TreeMap<String, IntList>()
    private val trigrams: HashMap<String, IntList>? =
        if (matching == MatchingMode.PREFIX_AND_FUZZY) HashMap() else null

    /** Number of indexed (term, value) entries - for diagnostics/memory reporting. */
    fun size(): Int = docs.size

    fun add(term: String, value: Any, origin: IndexOrigin) {
        val id = docs.size
        docs.add(Doc(term, value, origin))
        terms.getOrPut(term) { IntList() }.add(id)
        trigrams?.let { tg -> Scoring.trigramsOf(term.lowercase()).forEach { g -> tg.getOrPut(g) { IntList() }.add(id) } }
    }

    fun clear() { docs.clear(); terms.clear(); trigrams?.clear() }

    fun exact(key: String): List<Any> {
        val ids = terms[key] ?: return emptyList()
        val out = ArrayList<Any>(ids.size)
        for (i in 0 until ids.size) out.add(docs[ids[i]].value)
        return out
    }

    fun prefix(p: String, out: MutableList<Hit<Any>>, cap: Int) {
        for ((term, ids) in terms.tailMap(p)) {
            if (!term.startsWith(p)) break
            for (i in 0 until ids.size) {
                val d = docs[ids[i]]
                out.add(Hit(term, d.value, Scoring.scorePrefix(term, p, d.origin)))
                if (out.size >= cap) return
            }
        }
    }

    fun fuzzy(pattern: String, out: MutableList<Hit<Any>>, cap: Int) {
        if (trigrams == null || pattern.length < 3) { prefix(pattern, out, cap); return }
        val grams = Scoring.trigramsOf(pattern.lowercase())
        val candidates = intersectPostings(grams) ?: return
        for (id in candidates) {
            val d = docs[id]
            val s = Scoring.scoreFuzzy(d.term, pattern, d.origin)
            if (s > 0) {
                out.add(Hit(d.term, d.value, s))
                if (out.size >= cap) return
            }
        }
    }

    /** Smallest-postings-first intersection of the query's trigrams → candidate docIds. */
    private fun intersectPostings(grams: List<String>): Collection<Int>? {
        val tg = trigrams ?: return null
        val lists = grams.mapNotNull { tg[it] }
        if (lists.isEmpty()) return null
        val sorted = lists.sortedBy { it.size }
        var acc = sorted[0].toHashSet()
        for (i in 1 until sorted.size) {
            val s = sorted[i].toHashSet()
            acc = acc.filterTo(HashSet()) { it in s }
            if (acc.isEmpty()) break
        }
        return acc
    }

    private fun IntList.toHashSet(): HashSet<Int> {
        val s = HashSet<Int>(size * 2)
        for (i in 0 until size) s.add(this[i])
        return s
    }

}
