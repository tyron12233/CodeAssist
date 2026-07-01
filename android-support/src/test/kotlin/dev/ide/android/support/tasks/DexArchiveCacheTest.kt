package dev.ide.android.support.tasks

import dev.ide.android.support.tools.Dexer
import dev.ide.android.support.tools.MergePlan
import dev.ide.android.support.tools.OffHeapArchiveDexer
import dev.ide.android.support.tools.ToolResult
import dev.ide.build.TaskName
import dev.ide.build.engine.SimpleTaskContext
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Mimic D8 `--file-per-class-file`: write one per-class `.dex` for each `.class` in [inputs], named by its
 *  class path (`a/B.class` -> `a/B.dex`) — so [DexArchiveBuilderTask]'s completeness check (which verifies a
 *  bucket has a dex for every class in the jar) sees a realistic, complete archive. [omit] drops one class to
 *  simulate a dexer that silently loses it. */
private fun writePerClassDexes(inputs: List<Path>, outDir: Path, omit: String? = null) {
    Files.createDirectories(outDir)
    for (jar in inputs.filter { Files.exists(it) }) {
        ZipFile(jar.toFile()).use { zf ->
            zf.entries().asSequence().filter { it.name.endsWith(".class") && it.name != omit }.forEach { e ->
                val dex = outDir.resolve(e.name.removeSuffix(".class") + ".dex")
                dex.parent?.let { Files.createDirectories(it) }
                Files.write(dex, byteArrayOf(1))
            }
        }
    }
}

/**
 * The shared cross-project dex cache: a library jar is dexed once, then reused by copy in any other project
 * (or after a clean) instead of being re-dexed. Uses a fake [Dexer] that only counts invocations and writes
 * per-class `.dex` files (like real D8 `--file-per-class-file`), so the test exercises the caching/parallel/
 * completeness logic in [DexArchiveBuilderTask] offline (no real D8).
 */
class DexArchiveCacheTest {

    /** Counts dexArchive calls and writes a per-class `.dex` for each input class (so the bucket is complete). */
    private class CountingDexer : Dexer {
        val archiveCalls = AtomicInteger(0)
        override fun dex(inputs: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int, desugaredLibConfig: Path?): ToolResult {
            Files.createDirectories(outDir); Files.write(outDir.resolve("classes.dex"), byteArrayOf(1)); return ToolResult.ok(emptyList())
        }
        override fun dexArchive(inputs: List<Path>, classpath: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int, desugaredLibConfig: Path?): ToolResult {
            archiveCalls.incrementAndGet(); writePerClassDexes(inputs, outDir); return ToolResult.ok(emptyList())
        }
    }

    private fun jar(dir: Path, name: String, vararg entries: String): Path {
        val p = dir.resolve(name)
        ZipOutputStream(Files.newOutputStream(p)).use { z ->
            for (entry in entries) { z.putNextEntry(ZipEntry(entry)); z.write(entry.toByteArray()); z.closeEntry() }
        }
        return p
    }

    /**
     * Mimics D8's desugaring-classpath contract: it aborts with "Classpath type already present" when two
     * classpath entries define the same class. A dexer that enforces the same rule turns the regression into a
     * test failure offline (no real D8 needed).
     */
    private class ClasspathCheckingDexer : Dexer {
        override fun dex(inputs: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int, desugaredLibConfig: Path?): ToolResult {
            Files.createDirectories(outDir); Files.write(outDir.resolve("classes.dex"), byteArrayOf(1)); return ToolResult.ok(emptyList())
        }
        override fun dexArchive(inputs: List<Path>, classpath: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int, desugaredLibConfig: Path?): ToolResult {
            val seen = HashSet<String>()
            for (cp in classpath.filter { Files.exists(it) }) {
                ZipFile(cp.toFile()).use { zf ->
                    val e = zf.entries()
                    while (e.hasMoreElements()) {
                        val name = e.nextElement().name
                        if (name.endsWith(".class") && !seen.add(name)) return ToolResult.fail("Classpath type already present: $name")
                    }
                }
            }
            writePerClassDexes(inputs, outDir); return ToolResult.ok(emptyList())
        }
    }

