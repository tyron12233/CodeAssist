package dev.ide.android.support.tasks

import dev.ide.android.support.tools.Dexer
import dev.ide.android.support.tools.ToolResult
import dev.ide.build.TaskName
import dev.ide.build.TaskResult
import dev.ide.build.engine.SimpleTaskContext
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The native-multidex merge buckets classes by a stable hash and re-merges only the buckets whose classes
 * changed (AGP's `DexMergingTask`): editing one class re-merges its one bucket, not the whole scope. Uses a
 * dexer that counts merge invocations so the test can assert exactly how many buckets were (re-)merged.
 */
class DexMergeIncrementalTest {

    /** Counts `dex` (merge) invocations and writes one output dex per merged bucket. */
    private class MergeCountingDexer : Dexer {
        val mergeCalls = AtomicInteger(0)
        override fun dex(inputs: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int, desugaredLibConfig: Path?): ToolResult {
            mergeCalls.incrementAndGet()
            Files.createDirectories(outDir); Files.write(outDir.resolve("classes.dex"), byteArrayOf(1)); return ToolResult.ok(emptyList())
        }
        override fun dexArchive(inputs: List<Path>, classpath: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int, desugaredLibConfig: Path?): ToolResult =
            ToolResult.ok(emptyList())
    }

    private fun writeDex(root: Path, rel: String, bytes: ByteArray) {
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.write(f, bytes)
    }

    private fun jar(dir: Path, name: String, vararg classEntries: String): Path {
        Files.createDirectories(dir)
        val p = dir.resolve(name)
        java.util.zip.ZipOutputStream(Files.newOutputStream(p)).use { z ->
            for (e in classEntries) { z.putNextEntry(java.util.zip.ZipEntry(e)); z.write(e.toByteArray()); z.closeEntry() }
        }
        return p
    }

    @Test
    fun externalLibsDexedOncePassAndReusedFromCache() = runBlocking {
        val tmp = Files.createTempDirectory("dexextlibs")
        try {
            val libs = tmp.resolve("libs")
            val jars = listOf(jar(libs, "a.jar", "a/A.class"), jar(libs, "b.jar", "b/B.class"))
            val cache = tmp.resolve("ext-indexed")
            val dexer = MergeCountingDexer()
            fun task(out: Path) = DexExternalLibsTask(TaskName("dexExtLibs"), jars, tmp.resolve("android.jar"), 21, false, out, dexer, null, cache)

            // Build 1 (cold): one D8 pass over the whole classpath (a single dex() call), seeds the cache.
            val out1 = tmp.resolve("b1/ext-dex")
            assertEquals(TaskResult.Success, task(out1).execute(SimpleTaskContext()))
            assertEquals(1, dexer.mergeCalls.get(), "one-pass = a single dex invocation for the whole classpath")
            assertTrue(hasDexUnder(out1), "cold build produced indexed dex")

            // Build 2 (fresh output = a clean; SAME library set + shared cache): reused, no dexing.
            dexer.mergeCalls.set(0)
            val out2 = tmp.resolve("b2/ext-dex")
            assertEquals(TaskResult.Success, task(out2).execute(SimpleTaskContext()))
            assertEquals(0, dexer.mergeCalls.get(), "the same library set reuses the cached indexed dex")
            assertTrue(hasDexUnder(out2), "reused indexed dex is materialized into the fresh output dir")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    private fun hasDexUnder(root: Path): Boolean =
        Files.isDirectory(root) && Files.walk(root).use { s -> s.anyMatch { it.toString().endsWith(".dex") } }

    @Test
    fun editingOneClassReMergesOnlyItsBucket() = runBlocking {
        val tmp = Files.createTempDirectory("dexmerge-inc")
        try {
            // Project-scope archive layout: per-class dex directly under the root (no hash subdir).
            val arch = tmp.resolve("project-archives")
            val classes = (0 until 24).map { "pkg/C$it.dex" }
            classes.forEach { writeDex(arch, it, byteArrayOf(1)) }
            val out = tmp.resolve("project-dex")
            val dexer = MergeCountingDexer()
            // mergeChunk = 1 forces the max bucket count regardless of the machine's core count (deterministic test).
            fun task() = DexMergeTask(TaskName("mergeProjectDex"), listOf(arch), tmp.resolve("android.jar"), 21, false, out, dexer, groupPerBucket = false, mergeChunk = 1)

            // Cold: every non-empty bucket is merged. With 24 classes and the max (8) buckets, all 8 fill.
            assertEquals(TaskResult.Success, task().execute(SimpleTaskContext()))
            val coldMerges = dexer.mergeCalls.get()
            assertTrue(coldMerges >= 2, "the scope is split across multiple merge buckets, not one big merge: $coldMerges")
            assertTrue(hasDexUnder(out), "the merge produced output dex")

            // Re-run with nothing changed: every bucket is up-to-date, so no bucket is re-merged.
            dexer.mergeCalls.set(0)
            assertEquals(TaskResult.Success, task().execute(SimpleTaskContext()))
            assertEquals(0, dexer.mergeCalls.get(), "an unchanged scope re-merges nothing")

            // Change ONE class (different size → guaranteed fingerprint miss): only its bucket re-merges.
            writeDex(arch, "pkg/C5.dex", byteArrayOf(2, 2, 2))
            dexer.mergeCalls.set(0)
            assertEquals(TaskResult.Success, task().execute(SimpleTaskContext()))
            assertEquals(1, dexer.mergeCalls.get(), "editing one class re-merges exactly its one bucket, reusing the rest")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun mergedExtDexIsReusedFromSharedCacheAcrossCleans() = runBlocking {
        val tmp = Files.createTempDirectory("dexmerge-cache")
        try {
            // External scope: per-library content-hash buckets, each with per-class dex.
            val arch = tmp.resolve("ext-archives")
            writeDex(arch.resolve("a".repeat(24)), "androidx/a/A.dex", byteArrayOf(1))
            writeDex(arch.resolve("b".repeat(24)), "androidx/b/B.dex", byteArrayOf(1))
            val cache = tmp.resolve("merged-ext")
            val dexer = MergeCountingDexer()
            fun task(out: Path) = DexMergeTask(TaskName("mergeExtDex"), listOf(arch), tmp.resolve("android.jar"), 21, false, out, dexer, groupPerBucket = false, mergeCacheRoot = cache)

            // Build 1 (cold cache): merges + seeds the shared merge cache.
            val out1 = tmp.resolve("build1/ext-dex")
            assertEquals(TaskResult.Success, task(out1).execute(SimpleTaskContext()))
            assertTrue(dexer.mergeCalls.get() >= 1, "cold build merges the ext dex")
            assertTrue(hasDexUnder(out1), "cold build produced merged dex")

            // Build 2 (fresh output dir = a clean; SAME libraries + shared cache): reused, nothing re-merged.
            dexer.mergeCalls.set(0)
            val out2 = tmp.resolve("build2/ext-dex")
            assertEquals(TaskResult.Success, task(out2).execute(SimpleTaskContext()))
            assertEquals(0, dexer.mergeCalls.get(), "a clean rebuild reuses the merged ext dex from the shared cache")
            assertTrue(hasDexUnder(out2), "reused merged dex is materialized into the fresh output dir")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun staleDirectDexFromOldLayoutIsRemoved() = runBlocking {
        val tmp = Files.createTempDirectory("dexmerge-stale")
        try {
            val arch = tmp.resolve("project-archives")
            writeDex(arch, "pkg/A.dex", byteArrayOf(1))
            val out = tmp.resolve("project-dex")
            // Simulate the pre-bucketing single-merge layout: a direct classes.dex left in the output dir.
            Files.createDirectories(out); Files.write(out.resolve("classes.dex"), byteArrayOf(9))
            val dexer = MergeCountingDexer()
            val task = DexMergeTask(TaskName("mergeProjectDex"), listOf(arch), tmp.resolve("android.jar"), 21, false, out, dexer, groupPerBucket = false, mergeChunk = 1)

            assertEquals(TaskResult.Success, task.execute(SimpleTaskContext()))
            // The stale direct dex must be gone (else the packager would glob it into the APK); output lives only in g<b>/.
            assertTrue(Files.notExists(out.resolve("classes.dex")), "stale pre-bucketing classes.dex is removed")
            assertTrue(hasDexUnder(out), "bucketed output dex is present")
            val directDex = Files.list(out).use { s -> s.filter { Files.isRegularFile(it) && it.toString().endsWith(".dex") }.count() }
            assertEquals(0L, directDex, "no .dex directly under the output root; every output dex lives in a bucket dir")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
