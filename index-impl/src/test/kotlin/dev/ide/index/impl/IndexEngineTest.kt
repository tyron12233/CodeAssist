package dev.ide.index.impl

import dev.ide.index.ClassNameExternalizer
import dev.ide.index.ClassNameValue
import dev.ide.index.Externalizer
import dev.ide.index.Hit
import dev.ide.index.IndexExtension
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.InputFilter
import dev.ide.index.KeyDescriptor
import dev.ide.index.MatchingMode
import dev.ide.index.StringKeyDescriptor
import dev.ide.index.classEntryToFqn
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val CLASS = IndexId("test.classNames")
private val SRC = IndexId("test.sourceSymbols")

/** Exercises the generic engine: JDK enumeration + JPMS visibility filter, fuzzy, and cache reuse. */
class IndexEngineTest {

    /** A trivial class-name index over class-file entries (mirrors what the JDT backend does). */
    private object TestClassIndex : IndexExtension<String, ClassNameValue> {
        override val id = CLASS
        override val version = 1
        override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
        override val valueExternalizer: Externalizer<ClassNameValue> = ClassNameExternalizer
        override val matching = MatchingMode.PREFIX_AND_FUZZY
        override val inputFilter = InputFilter { it.unitName?.endsWith(".class") == true }
        override fun index(input: IndexInput): Map<String, Collection<ClassNameValue>> {
            val (fqn, simple) = classEntryToFqn(input.unitName!!) ?: return emptyMap()
            return mapOf(simple to listOf(ClassNameValue(fqn, input.origin, "class")))
        }
    }

    private fun service(cache: Path) = IndexServiceImpl(listOf(TestClassIndex), cache)
    private fun jdk() = Path.of(System.getProperty("java.home"))

    @Test
    fun indexesAccessibleJdkTypesAndExcludesInternals() {
        val cache = Files.createTempDirectory("idx")
        try {
            val svc = service(cache)
            runBlocking { svc.ensureUpToDate(IndexScope(jdkHome = jdk())) }
            val list = svc.prefix<ClassNameValue>(CLASS, "List", 200).map { it.value.fqn }.toList()
            assertTrue("java.util.List" in list, "expected java.util.List, got ${list.take(10)}")
            // JPMS visibility: jdk.internal.* is never exported, so it must not be indexed.
            val internals = svc.prefix<ClassNameValue>(CLASS, "L", 5000).count { it.value.fqn.startsWith("jdk.internal.") }
            assertTrue(internals == 0, "jdk.internal.* leaked into the index ($internals hits)")
        } finally {
            cache.toFile().deleteRecursively()
        }
    }

    /** A completed build records the per-indexer breakdown + aggregate stats on the terminal status, so the
     *  detail view + analytics can answer "which index took the time". */
    @Test
    fun buildRecordsPerIndexerBreakdownAndStats() {
        val cache = Files.createTempDirectory("idx")
        try {
            val svc = service(cache)
            runBlocking { svc.ensureUpToDate(IndexScope(jdkHome = jdk())) }
            val st = svc.status
            assertTrue(!st.building, "build should have finished")

            val stats = st.stats
            assertTrue(stats != null, "expected aggregate build stats on the terminal status")
            assertTrue(stats!!.artifacts >= 1, "expected at least the JRT artifact, got ${stats.artifacts}")
            // On a fresh cache every artifact is built (a cache miss), and built + reused always sums to total.
            assertEquals(stats.artifacts, stats.artifactsBuilt + stats.artifactsReused)
            assertTrue(stats.artifactsBuilt >= 1, "fresh cache should build the JRT, got ${stats.artifactsBuilt}")

            // The breakdown names our one indexer by its stable id and counts the entries it produced.
            val mine = st.breakdown.firstOrNull { it.id == CLASS.value }
            assertTrue(mine != null, "expected a breakdown entry for ${CLASS.value}, got ${st.breakdown.map { it.id }}")
            assertTrue(mine!!.entries > 0, "expected indexed entries recorded, got ${mine.entries}")
            assertTrue(mine.indexMs >= 0, "index time must be non-negative")
        } finally {
            cache.toFile().deleteRecursively()
        }
    }

