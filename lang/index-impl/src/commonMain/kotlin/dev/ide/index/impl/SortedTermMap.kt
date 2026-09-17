package dev.ide.index.impl

/**
 * The term dictionary: a map from term to postings that can also be walked in order from a given key.
 *
 * `java.util.TreeMap` is what this replaces, and it is the only thing that kept [IndexData] on the JVM. The
 * replacement is not a tree, because the four things the index actually asks for are narrower than a sorted
 * map:
 *
 * - `get` and `getOrPut`, on the hot path of every indexed entry
 * - an ascending walk from a prefix, which the caller ALWAYS abandons as soon as the prefix stops matching
 * - no removal at all; a tombstoned entry stays in its posting list and compaction rebuilds the whole store
 *
 * So the terms live in a hash map (O(1) point lookups, where a tree was O(log n)) and the ORDER lives beside
 * it in a sorted array. New terms land in an unsorted pending list and are folded into the array when there
 * are enough of them to pay for the merge; a walk merges the two ascending, lazily, so a prefix query over a
 * hundred thousand terms still stops after the handful it was capped at.
 *
 * The amortisation is the point. Sorting on every insert is O(n) memmove per term, and sorting on every query
 * is O(n log n) per keystroke. Folding at a threshold proportional to the dictionary makes the merge cost
 * O(1) amortised per insert, and the pending list small enough that sorting it when a query arrives after an
 * edit is not worth measuring.
 *
 * Not thread-safe, and does not need to be: [IndexData] groups it in a `Store` behind one `@Volatile`
 * reference, so a query snapshots a whole consistent state with a single read.
 */
internal class SortedTermMap<V : Any> {

    private val byTerm = HashMap<String, V>()

    /** Ascending, no duplicates, no entry that is also in [pending]. */
    private var sorted = emptyArray<String>()

    /** Terms added since the last fold. Sorted lazily, on the first walk that needs the order. */
    private var pending = ArrayList<String>()
    private var pendingIsSorted = true

    val size: Int get() = byTerm.size

    operator fun get(key: String): V? = byTerm[key]

    /**
     * The value for [key], inserting `create()` when there is none.
     *
     * Not `inline`, deliberately: every call site passes a lambda that captures nothing, which compiles to a
     * singleton, and inlining would put the private ordering state in a public signature.
     */
    fun getOrPut(key: String, create: () -> V): V {
        byTerm[key]?.let { return it }
        val value = create()
        byTerm[key] = value
        pending.add(key)
        pendingIsSorted = false
        if (pending.size > foldThreshold()) fold()
        return value
    }

    /**
     * Walk every entry whose key is `>= from`, ascending, until [action] returns false.
     *
     * This is `tailMap(from)` narrowed to what the index does with it. Returning false rather than breaking
     * out of a loop is what keeps the walk lazy across the two backing sequences.
     */
    fun forEachFrom(from: String, action: (String, V) -> Boolean) {
        if (!pendingIsSorted) {
            pending.sort()
            pendingIsSorted = true
        }
        var i = lowerBound(sorted, from)
        var j = lowerBound(pending, from)
        while (true) {
            val a = if (i < sorted.size) sorted[i] else null
            val b = if (j < pending.size) pending[j] else null
            val next = when {
                a == null && b == null -> return
                a == null -> b!!.also { j++ }
                b == null -> a.also { i++ }
                a <= b -> a.also { i++ }
                else -> b.also { j++ }
            }
            val value = byTerm[next] ?: continue
            if (!action(next, value)) return
        }
    }

    /**
     * How many pending terms to tolerate before folding them into the sorted array.
     *
     * Proportional to the dictionary, with a floor so a small index is not folded on every other insert: the
     * merge is O(n), so folding every n/16 inserts costs O(16) per insert however large the index gets.
     */
    private fun foldThreshold(): Int = maxOf(64, sorted.size shr 4)

    private fun fold() {
        if (pending.isEmpty()) return
        if (!pendingIsSorted) {
            pending.sort()
            pendingIsSorted = true
        }
        val merged = arrayOfNulls<String>(sorted.size + pending.size)
        var i = 0
        var j = 0
        var n = 0
        while (i < sorted.size && j < pending.size) {
            merged[n++] = if (sorted[i] <= pending[j]) sorted[i++] else pending[j++]
        }
        while (i < sorted.size) merged[n++] = sorted[i++]
        while (j < pending.size) merged[n++] = pending[j++]
        @Suppress("UNCHECKED_CAST")
        sorted = merged as Array<String>
        pending = ArrayList()
    }

    /** The first index whose element is `>= key`, or the size when every element sorts before it. */
    private fun lowerBound(keys: Array<String>, key: String): Int {
        var lo = 0
        var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun lowerBound(keys: ArrayList<String>, key: String): Int {
        var lo = 0
        var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