    private fun buildTask(name: String, externalJars: List<Path>, extRoot: Path, dexer: Dexer, cache: Path, tmp: Path, rJars: List<Path> = emptyList(), rRoot: Path = tmp.resolve("r")) =
        DexArchiveBuilderTask(
            TaskName(name), projectClasses = emptyList(), subProjectJars = emptyList(), externalJars = externalJars,
            androidJar = tmp.resolve("android.jar"), minApi = 21, release = false,
            stagingJar = tmp.resolve("staging/project.jar"),
            projectDexRoot = tmp.resolve("proj"), subDexRoot = tmp.resolve("sub"), extDexRoot = extRoot,
            dexer = dexer, dexCacheRoot = cache, rJars = rJars, rDexRoot = rRoot,
        )

    @Test
    fun sharedCacheDexesEachLibraryOnceAcrossProjects() = runBlocking {
        val tmp = Files.createTempDirectory("dexcache")
        try {
            val cache = tmp.resolve("shared-dex")
            val libs = tmp.resolve("libs")
            Files.createDirectories(libs)
            val jars = listOf(jar(libs, "a.jar", "a/A.class"), jar(libs, "b.jar", "b/B.class"))

            // Project A, cold: both libraries dex once and seed the shared cache.
            val dexerA = CountingDexer()
            buildTask(":a:dexBuilder", jars, tmp.resolve("a/ext"), dexerA, cache, tmp.resolve("a")).execute(SimpleTaskContext())
            assertEquals(2, dexerA.archiveCalls.get(), "cold build dexes every library once")

            // Project B (fresh per-project dirs) with the SAME jars + shared cache: pure cache hits, no dexing.
            val dexerB = CountingDexer()
            val bExt = tmp.resolve("b/ext")
            buildTask(":b:dexBuilder", jars, bExt, dexerB, cache, tmp.resolve("b")).execute(SimpleTaskContext())
            assertEquals(0, dexerB.archiveCalls.get(), "warm shared cache copies instead of re-dexing")
            // B's own ext archives are populated (from the copy), so its merge has real input.
            assertTrue(DexArchivesProbe.hasDexUnder(bExt), "project B's ext buckets were populated from the cache")

            // Project A again, same per-project dirs: tier-1 reuse (module buckets already present) — also no dexing.
            val dexerA2 = CountingDexer()
            buildTask(":a:dexBuilder", jars, tmp.resolve("a/ext"), dexerA2, cache, tmp.resolve("a")).execute(SimpleTaskContext())
            assertEquals(0, dexerA2.archiveCalls.get(), "unchanged module buckets are reused without dexing")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /**
     * [SharedLibraryDexer.contentHashes] is content-based, not mtime-based. This is what lets the real-view
     * layout preview reuse its merged `classes*.dex` across builds: the build regenerates `R.jar` and the
     * module-output jars with a fresh mtime every time, and an mtime signature flipped every build and forced a
     * full re-merge even when the jars were byte-identical. The hash must survive an mtime-only rebuild and
     * change only on a real content change.
     */
    @Test
    fun contentHashesSurviveMtimeOnlyRebuildAndFlipOnContentChange() {
        val tmp = Files.createTempDirectory("chash")
        try {
            val cacheDir = tmp.resolve("hashcache")
            val lib = jar(tmp, "lib.jar", "a/B.class", "a/C.class")
            val h1 = SharedLibraryDexer.contentHashes(listOf(lib), cacheDir)[lib]

            // Rebuild produces a byte-identical jar but a fresh mtime (what R.jar / module outputs do).
            Files.setLastModifiedTime(lib, FileTime.fromMillis(Files.getLastModifiedTime(lib).toMillis() + 60_000))
            val h2 = SharedLibraryDexer.contentHashes(listOf(lib), cacheDir)[lib]
            assertEquals(h1, h2, "an mtime-only rebuild must not change the content hash")

            // A real content change (a different class set) must change the hash — the size differs, so the
            // (path,size,mtime) sidecar can't serve a stale hit regardless of mtime.
            jar(tmp, "lib.jar", "a/B.class", "a/C.class", "a/D.class")
            val h3 = SharedLibraryDexer.contentHashes(listOf(lib), cacheDir)[lib]
            assertTrue(h3 != null && h3 != h1, "a content change must change the content hash")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /**
     * A resource edit changes the app's `R.jar` but leaves every dependency library untouched. The `R.jar` must
     * re-dex, but it must NOT bust the shared library-dex cache — regression for the material-project case where
     * folding `R.jar`'s content hash into the desugaring digest re-keyed the whole cache and re-dexed all ~50
     * libraries on every resource change. AGP-faithful fix: `R.jar` is dexed in its OWN scope (`rDexRoot`), kept
     * out of the external desugaring universe/digest, so the external library cache is stable across resource edits.
     */
    @Test
    fun changingRJarDoesNotReDexStableLibraries() = runBlocking {
        val tmp = Files.createTempDirectory("dexRcache")
        try {
            val cache = tmp.resolve("shared-dex")
            val libs = tmp.resolve("libs"); Files.createDirectories(libs)
            // A material-style set: several stable library jars + the app's volatile R.jar.
            val libJars = listOf(
                jar(libs, "material.jar", "com/google/android/material/button/MaterialButton.class"),
                jar(libs, "appcompat.jar", "androidx/appcompat/app/AppCompatActivity.class"),
                jar(libs, "core.jar", "androidx/core/view/ViewCompat.class"),
            )
            val rJar = jar(libs, "R.jar", "com/example/app/R.class", "com/example/app/R\$string.class")

            // Cold build: every library + R.jar dexes once, seeding the shared cache.
            val cold = CountingDexer()
            val coldExt = tmp.resolve("app/ext"); val coldR = tmp.resolve("app/r")
            buildTask(":app:dexBuilder", libJars, coldExt, cold, cache, tmp.resolve("app"), rJars = listOf(rJar), rRoot = coldR)
                .execute(SimpleTaskContext())
            assertEquals(4, cold.archiveCalls.get(), "cold build dexes each library and R.jar once")
            // R.jar dexes into its OWN root, never the external scope (so mergeExtDex stays stable across edits).
            assertTrue(DexArchivesProbe.hasDexUnder(coldR), "R.jar dexes into its own scope")

            // A resource edit: R.jar's content changes (ids shifted), the libraries are byte-identical. Fresh
            // per-project dirs (as after a clean) so ONLY the shared cache — not the module buckets — can save the
            // libraries from re-dexing.
            ZipOutputStream(Files.newOutputStream(rJar)).use { z ->
                z.putNextEntry(ZipEntry("com/example/app/R.class")); z.write("v2".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("com/example/app/R\$string.class")); z.write("v2-strings".toByteArray()); z.closeEntry()
            }
            val warm = CountingDexer()
            buildTask(":app:dexBuilder", libJars, tmp.resolve("app2/ext"), warm, cache, tmp.resolve("app2"), rJars = listOf(rJar), rRoot = tmp.resolve("app2/r"))
                .execute(SimpleTaskContext())
            assertEquals(1, warm.archiveCalls.get(), "a resource edit re-dexes only R.jar; the stable libraries hit the shared cache")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun duplicateLibraryOnClasspathDoesNotCrashDexing() = runBlocking {
        val tmp = Files.createTempDirectory("dexdup")
        try {
            val libs = tmp.resolve("libs"); Files.createDirectories(libs)
            // Same artifact via two paths (byte-identical → same content hash), e.g. two resolver cache paths.
            val dupA = jar(libs, "stdlib-a.jar", "kotlin/sequences/SequencesKt.class")
            val dupB = libs.resolve("stdlib-b.jar"); Files.copy(dupA, dupB)
            // Two DISTINCT jars (different content) that both define the same type — e.g. two kotlin-stdlib
            // versions, or a library bundling stdlib classes. Content-hash dedup can't catch this; class-level
            // dedup must. Give them an extra differing entry so their content hashes differ.
            val verA = jar(libs, "stdlib-1.8.jar", "kotlin/jvm/internal/Intrinsics.class").also { Files.write(libs.resolve("a.txt"), byteArrayOf(1)) }
            val verB = libs.resolve("stdlib-1.9.jar")
            ZipOutputStream(Files.newOutputStream(verB)).use { z ->
                z.putNextEntry(ZipEntry("kotlin/jvm/internal/Intrinsics.class")); z.write("v9".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("kotlin/Unit.class")); z.write("unit".toByteArray()); z.closeEntry()
            }
            val other = jar(libs, "other.jar", "o/O.class")

            val dexer = ClasspathCheckingDexer()
            val extRoot = tmp.resolve("ext")
            val result = buildTask(":x:dexBuilder", listOf(dupA, dupB, verA, verB, other), extRoot, dexer, tmp.resolve("cache"), tmp.resolve("x"))
                .execute(SimpleTaskContext())
            assertEquals(dev.ide.build.TaskResult.Success, result, "class-level dedup must keep duplicate types off the classpath")
            assertTrue(DexArchivesProbe.hasDexUnder(extRoot), "the deduped library set still produces dex output")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun classFreeLibraryJarsAreSkippedNotDexedOrCrashed() = runBlocking {
        val tmp = Files.createTempDirectory("dexempty")
        try {
            val libs = tmp.resolve("libs"); Files.createDirectories(libs)
            val normal = jar(libs, "real.jar", "a/A.class")
            // A resource-only AAR's classes.jar has no `.class` entries. Two shapes: the manifest-only jar the
            // resolver now writes, and a legacy zero-entry zip older builds left. Both must be skipped from
            // dexing (they dex to nothing) and must not crash content hashing (ART's ZipFile throws
            // "No entries" on the zero-entry one; the hasher falls back to raw bytes).
            val manifestOnly = libs.resolve("res-only.jar")
            ZipOutputStream(Files.newOutputStream(manifestOnly)).use { z ->
                z.putNextEntry(ZipEntry("META-INF/MANIFEST.MF")); z.write("Manifest-Version: 1.0\r\n\r\n".toByteArray()); z.closeEntry()
            }
            val zeroEntry = libs.resolve("empty.jar")
            Files.write(zeroEntry, byteArrayOf(0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))

            val dexer = CountingDexer()
            val extRoot = tmp.resolve("ext")
            val result = buildTask(":x:dexBuilder", listOf(zeroEntry, manifestOnly, normal), extRoot, dexer, tmp.resolve("cache"), tmp.resolve("x"))
                .execute(SimpleTaskContext())
            assertEquals(dev.ide.build.TaskResult.Success, result, "class-free jars must not break the dex build")
            assertEquals(1, dexer.archiveCalls.get(), "only the class-bearing jar is dexed; class-free jars are skipped")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /** A dexer that archives libraries OFF the app heap. Counts off-heap vs in-process archive calls so the test
     *  can assert dexBuilder routes the library scope through [dexArchiveOffHeap] when the capability is present. */
    private class OffHeapCountingDexer : Dexer, OffHeapArchiveDexer {
        val inProcessArchiveCalls = AtomicInteger(0)
        val offHeapCalls = AtomicInteger(0)
        override fun dex(inputs: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int, desugaredLibConfig: Path?): ToolResult {
            Files.createDirectories(outDir); Files.write(outDir.resolve("classes.dex"), byteArrayOf(1)); return ToolResult.ok(emptyList())
        }
        override fun dexArchive(inputs: List<Path>, classpath: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int, desugaredLibConfig: Path?): ToolResult {
            inProcessArchiveCalls.incrementAndGet(); writePerClassDexes(inputs, outDir); return ToolResult.ok(emptyList())
        }
        override fun offHeapArchivePlan(jarCount: Int) = MergePlan(maxInvocations = jarCount, concurrency = 2, threadsPerInvocation = 1)
        override fun dexArchiveOffHeap(jar: Path, classpath: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int, desugaredLibConfig: Path?): ToolResult {
            offHeapCalls.incrementAndGet(); writePerClassDexes(listOf(jar), outDir); return ToolResult.ok(emptyList())
        }
    }

    @Test
    fun coldLibraryArchivingRoutesOffHeapWhenSupported() = runBlocking {
        val tmp = Files.createTempDirectory("dexoffheap")
        try {
            val libs = tmp.resolve("libs"); Files.createDirectories(libs)
            val jars = listOf(jar(libs, "a.jar", "a/A.class"), jar(libs, "b.jar", "b/B.class"), jar(libs, "c.jar", "c/C.class"))
            val dexer = OffHeapCountingDexer()
            val extRoot = tmp.resolve("ext")
            val result = buildTask(":x:dexBuilder", jars, extRoot, dexer, tmp.resolve("cache"), tmp.resolve("x")).execute(SimpleTaskContext())

            assertEquals(dev.ide.build.TaskResult.Success, result)
            assertEquals(3, dexer.offHeapCalls.get(), "cold library archives run off-heap (forked) when the dexer supports it")
            assertEquals(0, dexer.inProcessArchiveCalls.get(), "library archiving must not fall back to the in-process dexArchive path")
            assertTrue(DexArchivesProbe.hasDexUnder(extRoot), "each library still produces its own per-jar bucket")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun incompleteLibraryBucketIsReDexedNotReused() = runBlocking {
        val tmp = Files.createTempDirectory("dexincomplete")
        try {
            val libs = tmp.resolve("libs"); Files.createDirectories(libs)
            val lib = jar(libs, "lib.jar", "a/A.class", "a/B.class")
            val extRoot = tmp.resolve("ext")
            // A partial bucket a prior/interrupted build left: has A's dex but not B's. Reuse used to check only
            // for ANY dex (hasDex), so it would be kept and B silently absent — a runtime ClassNotFoundException.
            val bucket = extRoot.resolve(DexArchives.contentHash(lib))
            Files.createDirectories(bucket.resolve("a")); Files.write(bucket.resolve("a/A.dex"), byteArrayOf(1))

            val dexer = CountingDexer()
            val result = buildTask(":x:dexBuilder", listOf(lib), extRoot, dexer, tmp.resolve("cache"), tmp.resolve("x")).execute(SimpleTaskContext())

            assertEquals(dev.ide.build.TaskResult.Success, result)
            assertEquals(1, dexer.archiveCalls.get(), "an incomplete bucket must be re-dexed, not reused")
            assertTrue(Files.isRegularFile(bucket.resolve("a/B.dex")), "the previously-missing class is now dexed")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /** A dexer that reports success but silently drops a class (mimicking a D8 quirk / a broken archive). */
    private class DroppingDexer(private val omit: String) : Dexer {
        override fun dex(inputs: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int, desugaredLibConfig: Path?): ToolResult {
            Files.createDirectories(outDir); Files.write(outDir.resolve("classes.dex"), byteArrayOf(1)); return ToolResult.ok(emptyList())
        }
        override fun dexArchive(inputs: List<Path>, classpath: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int, desugaredLibConfig: Path?): ToolResult {
            writePerClassDexes(inputs, outDir, omit = omit); return ToolResult.ok(emptyList())   // "success" but incomplete
        }
    }

    @Test
    fun droppedLibraryClassFailsTheBuildLoudly() = runBlocking {
        val tmp = Files.createTempDirectory("dexdrop")
        try {
            val libs = tmp.resolve("libs"); Files.createDirectories(libs)
            val lib = jar(libs, "lib.jar", "a/A.class", "a/B.class")
            val result = buildTask(":x:dexBuilder", listOf(lib), tmp.resolve("ext"), DroppingDexer(omit = "a/B.class"), tmp.resolve("cache"), tmp.resolve("x"))
                .execute(SimpleTaskContext())

            assertTrue(result is dev.ide.build.TaskResult.Failed, "a dropped class must fail the build, not ship a broken APK")
            val msg = (result as dev.ide.build.TaskResult.Failed).message
            assertTrue("a.B" in msg && "dropped" in msg, "the failure names the dropped class + cause: $msg")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    private object DexArchivesProbe {
        fun hasDexUnder(root: Path): Boolean = Files.isDirectory(root) &&
            Files.walk(root).use { s -> s.anyMatch { it.toString().endsWith(".dex") } }
    }
}
