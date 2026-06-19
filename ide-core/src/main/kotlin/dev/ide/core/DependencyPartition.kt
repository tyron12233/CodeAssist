package dev.ide.core

import dev.ide.deps.ResolvedArtifact
import dev.ide.model.Coordinate

/**
 * Partition a module's single whole-graph resolution closure back across its declared (direct) dependencies,
 * so the per-library model is preserved while the union of the libraries is exactly the unified closure (no
 * duplication, no independent-resolution drift).
 *
 * Each resolved artifact is assigned to the FIRST declarer (in declaration order) whose `dependsOn` chain
 * reaches it. A shared transitive therefore lands in exactly one library; the union still contains it. An
 * artifact reached from no declarer (which shouldn't happen for a closure rooted at the declarers) is attached
 * to the first declarer so nothing is ever dropped from the classpath.
 */
internal object DependencyPartition {

    /** [directs] is `(libraryName, coordinate)` in declaration order; [resolved] is the whole-graph closure. */
    fun partition(
        directs: List<Pair<String, Coordinate>>,
        resolved: List<ResolvedArtifact>,
    ): LinkedHashMap<String, MutableList<ResolvedArtifact>> {
        val byGa = HashMap<Pair<String, String>, ResolvedArtifact>()
        resolved.forEach { byGa[it.coordinate.group to it.coordinate.name] = it }

        val claimed = HashSet<Pair<String, String>>()
        val out = LinkedHashMap<String, MutableList<ResolvedArtifact>>()
        for ((libName, coord) in directs) {
            val bucket = out.getOrPut(libName) { ArrayList() }
            val queue = ArrayDeque<Pair<String, String>>()
            (coord.group to coord.name).let { if (byGa.containsKey(it)) queue.add(it) }
            val seen = HashSet<Pair<String, String>>()
            while (queue.isNotEmpty()) {
                val ga = queue.removeFirst()
                if (!seen.add(ga)) continue
                val art = byGa[ga] ?: continue
                if (claimed.add(ga)) bucket.add(art)
                art.dependsOn.forEach { queue.add(it.group to it.name) }
            }
        }
        // Defensive: an artifact reached from no declarer is attached to the first declarer so the whole
        // closure is always preserved on the classpath.
        resolved.filter { (it.coordinate.group to it.coordinate.name) !in claimed }
            .takeIf { it.isNotEmpty() }
            ?.let { leftover -> out.values.firstOrNull()?.addAll(leftover) }
        return out
    }
}