    @Test
    fun fuzzyFindsArrayList() {
        val cache = Files.createTempDirectory("idx")
        try {
            val svc = service(cache)
            runBlocking { svc.ensureUpToDate(IndexScope(jdkHome = jdk())) }
            val hits = svc.fuzzy<ClassNameValue>(CLASS, "rray", 200).map { it.value.fqn }.toList()
            assertTrue(hits.any { it.endsWith(".ArrayList") }, "fuzzy 'rray' should surface ArrayList: ${hits.take(10)}")
        } finally {
            cache.toFile().deleteRecursively()
        }
    }

    @Test
    fun fuzzyFindsCamelHumpMatchesOverTheRealJdk() {
        val cache = Files.createTempDirectory("idx")
        try {
            val svc = service(cache)
            runBlocking { svc.ensureUpToDate(IndexScope(jdkHome = jdk())) }
            val npe = svc.fuzzy<ClassNameValue>(CLASS, "NPE", 200).map { it.value.fqn }.toList()
            assertTrue("java.lang.NullPointerException" in npe, "NPE should hump-match: ${npe.take(10)}")
            val aioobe = svc.fuzzy<ClassNameValue>(CLASS, "IOOBE", 200).map { it.value.fqn }.toList()
            assertTrue(
                "java.lang.IndexOutOfBoundsException" in aioobe,
                "IOOBE should hump-match: ${aioobe.take(10)}",
            )
        } finally {
            cache.toFile().deleteRecursively()
        }
    }

    @Test
    fun invalidateClearsBuiltDataAndAllowsRebuild() {
        val cache = Files.createTempDirectory("idx")
        try {
            val svc = service(cache)
            runBlocking { svc.ensureUpToDate(IndexScope(jdkHome = jdk())) }
            assertTrue(svc.prefix<ClassNameValue>(CLASS, "List", 50).any { it.value.fqn == "java.util.List" })

            runBlocking { svc.invalidate() }
            assertTrue(svc.prefix<ClassNameValue>(CLASS, "List", 50).none(), "invalidate must drop all built data")
            assertTrue(Files.list(cache).use { it.findAny().isEmpty }, "invalidate must clear the on-disk cache")

            runBlocking { svc.ensureUpToDate(IndexScope(jdkHome = jdk())) }
            assertTrue(svc.prefix<ClassNameValue>(CLASS, "List", 50).any { it.value.fqn == "java.util.List" }, "rebuild after invalidate")
        } finally {
            cache.toFile().deleteRecursively()
        }
    }

    @Test
    fun reusesPersistedCacheAcrossInstances() {
        val cache = Files.createTempDirectory("idx")
        try {
            runBlocking { service(cache).ensureUpToDate(IndexScope(jdkHome = jdk())) }
            val svc2 = service(cache)
            runBlocking { svc2.ensureUpToDate(IndexScope(jdkHome = jdk())) }
            assertTrue(svc2.prefix<ClassNameValue>(CLASS, "List", 200).any { it.value.fqn == "java.util.List" })
        } finally {
            cache.toFile().deleteRecursively()
        }
    }

    /**
     * One unreadable library jar must NOT abort the whole index build. On ART, `ZipFile` throws
     * `ZipException: No entries` opening a zero-entry jar (a resource-only AAR's empty classes.jar); a corrupt
     * jar throws on any JVM. Either way the build launches one coroutine per artifact under a `coroutineScope`,
     * so an uncaught throw would cancel every sibling and leave the index empty — exactly the device-only
     * "tiny/empty segments, no symbols" failure. The good jar must still index.
     */
    @Test
    fun oneUnreadableJarDoesNotAbortTheIndexBuild() {
        val cache = Files.createTempDirectory("idx")
        val libs = Files.createTempDirectory("idxlibs")
        try {
            val good = libs.resolve("good.jar")
            java.util.zip.ZipOutputStream(Files.newOutputStream(good)).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("com/foo/Bar.class")); z.write(byteArrayOf(1, 2)); z.closeEntry()
            }
            // Garbage bytes → ZipFile throws (the desktop-reproducible analog of ART's zero-entry "No entries").
            val bad = libs.resolve("bad.jar"); Files.write(bad, byteArrayOf(0, 1, 2, 3, 4))

