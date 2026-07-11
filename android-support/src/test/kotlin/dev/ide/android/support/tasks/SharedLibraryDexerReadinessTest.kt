package dev.ide.android.support.tasks

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
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
