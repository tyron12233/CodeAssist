package dev.ide.model.impl

import dev.ide.model.ClasspathEntry
import dev.ide.model.ClasspathEntryKind
import dev.ide.model.MavenClasspath
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unit coverage for the shared classpath version-dedup used by both the build and the editor classpaths. */
class MavenClasspathTest {

    private fun entry(path: String, kind: ClasspathEntryKind = ClasspathEntryKind.LIBRARY) =
        ClasspathEntry(PathOnlyFile(path), kind)

    private fun paths(items: List<ClasspathEntry>) = MavenClasspath.resolveVersionConflicts(items).map { it.root.path }

    @Test
    fun keepsNewestVersionOfOneArtifact() {
        val base = "/cache/resolved-deps/androidx/collection/collection"
        val result = paths(listOf(entry("$base/1.1.0/collection-1.1.0.jar"), entry("$base/1.4.0/collection-1.4.0.jar")))
        assertEquals(listOf("$base/1.4.0/collection-1.4.0.jar"), result)
    }

    @Test
    fun dedupsAcrossPlainJarAndExplodedAar() {
        // An artifact can appear as a plain jar in one closure and an exploded-AAR classes.jar in another —
        // both carry the same artifact-dir key, so the older one is still dropped.
        val base = "/cache/resolved-deps/androidx/activity/activity"
        val result = paths(listOf(
            entry("$base/1.7.0/activity-1.7.0.jar"),
            entry("$base/1.9.3/activity-1.9.3-exploded/classes.jar"),
        ))
        assertEquals(listOf("$base/1.9.3/activity-1.9.3-exploded/classes.jar"), result)
    }

    @Test
    fun nonMavenPathsPassThroughUntouched() {
        // Module outputs / local jars / the SDK aren't Maven-shaped → never collapsed, even same file name.
        val items = listOf(
            entry("/project/app/build/classes", ClasspathEntryKind.MODULE_OUTPUT),
            entry("/sdk/android.jar", ClasspathEntryKind.SDK_BOOTCLASSPATH),
            entry("/libs/local.jar"),
        )
        assertEquals(items.map { it.root.path }, paths(items))
    }

    @Test
    fun distinctArtifactsAllSurvive() {
        val ui = "/cache/resolved-deps/androidx/compose/ui/ui/1.7.5/ui-1.7.5-exploded/classes.jar"
        val foundation = "/cache/resolved-deps/androidx/compose/foundation/foundation/1.7.5/foundation-1.7.5-exploded/classes.jar"
        val result = paths(listOf(entry(ui), entry(foundation)))
        assertTrue(ui in result && foundation in result, "different artifacts must both remain: $result")
    }

    // ---- dedupeForAndroidDex: dex-input dedup (bundled-vs-Maven; one artifact name, newest version wins) ----
    // KMP `-android`/`-jvm` collapse is no longer this layer's job — the resolver picks the right artifact
    // variant from Gradle Module Metadata, so the two are never both present here.

    private fun p(s: String) = java.nio.file.Paths.get(s)

    @Test
    fun dexDedupCollapsesBundledAndMavenStdlib() {
        // The IDE's bundled stdlib (a `.platform/` path the directory-keyed resolveVersionConflicts can't read)
        // vs a Maven-resolved stdlib. Same artifact → keep the newest.
        val bundled = p("/projects/quran-app/.platform/kotlin-stdlib-2.4.0.jar")
        val maven = p("/cache/resolved-deps/org/jetbrains/kotlin/kotlin-stdlib/2.2.0/kotlin-stdlib-2.2.0.jar")
        val result = MavenClasspath.dedupeForAndroidDex(listOf(maven, bundled)).map { it.toString() }
        assertEquals(listOf(bundled.toString()), result, "only the newest (2.4.0 bundled) stdlib survives")
    }

    @Test
    fun dexDedupKeepsUnversionedDirsAndDistinctArtifacts() {
        val classesJar = p("/cache/local-lib/classes.jar")              // not Maven-shaped → keep
        val moduleOut = p("/project/app/build/classes")                  // a dir → keep
        val coroutines = p("/cache/resolved-deps/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0/kotlinx-coroutines-core-jvm-1.8.0.jar")
        val result = MavenClasspath.dedupeForAndroidDex(listOf(classesJar, moduleOut, coroutines)).map { it.toString() }
        assertTrue(result.containsAll(listOf(classesJar, moduleOut, coroutines).map { it.toString() }),
            "unversioned/dir entries and a distinct artifact all survive: $result")
    }

    @Test
    fun dexDedupKeepsAndroidAndJvmOfDistinctModules() {
        // After GMM selection only one platform artifact of any ONE module reaches the dexer; a `-android`
        // and a `-jvm` of DIFFERENT modules are distinct artifacts and must both survive (no suffix folding).
        val android = p("/cache/resolved-deps/androidx/datastore/datastore-core-android/1.1.1/datastore-core-android-1.1.1-exploded/classes.jar")
        val jvm = p("/cache/resolved-deps/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0/kotlinx-coroutines-core-jvm-1.8.0.jar")
        val result = MavenClasspath.dedupeForAndroidDex(listOf(android, jvm)).map { it.toString() }
        assertEquals(listOf(android.toString(), jvm.toString()), result, "distinct-module -android and -jvm both kept")
    }

    @Test
    fun dexDedupDropsAMissingBundledStdlibForThePresentMavenOne() {
        // The reported Firebase crash: the bundled `.platform/kotlin-stdlib-2.4.0.jar` was ABSENT on disk (its
        // extraction failed), so newest-wins would supersede the project's real Maven stdlib and drop it from the
        // dex input — leaving kotlin-stdlib un-dexed (`NoClassDefFoundError: kotlin/collections/CollectionsKt` at
        // launch). A missing jar must not evict a present one: the real Maven stdlib survives.
        val dir = java.nio.file.Files.createTempDirectory("dex-dedup-missing")
        try {
            val bundledMissing = dir.resolve(".platform/kotlin-stdlib-2.4.0.jar")   // never created (extraction failed)
            val maven = dir.resolve("org/jetbrains/kotlin/kotlin-stdlib/1.9.24/kotlin-stdlib-1.9.24.jar")
            java.nio.file.Files.createDirectories(maven.parent)
            java.nio.file.Files.write(maven, ByteArray(0))
            val result = MavenClasspath.dedupeForAndroidDex(listOf(bundledMissing, maven)).map { it.toString() }
            assertEquals(
                listOf(maven.toString()), result,
                "the present Maven stdlib must survive; the missing (newer) bundled 2.4.0 must not evict it: $result",
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** A [VirtualFile] that only carries a [path] — all the dedup logic reads. */
    private class PathOnlyFile(override val path: String) : VirtualFile {
        override val name get() = path.substringAfterLast('/')
        override val isDirectory = false
        override val exists = true
        override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash(): ContentHash = ContentHash(path)
        override fun readBytes(): ByteArray = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
}
