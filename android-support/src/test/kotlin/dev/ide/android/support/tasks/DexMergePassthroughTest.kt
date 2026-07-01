package dev.ide.android.support.tasks

import dev.ide.android.support.tools.Dexer
import dev.ide.android.support.tools.MergePlan
import dev.ide.android.support.tools.ToolResult
import dev.ide.build.TaskName
import dev.ide.build.TaskResult
import dev.ide.build.engine.SimpleTaskContext
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [DexMergeTask] is a faithful merger: it hands every input `.dex` to D8 as-is and does NOT dedup. Producing
 * a clean, conflict-free graph (capability conflict eviction, version/variant selection) is the dependency
 * resolver's job; if a class still arrives twice it's a dirty input and D8 surfacing it is correct — the merge
 * task does not silently drop classes to paper over it.
 */
class DexMergePassthroughTest {

    /** Records the dex files handed to it, so the test can assert nothing was dropped. */
    private open class RecordingDexer : Dexer {
        val merged = ArrayList<Path>()
        override fun dex(inputs: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int, desugaredLibConfig: Path?): ToolResult {
            merged.addAll(inputs)
            Files.createDirectories(outDir); Files.write(outDir.resolve("classes.dex"), byteArrayOf(1)); return ToolResult.ok(emptyList())
        }
        override fun dexArchive(inputs: List<Path>, classpath: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int, desugaredLibConfig: Path?): ToolResult =
            ToolResult.ok(emptyList())
    }

    private fun writeDex(bucket: Path, classPath: String) {
        val f = bucket.resolve(classPath); Files.createDirectories(f.parent); Files.write(f, byteArrayOf(1))
    }

    @Test
    fun mergesEveryInputDexWithoutDropping() = runBlocking {
        val tmp = Files.createTempDirectory("dexmerge")
        try {
            val ext = tmp.resolve("ext-archives")
            // Two content-hash buckets that both define the same class plus a unique one each.
            val a = ext.resolve("a".repeat(24)); val b = ext.resolve("b".repeat(24))
            writeDex(a, "androidx/collection/ArrayMapKt.dex"); writeDex(a, "a/OnlyA.dex")
            writeDex(b, "androidx/collection/ArrayMapKt.dex"); writeDex(b, "b/OnlyB.dex")

            val dexer = RecordingDexer()
            val out = tmp.resolve("ext-dex")
            val task = DexMergeTask(TaskName("mergeExtDex"), listOf(ext), tmp.resolve("android.jar"), 21, false, out, dexer, groupPerBucket = false)
            val result = task.execute(SimpleTaskContext())

            assertEquals(TaskResult.Success, result)
            val names = dexer.merged.map { it.fileName.toString() }
            // No dedup: BOTH copies of the shared class reach D8 (a clean graph is the resolver's responsibility).
            assertEquals(2, names.count { it == "ArrayMapKt.dex" }, "the merge passes every input through, deduping nothing: $names")
            assertTrue("OnlyA.dex" in names && "OnlyB.dex" in names, "every unique class is merged: $names")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /** Forces all buckets into ONE merge invocation, like a forking dexer's batched plan. */
    private class BatchingDexer : RecordingDexer() {
        override fun mergePlan(inputCount: Int): MergePlan = MergePlan(maxInvocations = 1, concurrency = 1, threadsPerInvocation = 1)
    }

    @Test
    fun perLibraryMergeCollapsesDuplicateClassWhenBatched() = runBlocking {
        val tmp = Files.createTempDirectory("dexmerge-dup")
        try {
            val ext = tmp.resolve("ext-archives")
            // Two libraries that BOTH ship kotlin.ArrayIntrinsicsKt (a shaded/duplicated stdlib the resolver
            // can't coordinate-dedup), each plus a unique class.
            val a = ext.resolve("a".repeat(24)); val b = ext.resolve("b".repeat(24))
            writeDex(a, "kotlin/ArrayIntrinsicsKt.dex"); writeDex(a, "a/OnlyA.dex")
            writeDex(b, "kotlin/ArrayIntrinsicsKt.dex"); writeDex(b, "b/OnlyB.dex")

            val dexer = BatchingDexer()
            val out = tmp.resolve("ext-dex")
            // Per-library mode (groupPerBucket=true) + a batching plan = both libraries in one merge group. Without
            // dedup D8 would fail "Type kotlin.ArrayIntrinsicsKt is defined multiple times".
            val task = DexMergeTask(TaskName("mergeExtDex"), listOf(ext), tmp.resolve("android.jar"), 21, false, out, dexer, groupPerBucket = true)
            assertEquals(TaskResult.Success, task.execute(SimpleTaskContext()))

            val names = dexer.merged.map { it.fileName.toString() }
            assertEquals(1, names.count { it == "ArrayIntrinsicsKt.dex" }, "the duplicate class is collapsed to one copy across libraries: $names")
            assertTrue("OnlyA.dex" in names && "OnlyB.dex" in names, "every unique class survives: $names")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
