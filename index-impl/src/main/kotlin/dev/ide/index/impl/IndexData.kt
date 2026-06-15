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
 */
internal class IndexData(private val matching: MatchingMode) {

    private class Doc(val term: String, val value: Any, val origin: IndexOrigin)

    private val docs = ArrayList<Doc>()
    private val terms = TreeMap<String, MutableList<Int>>()
    private val trigrams: HashMap<String, MutableList<Int>>? =
        if (matching == MatchingMode.PREFIX_AND_FUZZY) HashMap() else null

    fun add(term: String, value: Any, origin: IndexOrigin) {
        val id = docs.size
        docs.add(Doc(term, value, origin))
        terms.getOrPut(term) { ArrayList(1) }.add(id)
        trigrams?.let { tg -> Scoring.trigramsOf(term.lowercase()).forEach { g -> tg.getOrPut(g) { ArrayList(1) }.add(id) } }
    }

    fun clear() { docs.clear(); terms.clear(); trigrams?.clear() }

    fun exact(key: String): List<Any> = terms[key]?.map { docs[it].value } ?: emptyList()

    fun prefix(p: String, out: MutableList<Hit<Any>>, cap: Int) {
        for ((term, ids) in terms.tailMap(p)) {
            if (!term.startsWith(p)) break
            for (id in ids) {
                out.add(Hit(term, docs[id].value, Scoring.scorePrefix(term, p, docs[id].origin)))
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
        var acc: MutableSet<Int> = HashSet(sorted[0])
        for (i in 1 until sorted.size) {
            val s = sorted[i].toHashSet()
            acc = acc.filterTo(HashSet()) { it in s }
            if (acc.isEmpty()) break
        }
        return acc
    }

}
