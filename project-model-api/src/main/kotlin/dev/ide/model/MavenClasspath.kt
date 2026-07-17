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
     *  across versions of one `group:name`), [version] its per-version child — the inputs to "newest wins". */
    private class Artifact(val artifactKey: String, val version: String)

    /**
     * Dedupe a raw jar list for an Android **dex** input: at most one jar per artifact, so D8 never sees a
     * class twice ("Type … is defined multiple times" — a fatal dex error). For one artifact present at two
     * versions reached by unlike paths — the IDE's bundled `kotlin-stdlib-2.4.0.jar` (a non-Maven `.platform/…`
     * path) vs a Maven `kotlin-stdlib-2.2.0.jar` — the newest version wins. (Kotlin-Multiplatform `-android`
     * vs `-jvm` collisions used to be collapsed here too, but the resolver now selects the right artifact
     * variant from Gradle Module Metadata up front, so only one is ever present.)
     *
     * The coordinate (artifact name + version) is read from the Maven directory layout — so it works for plain
     * jars AND exploded-AAR `classes.jar`s — falling back to the file name for non-Maven paths (the bundled
     * stdlib). Paths with neither (module output dirs) carry no coordinate and pass through.
     */
    fun dedupeForAndroidDex(jars: List<java.nio.file.Path>): List<java.nio.file.Path> {
        val parsed = jars.map { path -> val c = dexCoordinate(path); ParsedJar(path, c?.first, c?.second) }
        // Per artifact (keyed by its exact name, no platform-suffix folding), the newest version wins — but
        // preferring jars that EXIST on disk. A missing jar contributes no classes to the dex, so it must never
        // win an artifact's dex slot and evict a real, present version: the IDE's bundled
        // `.platform/kotlin-stdlib-<v>.jar` failing to extract would otherwise supersede the project's real
        // Maven `kotlin-stdlib` (newest-wins), leaving kotlin-stdlib un-dexed — the runtime
        // `NoClassDefFoundError: kotlin/collections/CollectionsKt` on launch (Firebase init). Only when NO
        // version of an artifact is present does the newest (still-missing) one win, preserving prior behavior.
        val winnerExisting = HashMap<String, String>()
        val winnerAny = HashMap<String, String>()
        for (j in parsed) {
            val base = j.base ?: continue
            val v = j.version!!
            fun bump(m: HashMap<String, String>) { val cur = m[base]; if (cur == null || isNewer(v, cur)) m[base] = v }
            bump(winnerAny)
            if (java.nio.file.Files.exists(j.path)) bump(winnerExisting)
        }
        val emitted = HashSet<String>()
        val out = ArrayList<java.nio.file.Path>(jars.size)
        for (j in parsed) {
            if (j.base == null) { out.add(j.path); continue }             // no coordinate (dir / odd path) → keep
            val win = winnerExisting[j.base] ?: winnerAny[j.base] ?: continue
            if (j.version != win) continue                                // a superseded (or missing) version → drop
            if (emitted.add(j.base)) out.add(j.path)                     // the winner; first path of it wins
        }
        return out
    }

    private class ParsedJar(val path: java.nio.file.Path, val base: String?, val version: String?)

    /** Artifact name + version for a dex input: Maven layout first (plain jar OR exploded AAR), else file name. */
    private fun dexCoordinate(path: java.nio.file.Path): Pair<String, String>? {
        coordinateOf(path.toString())?.let { c ->
            val name = java.nio.file.Paths.get(c.artifactKey).fileName?.toString()
            if (name != null) return name to c.version
        }
        val file = path.fileName?.toString() ?: return null
        if (!file.endsWith(".jar", ignoreCase = true)) return null
        return FILE_NAME_VERSION.matchEntire(file)?.let { it.groupValues[1] to it.groupValues[2] }
    }

    // <name>-<version>.jar with a digit-led version (e.g. `kotlin-stdlib-2.4.0`, `core-jvm-1.8.0-alpha01`).
    private val FILE_NAME_VERSION = Regex("""(.+?)-(\d[A-Za-z0-9.]*(?:-[A-Za-z0-9.]+)*)\.jar""", RegexOption.IGNORE_CASE)

    fun resolveVersionConflicts(items: List<ClasspathEntry>): List<ClasspathEntry> {
        val winner = HashMap<String, String>()
        for (e in items) {
            val c = coordinateOf(e.root.path) ?: continue
            val cur = winner[c.artifactKey]
            if (cur == null || isNewer(c.version, cur)) winner[c.artifactKey] = c.version
        }
        val out = LinkedHashMap<String, ClasspathEntry>()
        for (e in items) {
            val c = coordinateOf(e.root.path)
            if (c != null && winner[c.artifactKey] != c.version) continue   // a superseded version of this artifact — drop it
            out.putIfAbsent("${e.kind.name}|${e.root.path}", e)
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
    private fun coordinateOf(path: String): Artifact? {
        val p = runCatching { java.nio.file.Paths.get(path) }.getOrNull() ?: return null
        val file = p.fileName?.toString() ?: return null
        if (!file.endsWith(".jar", ignoreCase = true)) return null
        val parent = p.parent ?: return null
        val versionDir: java.nio.file.Path =
            if (file.equals("classes.jar", ignoreCase = true) && (parent.fileName?.toString()?.endsWith("-exploded") == true))
                parent.parent ?: return null   // …/<version>/<name>-<version>-exploded/classes.jar
            else parent                          // …/<version>/<name>-<version>.jar
        val artifactDir = versionDir.parent ?: return null
        val version = versionDir.fileName?.toString() ?: return null
        val name = artifactDir.fileName?.toString() ?: return null
        // Confirm the Maven naming convention so an arbitrary `a/b/c.jar` tree isn't misread as a coordinate.
        val matchesConvention = if (file.equals("classes.jar", ignoreCase = true))
            parent.fileName?.toString() == "$name-$version-exploded"
        else file.removeSuffix(".jar").startsWith("$name-$version")
        if (!matchesConvention) return null
        return Artifact(artifactDir.toString(), version)
    }

    /** "newest wins" version comparison: numeric segments compared numerically, a numeric segment outranks a
     *  qualifier (`1.0` > `1.0-rc`), and the shorter release form outranks a longer qualifier suffix. */
    private fun isNewer(a: String, b: String): Boolean {
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
