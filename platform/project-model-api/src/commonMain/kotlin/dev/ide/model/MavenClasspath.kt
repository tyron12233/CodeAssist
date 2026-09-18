package dev.ide.model

/**
 * Classpath version-conflict resolution, shared by every classpath assembler so the editor's analysis
 * classpath and the build classpath agree. A single [LibraryDependency] carries its whole resolved transitive
 * closure as jars, and a module's declared dependencies are each resolved independently — so two of them can
 * drag in different versions of the same artifact (e.g. `androidx.collection` at 1.1.0 via one closure, 1.4.0
 * via another). Two copies on the compile path are merely wasteful for analysis, but two dex archives of the
 * same class are FATAL to the Android dex merge.
 *
 * [resolveVersionConflicts] mirrors Gradle/Maven "newest wins": for an artifact seen at more than one version,
 * keep only the newest. Because each declared dependency's closure already contains the newest version *its*
 * subgraph requested, the newest across the union equals the version a single whole-graph resolve would pick —
 * so deduping the union here is equivalent to resolving the module's whole dependency set as one graph, without
 * re-resolving. The version is read from the jar's Maven-layout path; entries whose path isn't Maven-shaped
 * (local jars, module outputs, the SDK) carry no coordinate and pass through untouched.
 */
object MavenClasspath {

    /** A Maven artifact recovered from a classpath jar's path: [artifactKey] is the artifact directory (stable
     *  across versions of one `group:name`), [version] its per-version child: the inputs to "newest wins".
     *  [classifier] (`""` for the main artifact) separates the SECONDARY artifacts a module publishes under
     *  one `group:name:version`. */
    internal class Artifact(val artifactKey: String, val version: String, val classifier: String) {
        /**
         * The "newest wins" key. Two classifiers of one `group:name` are DIFFERENT artifacts, not two
         * versions of one, so each gets its own slot: the six `gdx-platform-1.14.2-natives-*.jar` files must
         * all survive. Folding them onto one key made the first path seen evict the other five.
         */
        val conflictKey: String get() = if (classifier.isEmpty()) artifactKey else "$artifactKey|$classifier"
    }

    fun resolveVersionConflicts(items: List<ClasspathEntry>): List<ClasspathEntry> {
        val winner = HashMap<String, String>()
        for (e in items) {
            val c = coordinateOf(e.root.path) ?: continue
            val cur = winner[c.conflictKey]
            if (cur == null || isNewer(c.version, cur)) winner[c.conflictKey] = c.version
        }
        val out = LinkedHashMap<String, ClasspathEntry>()
        for (e in items) {
            val c = coordinateOf(e.root.path)
            if (c != null && winner[c.conflictKey] != c.version) continue   // a superseded version of this artifact, dropped
            out.getOrPut("${e.kind.id}|${e.root.path}") { e }
        }
        return out.values.toList()
    }

    /**
     * Read a `(artifactKey, version)` from a jar path laid out the way the dependency resolver writes its
     * cache — Maven repository layout: `<base>/<group/as/path>/<name>/<version>/<name>-<version>[-cls].jar`, or
     * an exploded AAR's `…/<name>/<version>/<name>-<version>-exploded/classes.jar`. The *artifact directory*
     * (`<base>/…/<name>`) is identical for every version of one `group:name`, so its path is a stable conflict
     * key without needing to recover the group string; the version is its child directory. Returns null for any
     * path that doesn't match (local jars, module output dirs, the SDK) so those are never wrongly collapsed.
     */
    internal fun coordinateOf(path: String): Artifact? {
        // Split rather than `Paths.get`: this reads the Maven DIRECTORY LAYOUT out of a path string, which is
        // segment arithmetic and never a filesystem question. Both separators, so a Windows-shaped path a
        // project persisted still parses.
        val segments = path.replace('\\', '/').trimEnd('/').split('/').filter { it.isNotEmpty() }
        if (segments.size < 3) return null
        val file = segments.last()
        if (!file.endsWith(".jar", ignoreCase = true)) return null
        val parentName = segments[segments.size - 2]
        // …/<version>/<name>-<version>-exploded/classes.jar, or …/<version>/<name>-<version>.jar
        val exploded = file.equals("classes.jar", ignoreCase = true) && parentName.endsWith("-exploded")
        val versionIndex = if (exploded) segments.size - 3 else segments.size - 2
        if (versionIndex < 1) return null
        val version = segments[versionIndex]
        val name = segments[versionIndex - 1]
        val artifactDir = segments.subList(0, versionIndex).joinToString("/", prefix = if (path.startsWith("/")) "/" else "")
        val parent = parentName
        // Confirm the Maven naming convention so an arbitrary `a/b/c.jar` tree isn't misread as a coordinate.
        val matchesConvention = if (file.equals("classes.jar", ignoreCase = true))
            parent.startsWith("$name-$version")
        else file.removeSuffix(".jar").startsWith("$name-$version")
        if (!matchesConvention) return null
        // Whatever follows `<name>-<version>` in the file name is the classifier (`-natives-arm64-v8a`), and
        // for an exploded AAR it follows it in the DIRECTORY name (`<name>-<version>[-cls]-exploded`).
        val stem = if (file.equals("classes.jar", ignoreCase = true))
            parent.removeSuffix("-exploded")
        else file.removeSuffix(".jar")
        val classifier = stem.removePrefix("$name-$version").removePrefix("-")
        return Artifact(artifactDir, version, classifier)
    }

    /** "newest wins" version comparison: numeric segments compared numerically, a numeric segment outranks a
     *  qualifier (`1.0` > `1.0-rc`), and the shorter release form outranks a longer qualifier suffix. */
    internal fun isNewer(a: String, b: String): Boolean {
        val pa = a.split('.', '-', '_'); val pb = b.split('.', '-', '_')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i); val y = pb.getOrNull(i)
            if (x == y) continue
            if (x == null) return y!!.toIntOrNull() == null   // a is the shorter (release) form; newer iff b's extra is a qualifier
            if (y == null) return x.toIntOrNull() != null     // a has an extra segment; newer iff it's numeric, older iff a qualifier
            val xi = x.toIntOrNull(); val yi = y.toIntOrNull()
            val cmp = when {
                xi != null && yi != null -> xi.compareTo(yi)
                xi != null -> 1                                // a numeric segment outranks b's qualifier
                yi != null -> -1
                else -> x.compareTo(y)
            }
            if (cmp != 0) return cmp > 0
        }
        return false
    }
}
