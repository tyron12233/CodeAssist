package dev.ide.deps.impl

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
 *
 * A declaration scoped to a build variant (`debugImplementation`) only ever shares a classpath with the shared
 * declarations and its own variant's, so a transitive is claimed once per variant rather than once overall:
 * `debugImplementation(x)` and `releaseImplementation(y)` reaching the same library each get it, since a release
 * build never sees the debug declaration's share. Shared declarations claim first, and what they claim is on
 * every variant's classpath, so no variant declaration takes it again.
 */
object DependencyPartition {

    private val ResolvedArtifact.ga: Ga get() = coordinate.group to coordinate.name
    private val ResolvedArtifact.artifactId: ArtifactId
        get() = Triple(coordinate.group, coordinate.name, coordinate.classifier.orEmpty())

    /**
     * [directs] is `(libraryName, coordinate)` in declaration order; [resolved] is the whole-graph closure;
     * [variants] names the build variant a declaration is scoped to (absent or null: shared by every variant).
     */
    fun partition(
        directs: List<Pair<String, Coordinate>>,
        resolved: List<ResolvedArtifact>,
        variants: Map<String, String?> = emptyMap(),
    ): LinkedHashMap<String, MutableList<ResolvedArtifact>> {
        val byGa = HashMap<Ga, MutableList<ResolvedArtifact>>()
        resolved.forEach { byGa.getOrPut(it.ga) { ArrayList() }.add(it) }

        val shared = HashSet<ArtifactId>()
        val byVariant = HashMap<String, HashSet<ArtifactId>>()
        val out = LinkedHashMap<String, MutableList<ResolvedArtifact>>()
        // Buckets in declaration order; claiming shared declarations first.
        directs.forEach { (libName, _) -> out.getOrPut(libName) { ArrayList() } }
        val ordered = directs.filter { variants[it.first] == null } + directs.filter { variants[it.first] != null }
        for ((libName, coord) in ordered) {
            val bucket = out.getValue(libName)
            val variant = variants[libName]
            val own = if (variant == null) shared else byVariant.getOrPut(variant) { HashSet() }
            fun claim(id: ArtifactId): Boolean = id !in shared && own.add(id)
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
                    if (claim(art.artifactId)) bucket.add(art)
                }
                candidates.flatMap { it.dependsOn }.forEach { queue.add(it.group to it.name) }
            }
        }
        // Defensive: an artifact reached from no declarer is attached to the first declarer so the whole
        // closure is always preserved on the classpath.
        val claimed = shared + byVariant.values.flatten()
        resolved.filter { it.artifactId !in claimed }
            .takeIf { it.isNotEmpty() }
            ?.let { leftover -> out.values.firstOrNull()?.addAll(leftover) }
        return out
    }
}
