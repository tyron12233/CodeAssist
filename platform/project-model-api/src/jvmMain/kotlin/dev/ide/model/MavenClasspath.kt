package dev.ide.model

/**
 * Dex-input dedupe, which is the half of [MavenClasspath] that is typed by a real filesystem.
 *
 * The version-conflict rule itself is common code; this is the Android-specific application of it, and it
 * takes and returns `java.nio.file.Path` because that is what the build hands it.
 */
/**
 * Dedupe a raw jar list for an Android **dex** input: at most one jar per artifact, so D8 never sees a
 * class twice ("Type … is defined multiple times" — a fatal dex error). For one artifact present at two
 * versions reached by unlike paths — the IDE's bundled `kotlin-stdlib-2.4.0.jar` (a non-Maven `.platform/…`
 * path) vs a Maven `kotlin-stdlib-2.2.0.jar` — the newest version wins. (Kotlin-Multiplatform `-android`
 * vs `-jvm` collisions used to be collapsed here too, but the resolver now selects the right artifact
 * variant from Gradle Module Metadata up front, so only one is ever present.)
 *
 * The coordinate is read from the Maven directory layout, so it works for plain jars AND exploded-AAR
 * `classes.jar`s, and it keeps the GROUP: `androidx.core:core` and `io.noties.markwon:core` are both a
 * `core` artifact, yet they are different libraries and both must survive. A non-Maven path (the bundled
 * stdlib) only has a file name, so it competes with the Maven artifact of that name when exactly one group
 * publishes it and the two jars define a class in common. Paths with neither (module output dirs) carry no coordinate and pass through.
 */
fun dedupeJarsForAndroidDex(jars: List<java.nio.file.Path>): List<java.nio.file.Path> {
    val coordinates = jars.map { dexCoordinate(it) }
    // A bare file name (`kotlin-stdlib`) joins the slot of the one Maven artifact with that name. When two
    // groups publish the name, which one it duplicates is unknowable, so it keeps a slot of its own.
    val mavenKeysByName = HashMap<String, MutableSet<String>>()
    val mavenJarsByKey = HashMap<String, MutableList<java.nio.file.Path>>()
    jars.zip(coordinates) { path, c ->
        if (c is DexCoordinate.Maven) {
            mavenKeysByName.getOrPut(c.name) { HashSet() }.add(c.key)
            mavenJarsByKey.getOrPut(c.key) { ArrayList() }.add(path)
        }
    }
    val parsed = jars.zip(coordinates) { path, c ->
        when (c) {
            null -> ParsedJar(path, null, null)
            is DexCoordinate.Maven -> ParsedJar(path, c.key, c.version)
            is DexCoordinate.FileName -> {
                // A matching NAME is not yet the same library: a local `libs/core-1.0.jar` and Maven
                // `io.noties.markwon:core` share only the word. Join the Maven slot only when the jars could
                // collide in the dex, i.e. they define a class in common (or one can't be read to tell).
                val key = mavenKeysByName[c.name]?.singleOrNull()
                    ?.takeIf { k -> mavenJarsByKey[k].orEmpty().any { mayShareClasses(path, it) } }
                ParsedJar(path, key ?: c.name, c.version)
            }
        }
    }
    // Per artifact (keyed by group and name, no platform-suffix folding), the newest version wins — but
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
        fun bump(m: HashMap<String, String>) { val cur = m[base]; if (cur == null || MavenClasspath.isNewer(v, cur)) m[base] = v }
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

/** Whether [a] and [b] define a class in common. True when either can't be read (missing, empty, not a
 *  zip): then nothing rules the collision out, and the name match stands, as it always did. */
private fun mayShareClasses(a: java.nio.file.Path, b: java.nio.file.Path): Boolean {
    val ca = classEntries(a) ?: return true
    val cb = classEntries(b) ?: return true
    return ca.any { it in cb }
}

private fun classEntries(jar: java.nio.file.Path): Set<String>? = runCatching {
    java.util.zip.ZipFile(jar.toFile()).use { zf ->
        zf.entries().asSequence().map { it.name }
            .filter { it.endsWith(".class") && !it.startsWith("META-INF/") && it != "module-info.class" }
            .toHashSet()
    }
}.getOrNull()?.takeIf { it.isNotEmpty() }

private class ParsedJar(val path: java.nio.file.Path, val base: String?, val version: String?)

private sealed class DexCoordinate(val name: String, val version: String) {
    /** A Maven-layout jar. [key] is the artifact's `group/name` (classifier included), unique per library. */
    class Maven(val key: String, name: String, version: String) : DexCoordinate(name, version)
    /** A jar outside the Maven layout, known only by the `<name>-<version>.jar` in its file name. */
    class FileName(name: String, version: String) : DexCoordinate(name, version)
}

/** The coordinate of a dex input: Maven layout first (plain jar OR exploded AAR), else the file name. The
 *  key carries the classifier, so two secondary artifacts of one module don't share a dex slot. */
private fun dexCoordinate(path: java.nio.file.Path): DexCoordinate? {
    MavenClasspath.coordinateOf(path.toString())?.let { c ->
        val dir = c.artifactKey.replace('\\', '/')
        val name = dir.substringAfterLast('/')
        // The resolver's cache is `<root>/.platform/caches/resolved-deps/<group/as/path>/<name>`, so the part
        // after the marker is exactly `group/name`, whichever cache root the jar came from. Anywhere else the
        // whole artifact directory stands in for it.
        val ga = dir.substringAfter("/$RESOLVED_DEPS/", missingDelimiterValue = dir)
        return DexCoordinate.Maven(if (c.classifier.isEmpty()) ga else "$ga|${c.classifier}", name, c.version)
    }
    val file = path.fileName?.toString() ?: return null
    if (!file.endsWith(".jar", ignoreCase = true)) return null
    return FILE_NAME_VERSION.matchEntire(file)?.let { DexCoordinate.FileName(it.groupValues[1], it.groupValues[2]) }
}

private const val RESOLVED_DEPS = "resolved-deps"

// <name>-<version>.jar with a digit-led version (e.g. `kotlin-stdlib-2.4.0`, `core-jvm-1.8.0-alpha01`).
private val FILE_NAME_VERSION = Regex("""(.+?)-(\d[A-Za-z0-9.]*(?:-[A-Za-z0-9.]+)*)\.jar""", RegexOption.IGNORE_CASE)
