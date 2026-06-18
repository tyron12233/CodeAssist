package dev.ide.android.support.tasks

import dev.ide.android.support.tools.Dexer
import dev.ide.android.support.tools.ToolResult
import dev.ide.build.TaskName
import dev.ide.build.engine.SimpleTaskContext
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shared cross-project dex cache: a library jar is dexed once, then reused by copy in any other project
 * (or after a clean) instead of being re-dexed. Uses a fake [Dexer] that only counts invocations and writes a
 * stub `.dex`, so the test exercises the caching/parallel logic in [DexArchiveBuilderTask] offline (no real D8).
 */
class DexArchiveCacheTest {

    /** Counts dexArchive calls and writes a stub per-class `.dex` so `hasDex(outDir)` is satisfied afterwards. */
    private class CountingDexer : Dexer {
        val archiveCalls = AtomicInteger(0)
        override fun dex(inputs: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int): ToolResult {
            Files.createDirectories(outDir); Files.write(outDir.resolve("classes.dex"), byteArrayOf(1)); return ToolResult.ok(emptyList())
        }
        override fun dexArchive(inputs: List<Path>, classpath: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int): ToolResult {
            archiveCalls.incrementAndGet()
            Files.createDirectories(outDir); Files.write(outDir.resolve("c.dex"), byteArrayOf(1)); return ToolResult.ok(emptyList())
        }
    }

    private fun jar(dir: Path, name: String, entry: String): Path {
        val p = dir.resolve(name)
        ZipOutputStream(Files.newOutputStream(p)).use { z -> z.putNextEntry(ZipEntry(entry)); z.write(entry.toByteArray()); z.closeEntry() }
        return p
    }

    /**
     * Mimics D8's desugaring-classpath contract: it aborts with "Classpath type already present" when two
     * classpath entries define the same class. A dexer that enforces the same rule turns the regression into a
     * test failure offline (no real D8 needed).
     */
    private class ClasspathCheckingDexer : Dexer {
        override fun dex(inputs: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int): ToolResult {
            Files.createDirectories(outDir); Files.write(outDir.resolve("classes.dex"), byteArrayOf(1)); return ToolResult.ok(emptyList())
        }
        override fun dexArchive(inputs: List<Path>, classpath: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int): ToolResult {
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
            Files.createDirectories(outDir); Files.write(outDir.resolve("c.dex"), byteArrayOf(1)); return ToolResult.ok(emptyList())
        }
    }

    private fun buildTask(name: String, externalJars: List<Path>, extRoot: Path, dexer: Dexer, cache: Path, tmp: Path) =
        DexArchiveBuilderTask(
            TaskName(name), projectClasses = emptyList(), subProjectJars = emptyList(), externalJars = externalJars,
            androidJar = tmp.resolve("android.jar"), minApi = 21, release = false,
            stagingJar = tmp.resolve("staging/project.jar"),
            projectDexRoot = tmp.resolve("proj"), subDexRoot = tmp.resolve("sub"), extDexRoot = extRoot,
            dexer = dexer, dexCacheRoot = cache,
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

    @Test
    fun duplicateLibraryOnClasspathDoesNotCrashDexing() = runBlocking {
        val tmp = Files.createTempDirectory("dexdup")
        try {
            val libs = tmp.resolve("libs"); Files.createDirectories(libs)
            // The same artifact reaching the dexer via two paths (byte-identical → same content hash), e.g. two
            // resolver cache paths. Both define kotlin/sequences/SequencesKt.class.
            val dupA = jar(libs, "stdlib-a.jar", "kotlin/sequences/SequencesKt.class")
            val dupB = libs.resolve("stdlib-b.jar"); Files.copy(dupA, dupB)
            val other = jar(libs, "other.jar", "o/O.class")

            val dexer = ClasspathCheckingDexer()
            val extRoot = tmp.resolve("ext")
            val result = buildTask(":x:dexBuilder", listOf(dupA, dupB, other), extRoot, dexer, tmp.resolve("cache"), tmp.resolve("x"))
                .execute(SimpleTaskContext())
            assertEquals(dev.ide.build.TaskResult.Success, result, "dedup must keep SequencesKt off the classpath twice")
            assertTrue(DexArchivesProbe.hasDexUnder(extRoot), "the deduped library set still produces dex output")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    private object DexArchivesProbe {
        fun hasDexUnder(root: Path): Boolean = Files.isDirectory(root) &&
            Files.walk(root).use { s -> s.anyMatch { it.toString().endsWith(".dex") } }
    }
}