            val svc = service(cache)
            // `bad` first, so without per-artifact isolation its throw would cancel `good`'s indexing.
            runBlocking { svc.ensureUpToDate(IndexScope(libraryJars = listOf(bad, good))) }
            val hits = svc.prefix<ClassNameValue>(CLASS, "Bar", 50).map { it.value.fqn }.toList()
            assertTrue("com.foo.Bar" in hits, "good jar must index despite a sibling unreadable jar; got $hits")
        } finally {
            cache.toFile().deleteRecursively(); libs.toFile().deleteRecursively()
        }
    }

    /** The static segment store is shared across projects, so a per-project invalidate() must drop only its
     *  OWN segments, not another project's segments living in the same content-addressed root. */
    @Test
    fun invalidateRemovesOnlyThisServicesSegmentsNotTheSharedRoot() {
        val cache = Files.createTempDirectory("idx")
        try {
            val svc = service(cache)
            runBlocking { svc.ensureUpToDate(IndexScope(jdkHome = jdk())) }
            // A segment another project put in the SHARED root (a different index id); invalidate must keep it.
            val foreign = cache.resolve("other.index").resolve("v1").resolve("foreign.seg")
            Files.createDirectories(foreign.parent); Files.write(foreign, byteArrayOf(1, 2, 3))

            runBlocking { svc.invalidate() }

            assertTrue(svc.prefix<ClassNameValue>(CLASS, "List", 50).none(), "invalidate must drop this service's built data")
            assertTrue(Files.exists(foreign), "invalidate must NOT delete another project's segment in the shared root")
            val mine = Files.walk(cache).use { s -> s.filter { it.toString().endsWith(".seg") && it != foreign }.toList() }
            assertTrue(mine.isEmpty(), "invalidate must remove this service's own segments, left: $mine")

            runBlocking { svc.ensureUpToDate(IndexScope(jdkHome = jdk())) }
            assertTrue(svc.prefix<ClassNameValue>(CLASS, "List", 50).any { it.value.fqn == "java.util.List" }, "rebuild after invalidate")
        } finally {
            cache.toFile().deleteRecursively()
        }
    }

    /**
     * Two AAR `classes.jar`s live at different paths but share the file NAME (every exploded AAR is a
     * `classes.jar`). When they also share size + mtime, the old `filename+size+mtime` content key collided,
     * so the second artifact reused the first's content-addressed segment and was NEVER indexed — its classes
     * then resolved nowhere (the JDT name env's authoritative "not found", which broke e.g. the
     * AppCompatActivity supertype chain across appcompat/fragment/activity/core AARs). The content key is now
     * path-aware, so two same-named jars index independently. Constrained ⇒ serial build, so a regression
     * collides deterministically rather than racing.
     */
    @Test
    fun sameNamedJarsAtDifferentPathsDoNotCollide() {
        val cache = Files.createTempDirectory("idx")
        val a = Files.createTempDirectory("aarA")
        val b = Files.createTempDirectory("aarB")
        try {
            // Equal entry-name length (15) + equal data length ⇒ STORED zips of identical byte size.
            val jarA = a.resolve("classes.jar").also { storedJar(it, "com/a/Foo.class", byteArrayOf(1, 2)) }
            val jarB = b.resolve("classes.jar").also { storedJar(it, "com/b/Bar.class", byteArrayOf(3, 4)) }
            assertEquals(Files.size(jarA), Files.size(jarB), "precondition: jars must be the same size to force the old collision")
            // ...and the same mtime: the final discriminator in the old key.
            val t = java.nio.file.attribute.FileTime.fromMillis(1_600_000_000_000L)
            Files.setLastModifiedTime(jarA, t); Files.setLastModifiedTime(jarB, t)

            // 1 MB cap ⇒ constrained ⇒ serial indexing, so a collision (if present) is deterministic.
            val svc = IndexServiceImpl(listOf(TestClassIndex), cache, blockCacheBytes = 1L * 1024 * 1024)
            runBlocking { svc.ensureUpToDate(IndexScope(libraryJars = listOf(jarA, jarB))) }

            val foo = svc.prefix<ClassNameValue>(CLASS, "Foo", 50).map { it.value.fqn }.toList()
            val bar = svc.prefix<ClassNameValue>(CLASS, "Bar", 50).map { it.value.fqn }.toList()
            assertTrue("com.a.Foo" in foo, "first classes.jar must index; got $foo")
            assertTrue("com.b.Bar" in bar, "second same-named classes.jar must ALSO index (no content-hash collision); got $bar")
        } finally {
            cache.toFile().deleteRecursively(); a.toFile().deleteRecursively(); b.toFile().deleteRecursively()
        }
    }

    /**
     * The index-status worklist labels an exploded-AAR `classes.jar` by its library directory (e.g.
     * `foo-1.2.3`), not the meaningless `classes.jar` file name every AAR shares. A plain jar keeps its name.
     */
    @Test
    fun explodedAarLabeledByLibraryNotClassesJar() {
        val cache = Files.createTempDirectory("idx")
        val aar = Files.createTempDirectory("aar")
        try {
            val exploded = Files.createDirectories(aar.resolve("foo-1.2.3-exploded"))
            val classesJar = exploded.resolve("classes.jar").also { storedJar(it, "com/foo/Foo.class", byteArrayOf(1, 2)) }
            val plainJar = aar.resolve("bar-2.0.jar").also { storedJar(it, "com/bar/Bar.class", byteArrayOf(3, 4)) }

            val svc = service(cache)
            val labels = LinkedHashSet<String>()
            svc.observeStatus { s -> s.items.forEach { labels.add(it.label) } }
            runBlocking { svc.ensureUpToDate(IndexScope(libraryJars = listOf(classesJar, plainJar))) }

            assertTrue("foo-1.2.3" in labels, "exploded AAR must show its library dir name; got $labels")
            assertTrue("classes.jar" !in labels, "the generic classes.jar name must not be shown; got $labels")
            assertTrue("bar-2.0.jar" in labels, "a plain jar keeps its file name; got $labels")
        } finally {
            cache.toFile().deleteRecursively(); aar.toFile().deleteRecursively()
        }
    }

    /** A class-name index that counts how many `.class` entries it actually indexes, so a test can assert a jar
     *  is (or isn't) re-indexed across "launches" (fresh service instances over a shared cache). */
    private class CountingClassIndex(val indexed: AtomicInteger) : IndexExtension<String, ClassNameValue> {
        override val id = CLASS
        override val version = 1
        override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
        override val valueExternalizer: Externalizer<ClassNameValue> = ClassNameExternalizer
        override val matching = MatchingMode.PREFIX_AND_FUZZY
        override val inputFilter = InputFilter { it.unitName?.endsWith(".class") == true }
        override fun index(input: IndexInput): Map<String, Collection<ClassNameValue>> {
            val (fqn, simple) = classEntryToFqn(input.unitName!!) ?: return emptyMap()
            indexed.incrementAndGet()
            return mapOf(simple to listOf(ClassNameValue(fqn, input.origin, "class")))
        }
    }

    private fun bumpMtime(p: Path) =
        Files.setLastModifiedTime(p, FileTime.fromMillis(Files.getLastModifiedTime(p).toMillis() + 10_000))

    /**
     * A bundled/SDK jar (android.jar, core-lambda-stubs) is re-extracted from app assets, so its on-disk mtime
     * is not stable across launches; the default `path+size+mtime` content key re-keyed it every launch and it
     * was re-indexed from scratch (android.jar ≈ 90% of all entries). A stable, path-free [IndexScope.stableJarIds]
     * id keys the segment by content identity, so it is reused across an mtime change — whereas the default key,
     * given the same mtime change, forces a full re-index.
     */
    @Test
    fun stableKeyedJarSurvivesMtimeChangeUnlikeDefaultKey() {
        val dir = Files.createTempDirectory("lib")
        val stableCache = Files.createTempDirectory("idxStable")
        val defaultCache = Files.createTempDirectory("idxDefault")
        try {
            val jar = dir.resolve("android.jar").also { storedJar(it, "com/x/Foo.class", byteArrayOf(1, 2)) }

            // Stable-keyed: a fresh-mtime "re-extract" between launches must NOT re-index.
            val stableHits = AtomicInteger(0)
            val stable = mapOf(jar to "android.jar-${Files.size(jar)}")
            runBlocking { IndexServiceImpl(listOf(CountingClassIndex(stableHits)), stableCache).ensureUpToDate(IndexScope(libraryJars = listOf(jar), stableJarIds = stable)) }
            assertEquals(1, stableHits.get(), "first build indexes the class")
            bumpMtime(jar)
            runBlocking { IndexServiceImpl(listOf(CountingClassIndex(stableHits)), stableCache).ensureUpToDate(IndexScope(libraryJars = listOf(jar), stableJarIds = stable)) }
            assertEquals(1, stableHits.get(), "stable-keyed jar must reuse its segment after an mtime change (not re-index)")

            // Default-keyed contrast: the same mtime change re-keys → re-index (the bug the stable id fixes).
            val defaultHits = AtomicInteger(0)
            runBlocking { IndexServiceImpl(listOf(CountingClassIndex(defaultHits)), defaultCache).ensureUpToDate(IndexScope(libraryJars = listOf(jar))) }
            bumpMtime(jar)
            runBlocking { IndexServiceImpl(listOf(CountingClassIndex(defaultHits)), defaultCache).ensureUpToDate(IndexScope(libraryJars = listOf(jar))) }
            assertEquals(2, defaultHits.get(), "default path+size+mtime key re-indexes on an mtime change")
        } finally {
            dir.toFile().deleteRecursively(); stableCache.toFile().deleteRecursively(); defaultCache.toFile().deleteRecursively()
        }
    }

    /** A source-symbol index over `.java` files that counts how many files it actually (re)parses, so a test
     *  can assert the engine re-indexes ONLY changed files across "launches" (fresh service instances). */
    private class CountingSourceIndex(val indexed: AtomicInteger) : IndexExtension<String, ClassNameValue> {
        override val id = SRC
        override val version = 1
        override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
        override val valueExternalizer: Externalizer<ClassNameValue> = ClassNameExternalizer
        override val matching = MatchingMode.PREFIX_AND_FUZZY
        override val inputFilter = InputFilter { it.unitName?.endsWith(".java") == true }
        override fun index(input: IndexInput): Map<String, Collection<ClassNameValue>> {
            indexed.incrementAndGet()
            val name = input.unitName!!.substringAfterLast('/').removeSuffix(".java")
            // Stash the interned file id in `kind` so a test can read it back and resolve it via filePath().
            return mapOf(name to listOf(ClassNameValue(name, input.origin, input.fileId.toString())))
        }
    }

    /**
     * The source side is rebuilt into RAM each launch, but with a persisted per-file cache (keyed by interned
     * file id) only files whose (size, mtime) changed are re-read + re-parsed; unchanged files reuse their
     * stored entries, and deleted files drop out. This is what stops a re-open from re-indexing the whole
     * project's source.
     */
    @Test
    fun reindexesOnlyChangedSourceFilesAcrossLaunches() {
        val cache = Files.createTempDirectory("idx")
        val srcCache = Files.createTempDirectory("idxsrc")
        val src = Files.createTempDirectory("src")
        try {
            val a = src.resolve("A.java"); Files.writeString(a, "class A {}")
            val b = src.resolve("B.java"); Files.writeString(b, "class B {}")
            val counter = AtomicInteger(0)
            val ext = CountingSourceIndex(counter)
            fun svc() = IndexServiceImpl(listOf(ext), cache, sourceCacheRoot = srcCache)
            val scope = IndexScope(sourceRoots = listOf(src))

            // Cold start: both files indexed.
            runBlocking { svc().ensureUpToDate(scope) }
            assertEquals(2, counter.get(), "cold start indexes both source files")

            // Relaunch, nothing changed: zero re-parses, both still queryable.
            counter.set(0)
            val s2 = svc()
            runBlocking { s2.ensureUpToDate(scope) }
            assertEquals(0, counter.get(), "unchanged relaunch must re-parse no source files")
            assertTrue(s2.prefix<ClassNameValue>(SRC, "A", 10).any { it.value.fqn == "A" }, "A reused from cache")
            assertTrue(s2.prefix<ClassNameValue>(SRC, "B", 10).any { it.value.fqn == "B" }, "B reused from cache")

            // Edit A (new content + bumped mtime): only A is re-parsed on the next launch.
            Files.writeString(a, "class A { int x; }")
            Files.setLastModifiedTime(a, FileTime.fromMillis(Files.getLastModifiedTime(a).toMillis() + 5000))
            counter.set(0)
            val s3 = svc()
            runBlocking { s3.ensureUpToDate(scope) }
            assertEquals(1, counter.get(), "only the edited file is re-parsed on relaunch")

            // Delete B: zero re-parses, and B drops out of the index.
            Files.delete(b)
            counter.set(0)
            val s4 = svc()
            runBlocking { s4.ensureUpToDate(scope) }
            assertEquals(0, counter.get(), "a deletion triggers no re-parse")
            assertTrue(s4.prefix<ClassNameValue>(SRC, "B", 10).none(), "deleted file's symbols must be dropped")
            assertTrue(s4.prefix<ClassNameValue>(SRC, "A", 10).any { it.value.fqn == "A" }, "surviving file stays indexed")
        } finally {
            cache.toFile().deleteRecursively(); srcCache.toFile().deleteRecursively(); src.toFile().deleteRecursively()
        }
    }

    /**
     * Source values reference a file by its interned id (paths held once in the table), so the id must (a)
     * resolve back to the right path and (b) stay STABLE across launches — otherwise a persisted value's
     * fileId would point at the wrong file after a restart.
     */
    @Test
    fun fileIdsAreStableAcrossLaunchesAndResolveToPaths() {
        val cache = Files.createTempDirectory("idx")
        val srcCache = Files.createTempDirectory("idxsrc")
        val src = Files.createTempDirectory("src")
        try {
            val a = src.resolve("A.java"); Files.writeString(a, "class A {}")
            val b = src.resolve("B.java"); Files.writeString(b, "class B {}")
            val ext = CountingSourceIndex(AtomicInteger(0))
            fun svc() = IndexServiceImpl(listOf(ext), cache, sourceCacheRoot = srcCache)
            val scope = IndexScope(sourceRoots = listOf(src))

            val s1 = svc(); runBlocking { s1.ensureUpToDate(scope) }
            val idA = s1.prefix<ClassNameValue>(SRC, "A", 10).first().value.kind.toInt()
            val idB = s1.prefix<ClassNameValue>(SRC, "B", 10).first().value.kind.toInt()
            assertEquals(a.toString(), s1.filePath(idA), "fileId must resolve to A's path")
            assertEquals(b.toString(), s1.filePath(idB), "fileId must resolve to B's path")

            // Relaunch (fresh service over the same persisted cache): ids must be identical and still resolve,
            // and the value loaded straight off disk must carry the same id.
            val s2 = svc(); runBlocking { s2.ensureUpToDate(scope) }
            assertEquals(idA, s2.prefix<ClassNameValue>(SRC, "A", 10).first().value.kind.toInt(), "A keeps its id across launches")
            assertEquals(idB, s2.prefix<ClassNameValue>(SRC, "B", 10).first().value.kind.toInt(), "B keeps its id across launches")
            assertEquals(a.toString(), s2.filePath(idA), "persisted fileId still resolves after relaunch")
        } finally {
            cache.toFile().deleteRecursively(); srcCache.toFile().deleteRecursively(); src.toFile().deleteRecursively()
        }
    }

    /**
     * The source side is updated INCREMENTALLY per file: a save replaces only the edited file's entries, so
     * the in-memory work is O(edited file), not O(whole project). The old design cleared and re-added every
     * entry of every file on each save, so its cost grew with project size. [debugSourceAddOps] counts entries
     * appended to the source index; editing one file (which yields one entry under [CountingSourceIndex]) must
     * bump it by exactly 1 — for a 4-file project and a 400-file project alike.
     */
    @Test
    fun reindexSourceCostIsIndependentOfProjectSize() {
        fun addOpsForOneEdit(n: Int): Long {
            val cache = Files.createTempDirectory("idx")
            val srcCache = Files.createTempDirectory("idxsrc")
            val src = Files.createTempDirectory("src")
            try {
                for (i in 0 until n) Files.writeString(src.resolve("F$i.java"), "class F$i {}")
                val svc = IndexServiceImpl(listOf(CountingSourceIndex(AtomicInteger(0))), cache, sourceCacheRoot = srcCache)
                runBlocking { svc.ensureUpToDate(IndexScope(sourceRoots = listOf(src))) }
                // All N files must be queryable after the cold build.
                assertTrue(svc.prefix<ClassNameValue>(SRC, "F0", 10).any { it.value.fqn == "F0" }, "cold build indexes F0")

                val before = svc.debugSourceAddOps()
                val f0 = src.resolve("F0.java")
                runBlocking { svc.reindexSource(f0, "class F0 { int x; }") }
                val delta = svc.debugSourceAddOps() - before

                // The edited file stays queryable and the rest are untouched.
                assertTrue(svc.prefix<ClassNameValue>(SRC, "F0", 10).any { it.value.fqn == "F0" }, "edited file still indexed")
                if (n > 1) assertTrue(svc.prefix<ClassNameValue>(SRC, "F${n - 1}", 10).any { it.value.fqn == "F${n - 1}" }, "untouched file still indexed")
                return delta
            } finally {
                cache.toFile().deleteRecursively(); srcCache.toFile().deleteRecursively(); src.toFile().deleteRecursively()
            }
        }

        assertEquals(1L, addOpsForOneEdit(4), "a one-file edit re-adds only that file's entry (small project)")
        assertEquals(1L, addOpsForOneEdit(400), "reindex cost must NOT scale with project size (large project)")
    }

    /**
     * The bounded top-K merge ([rankMerged]) that [IndexServiceImpl.query] uses to fold the per-source hits
     * must produce EXACTLY what the straightforward `sortedByDescending { score }.distinctBy { value }
     * .take(limit)` would — same survivors, same representative per value, same order, including ties — just
     * without sorting the whole over-fetched union. The quality baseline exercises that reference expression
     * directly (not `query`), so this is what guards the optimized path. Unique keys per occurrence catch a
     * wrong-representative pick; a small score range forces many ties.
     */
    @Test
    fun rankMergedMatchesSortDistinctTakeSemantics() {
        val rnd = kotlin.random.Random(1234)
        repeat(500) { iter ->
            val n = rnd.nextInt(0, 80)
            val hits = ArrayList<Hit<Any>>(n)
            for (j in 0 until n) {
                val value: Any = "v${rnd.nextInt(0, 15)}"   // duplicate values exercise the dedup
                val score = rnd.nextInt(0, 6)               // small range → many score ties
                hits.add(Hit("k$j", value, score))          // unique key per occurrence reveals a wrong pick
            }
            val limit = rnd.nextInt(1, 20)
            val expected = hits.sortedByDescending { it.score }.distinctBy { it.value }.take(limit)
                .map { Triple(it.key, it.value, it.score) }
            val actual = rankMerged(hits, limit).map { Triple(it.key, it.value, it.score) }
            assertEquals(expected, actual, "rankMerged diverged (iter=$iter, limit=$limit) for $hits")
        }
        assertTrue(rankMerged(listOf(Hit("k", "v" as Any, 5)), 0).isEmpty(), "limit 0 → empty")
        assertTrue(rankMerged(emptyList(), 10).isEmpty(), "empty input → empty")
    }

    /** A single-entry STORED (uncompressed) jar: file size is a deterministic function of the entry-name
     *  length + data length, so two such jars can be forced to an identical size (and mtime) on disk. */
    private fun storedJar(path: Path, entryName: String, data: ByteArray) {
        java.util.zip.ZipOutputStream(Files.newOutputStream(path)).use { z ->
            val e = java.util.zip.ZipEntry(entryName).apply {
                method = java.util.zip.ZipEntry.STORED
                size = data.size.toLong(); compressedSize = data.size.toLong()
                crc = java.util.zip.CRC32().apply { update(data) }.value
                time = 1_600_000_000_000L // fixed, so both jars differ only in name/data bytes, not size
            }
            z.putNextEntry(e); z.write(data); z.closeEntry()
        }
    }
}
