package dev.ide.platform.storage

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [StorageUsage] against a fabricated on-disk tree with known file sizes: the per-category
 * breakdown, the source-only [StorageUsage.PROJECTS] bucket, the "other" remainder, and — most
 * importantly — that [StorageUsage.clearCategory] removes exactly the right dirs and never touches
 * source, keystores, or config. Pure `java.nio.file`, so it runs under `CI_CORE_ONLY`.
 */
class StorageUsageTest {

    private val root: Path = Files.createTempDirectory("storage-usage-test")

    @AfterTest
    fun cleanup() {
        if (Files.exists(root)) {
            Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    /** Write a file of exactly [size] bytes, creating parent dirs. */
    private fun file(relative: String, size: Int): Path {
        val p = root.resolve(relative)
        Files.createDirectories(p.parent)
        Files.write(p, ByteArray(size))
        return p
    }

    private fun cat(r: StorageUsage.Report, id: String): Long = r.categories.first { it.id == id }.bytes

    /**
     * On desktop `storageRoot == sharedRoot`, and projects live under `<root>/projects/<name>`.
     * Lay out one file per category so each expected size is a distinct, checkable number.
     */
    private fun seed(): List<Path> {
        val appA = root.resolve("projects").resolve("appA")
        val appB = root.resolve("projects").resolve("appB")

        // Project source + config (→ PROJECTS category, and counted in each project's total).
        file("projects/appA/src/Main.kt", 100)
        file("projects/appA/.platform/settings.properties", 10)   // .platform non-cache config → source
        file("projects/appB/src/App.kt", 50)

        // Per-project caches (→ their categories, NOT the PROJECTS category).
        file("projects/appA/.platform/caches/build/out.bin", 1_000)
        file("projects/appA/.platform/caches/source-index/i.bin", 300)
        file("projects/appA/.platform/caches/kotlin-ext/k.bin", 70)
        file("projects/appA/.platform/caches/aar-res/a.bin", 40)
        file("projects/appB/.platform/caches/build/out.bin", 500)

        // Shared caches under the app home dir.
        file("caches/index/seg.bin", 2_000)
        file("caches/dex/lib.dex", 4_000)
        file("caches/preview-res/merged.bin", 25)
        file(".platform/caches/resolved-deps/lib.jar", 6_000)
        file(".platform/caches/resolved-deps-misses/miss.txt", 5)

        // App-owned SDK/toolchain (clearable, destructive).
        file(".platform/sdk-downloads/dl.zip", 1_500)
        file(".platform/android-sdk/platform.jar", 8_000)
        file(".platform/jdk-src.zip", 700)

        // "Other" — never clearable: keystores + exports + a stray home file.
        file("keystores/debug.keystore", 20)
        file("exports/backup.zip", 15)
        file("prefs.properties", 3)

        return listOf(appA, appB)
    }

    @Test
    fun categoriesAndTotalsAddUp() {
        val projects = seed()
        val r = StorageUsage.report(root, projects, root)

        // dependencies = resolved-deps(6000) + resolved-deps-misses(5) + appA aar-res(40)
        assertEquals(6_045L, cat(r, StorageUsage.DEPENDENCIES), "dependencies")
        // index = shared index(2000) + appA source-index(300)
        assertEquals(2_300L, cat(r, StorageUsage.INDEX), "index")
        // build = shared dex(4000) + appA build(1000) + appB build(500)
        assertEquals(5_500L, cat(r, StorageUsage.BUILD), "build")
        // preview = shared preview-res(25)
        assertEquals(25L, cat(r, StorageUsage.PREVIEW), "preview")
        // language = appA kotlin-ext(70)
        assertEquals(70L, cat(r, StorageUsage.LANGUAGE), "language")
        // sdk = sdk-downloads(1500) + android-sdk(8000) + jdk-src.zip(700)
        assertEquals(10_200L, cat(r, StorageUsage.SDK), "sdk")
        // projects = source/config only: appA Main.kt(100) + appA settings(10) + appB App.kt(50)
        assertEquals(160L, cat(r, StorageUsage.PROJECTS), "projects (source only, excludes caches)")
        // other = keystores(20) + exports(15) + prefs(3)
        assertEquals(38L, cat(r, StorageUsage.OTHER), "other")

        assertEquals(24_338L, r.totalBytes, "total = sum of categories")
        assertEquals(r.categories.sumOf { it.bytes }, r.totalBytes, "total is the category sum")
    }

    @Test
    fun perProjectTotalsIncludeTheirCaches() {
        val projects = seed()
        val r = StorageUsage.report(root, projects, root)
        val byName = r.projects.associateBy { it.name }
        // appA total = source(100+10) + build(1000) + source-index(300) + kotlin-ext(70) + aar-res(40)
        assertEquals(1_520L, byName.getValue("appA").bytes, "appA full size (incl. caches)")
        // appB total = App.kt(50) + build(500)
        assertEquals(550L, byName.getValue("appB").bytes, "appB full size (incl. caches)")
        // Sorted biggest-first.
        assertEquals("appA", r.projects.first().name)
    }

    @Test
    fun clearingBuildRemovesOnlyBuildDirs() {
        val projects = seed()
        val freed = StorageUsage.clearCategory(StorageUsage.BUILD, projects, root)
        assertEquals(5_500L, freed, "freed = shared dex + both project build dirs")

        // Build dirs gone…
        assertFalse(Files.exists(root.resolve("caches/dex")), "shared dex removed")
        assertFalse(Files.exists(root.resolve("projects/appA/.platform/caches/build")), "appA build removed")
        // …but source, other caches, keystores untouched.
        assertTrue(Files.exists(root.resolve("projects/appA/src/Main.kt")), "source kept")
        assertTrue(Files.exists(root.resolve("projects/appA/.platform/caches/source-index/i.bin")), "index kept")
        assertTrue(Files.exists(root.resolve("keystores/debug.keystore")), "keystore kept")

        // The report now reflects the freed space.
        assertEquals(0L, cat(StorageUsage.report(root, projects, root), StorageUsage.BUILD))
    }

    @Test
    fun clearingSdkRemovesOnlyAppOwnedToolchain() {
        val projects = seed()
        val freed = StorageUsage.clearCategory(StorageUsage.SDK, projects, root)
        assertEquals(10_200L, freed)
        assertFalse(Files.exists(root.resolve(".platform/android-sdk")))
        assertFalse(Files.exists(root.resolve(".platform/sdk-downloads")))
        assertFalse(Files.exists(root.resolve(".platform/jdk-src.zip")))
        // resolved-deps lives under .platform/caches, NOT touched by an SDK clear.
        assertTrue(Files.exists(root.resolve(".platform/caches/resolved-deps/lib.jar")), "deps cache kept")
    }

    @Test
    fun clearingProjectsAndOtherAreNoOps() {
        val projects = seed()
        assertEquals(0L, StorageUsage.clearCategory(StorageUsage.PROJECTS, projects, root), "projects is no-op")
        assertEquals(0L, StorageUsage.clearCategory(StorageUsage.OTHER, projects, root), "other is no-op")
        assertEquals(0L, StorageUsage.clearCategory("bogus", projects, root), "unknown id is no-op")
        // Everything still present.
        assertTrue(Files.exists(root.resolve("projects/appA/src/Main.kt")))
        assertTrue(Files.exists(root.resolve("keystores/debug.keystore")))
        assertTrue(Files.exists(root.resolve("caches/dex/lib.dex")))
    }
}
