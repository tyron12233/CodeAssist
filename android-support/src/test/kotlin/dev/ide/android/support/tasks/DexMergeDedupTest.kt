package dev.ide.android.support.tasks

import dev.ide.android.support.tools.Dexer
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
 * [DexMergeTask] must merge a class that two libraries both define exactly once — otherwise D8 aborts with
 * "Type ... is defined multiple times" (the on-device failure for `androidx.collection.ArrayMapKt`, shipped
 * by both `collection` and `collection-ktx`). The per-class archive names each `.dex` by its class, so the
 * merge dedupes by the dex path relative to its content-hash bucket.
 */
class DexMergeDedupTest {

    /** Records the dex files handed to it (so the test can assert the merge deduped them). */
    private class RecordingDexer : Dexer {
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
    fun classDefinedByTwoBucketsIsMergedOnce() = runBlocking {
        val tmp = Files.createTempDirectory("dexmerge")
        try {
            val ext = tmp.resolve("ext-archives")
            // Two content-hash buckets that both define androidx/collection/ArrayMapKt, each with a unique class.
            val a = ext.resolve("a".repeat(24)); val b = ext.resolve("b".repeat(24))
            writeDex(a, "androidx/collection/ArrayMapKt.dex"); writeDex(a, "a/OnlyA.dex")
            writeDex(b, "androidx/collection/ArrayMapKt.dex"); writeDex(b, "b/OnlyB.dex")

            val dexer = RecordingDexer()
            val out = tmp.resolve("ext-dex")
            val task = DexMergeTask(TaskName("mergeExtDex"), listOf(ext), tmp.resolve("android.jar"), 21, false, out, dexer, groupPerBucket = false)
            val result = task.execute(SimpleTaskContext())

            assertEquals(TaskResult.Success, result, "merge with a duplicate class must not fail")
            val names = dexer.merged.map { it.fileName.toString() }
            assertEquals(1, names.count { it == "ArrayMapKt.dex" }, "the duplicated class is merged exactly once: $names")
            assertTrue("OnlyA.dex" in names && "OnlyB.dex" in names, "every unique class is still merged: $names")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
