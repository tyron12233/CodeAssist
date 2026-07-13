package dev.ide.android.support.tasks

import dev.ide.android.support.tools.Dexer
import dev.ide.android.support.tools.ToolResult
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The layout preview's dex-readiness check ([SharedLibraryDexer.undexedLibraries]) — the pure, Dexer-free scan
 * that decides whether the preview can class-load or must prompt a "prepare libraries" build. classNamesOf
 * lists `.class` zip entries by name (no parsing), so dummy class bytes are enough here.
 */
class SharedLibraryDexerReadinessTest {

    private fun jar(path: Path, vararg entries: String): Path {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            for (e in entries) { zip.putNextEntry(ZipEntry(e)); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry() }
        }
        return path
    }

    @Test
    fun everyLibraryIsUndexedWhenTheCacheIsEmpty() {
        val dir = Files.createTempDirectory("dexcheck")
        try {
            val a = jar(dir.resolve("a.jar"), "com/example/Foo.class")
            val b = jar(dir.resolve("b.jar"), "com/example/Bar.class")
            val u = SharedLibraryDexer.computeUniverse(listOf(a, b), dir.resolve("hc"), minApi = 34, desugaredLibConfig = null)
            val undexed = SharedLibraryDexer.undexedLibraries(listOf(a, b), u, dir.resolve("dex"), minApi = 34, release = false)
            assertEquals(setOf(a, b), undexed.toSet(), "an empty cache means every class-bearing library needs dexing")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** A fake dexer that writes a per-class `.dex` for each `.class` (like D8 --file-per-class-file), enough for
     *  [SharedLibraryDexer.dexScope]'s completeness check + shared-cache publish to run offline. */
    private class FakeDexer : Dexer {
        override fun dex(inputs: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int, desugaredLibConfig: Path?): ToolResult {
            Files.createDirectories(outDir); Files.write(outDir.resolve("classes.dex"), byteArrayOf(1)); return ToolResult.ok(emptyList())
        }
        override fun dexArchive(inputs: List<Path>, classpath: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int, desugaredLibConfig: Path?): ToolResult {
            Files.createDirectories(outDir)
            for (j in inputs.filter { Files.exists(it) }) ZipFile(j.toFile()).use { zf ->
                zf.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { e ->
                    val dex = outDir.resolve(e.name.removeSuffix(".class") + ".dex")
                    dex.parent?.let { Files.createDirectories(it) }; Files.write(dex, byteArrayOf(1))
                }
            }
            return ToolResult.ok(emptyList())
        }
    }

    /**
     * The build dexes the EXTERNAL library scope with a universe of the external libs ALONE (deps point down —
     * an external lib never sees your modules), but the layout-preview readiness lumps the external libs and the
     * dependency-module outputs into ONE universe. Below API 26 desugaring is active, so that combined universe
     * hashes to a different [SharedLibraryDexer.Universe.desugarDigest] — and therefore a different cache tag —
     * than the build's external scope. The build's buckets are then never found and the "prepare libraries" gate
     * never flips even though the dexing succeeded. The readiness check must reuse the build's SCOPED universe.
     */
    @Test
    fun combinedUniverseReadinessMissesTheBuildsScopedExternalBucketsBelowApi26() = runBlocking {
        val dir = Files.createTempDirectory("dexscope")
        try {
            val extA = jar(dir.resolve("extA.jar"), "ext/A.class")
            val extB = jar(dir.resolve("extB.jar"), "ext/B.class")
            val modC = jar(dir.resolve("modC.jar"), "mod/C.class")   // a dependency-module output
            val cache = dir.resolve("dex")                            // the shared caches/dex
            val hc = dir.resolve("hc")

            // BUILD: dex the external scope (external libs ALONE) into the shared cache — the build's ext universe.
            val libDexer = SharedLibraryDexer(FakeDexer(), dir.resolve("android.jar"), minApi = 21, release = false, dexCacheRoot = cache)
            val extUniverse = libDexer.computeUniverse(listOf(extA, extB), hc)
            assertTrue(libDexer.dexScope(listOf(extA, extB), dir.resolve("extRoot"), extUniverse), "build ext-scope dexing")
            // Baseline: with the build's own scoped universe the buckets are found.
            assertTrue(
                SharedLibraryDexer.undexedLibraries(listOf(extA, extB), extUniverse, cache, minApi = 21, release = false).isEmpty(),
                "the build's ext universe finds its own buckets",
            )

            // BUG: the combined-universe readiness (external + module output) misses them below API 26.
            val combined = SharedLibraryDexer.computeUniverse(listOf(extA, extB, modC), hc, minApi = 21, desugaredLibConfig = null)
            assertEquals(
                setOf(extA, extB),
                SharedLibraryDexer.undexedLibraries(listOf(extA, extB), combined, cache, minApi = 21, release = false).toSet(),
                "repro: a combined universe re-keys the cache tag, so the build's ext buckets are missed",
            )

            // FIX: the scoped gate reuses the build's ext buckets. Only the still-un-dexed module output remains
            // — never the external libs (the bug). i.e. the external scope now correctly reads as dexed.
            fun previewUndexed() = SharedLibraryDexer.undexedForPreview(
                externalJars = listOf(extA, extB), moduleJars = listOf(modC), hashCacheDir = hc,
                minApi = 21, desugaredLibConfig = null, dexCacheRoot = cache,
            )
            assertEquals(setOf(modC), previewUndexed().toSet(), "fix: external libs reuse the build's ext buckets")

            // BUILD (sub scope): dex the dependency-module output against sub+ext, as the dexBuilder does.
            val subUniverse = libDexer.computeUniverse(listOf(modC, extA, extB), hc)
            assertTrue(libDexer.dexScope(listOf(modC), dir.resolve("subRoot"), subUniverse), "build sub-scope dexing")
            // Now BOTH scopes are dexed → the gate is fully clear → "prepare libraries" dismisses.
            assertTrue(previewUndexed().isEmpty(), "fix: with both build scopes dexed the gate flips")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun aResourceOnlyJarIsNeverReportedUndexed() {
        val dir = Files.createTempDirectory("dexcheck2")
        try {
            // No `.class` entries → dexes to nothing → must not be treated as "needs dexing" (else the preview
            // would prompt to build forever over a resource-only AAR's classes.jar).
            val res = jar(dir.resolve("res.jar"), "res/values/strings.xml")
            val u = SharedLibraryDexer.computeUniverse(listOf(res), dir.resolve("hc"), minApi = 34, desugaredLibConfig = null)
            assertTrue(SharedLibraryDexer.undexedLibraries(listOf(res), u, dir.resolve("dex"), minApi = 34, release = false).isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
