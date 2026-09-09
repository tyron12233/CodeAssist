package dev.ide.core

import dev.ide.deps.ResolvedArtifact
import dev.ide.model.Coordinate

/** `group:name`, the key the resolved graph's `dependsOn` edges are expressed in. */
private typealias Ga = Pair<String, String>

/** `group:name:classifier` (`""` for a module's main artifact), the key an artifact is claimed under. */
private typealias ArtifactId = Triple<String, String, String>

/**
 * Partition a module's single whole-graph resolution closure back across its declared (direct) dependencies,
 * so the per-library model is preserved while the union of the libraries is exactly the unified closure (no
 * duplication, no independent-resolution drift).
 *
 * Each resolved artifact is assigned to the FIRST declarer (in declaration order) whose `dependsOn` chain
 * reaches it. A shared transitive therefore lands in exactly one library; the union still contains it. An
 * artifact reached from no declarer (which shouldn't happen for a closure rooted at the declarers) is attached
 * to the first declarer so nothing is ever dropped from the classpath.
 *
 * Reachability is walked per `group:name`, which is the granularity of the resolved graph's edges, but
 * artifacts are CLAIMED per `group:name:classifier`: one module can contribute several files under one
 * `group:name` when more than one of its Maven classifiers was declared (`gdx-platform`, once per ABI), and
 * each declaration must end up owning the artifact it actually named.
 */
internal object DependencyPartition {

    private val ResolvedArtifact.ga: Ga get() = coordinate.group to coordinate.name
    private val ResolvedArtifact.artifactId: ArtifactId
        get() = Triple(coordinate.group, coordinate.name, coordinate.classifier.orEmpty())

    /** [directs] is `(libraryName, coordinate)` in declaration order; [resolved] is the whole-graph closure. */
    fun partition(
        directs: List<Pair<String, Coordinate>>,
        resolved: List<ResolvedArtifact>,
    ): LinkedHashMap<String, MutableList<ResolvedArtifact>> {
        val byGa = HashMap<Ga, MutableList<ResolvedArtifact>>()
        resolved.forEach { byGa.getOrPut(it.ga) { ArrayList() }.add(it) }

        val claimed = HashSet<ArtifactId>()
        val out = LinkedHashMap<String, MutableList<ResolvedArtifact>>()
        for ((libName, coord) in directs) {
            val bucket = out.getOrPut(libName) { ArrayList() }
            val root: Ga = coord.group to coord.name
            val queue = ArrayDeque<Ga>()
            if (byGa.containsKey(root)) queue.add(root)
            val seen = HashSet<Ga>()
            while (queue.isNotEmpty()) {
                val ga = queue.removeFirst()
                if (!seen.add(ga)) continue
                val candidates = byGa[ga] ?: continue
                // At the declarer's OWN module, take only the artifact it named: a declaration of
                // `…:natives-arm64-v8a` must not also swallow `…:natives-armeabi-v7a`, which its sibling
                // declaration names. Anywhere else in the closure (and when nothing matches the declared
                // classifier) every unclaimed artifact of the module is taken, as before.
                val exact = if (ga == root) candidates.filter { it.coordinate.classifier == coord.classifier } else emptyList()
                for (art in exact.ifEmpty { candidates }) {
                    if (claimed.add(art.artifactId)) bucket.add(art)
                }
                candidates.flatMap { it.dependsOn }.forEach { queue.add(it.group to it.name) }
            }
        }
        // Defensive: an artifact reached from no declarer is attached to the first declarer so the whole
        // closure is always preserved on the classpath.
        resolved.filter { it.artifactId !in claimed }
            .takeIf { it.isNotEmpty() }
            ?.let { leftover -> out.values.firstOrNull()?.addAll(leftover) }
        return out
    }
}
