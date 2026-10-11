package dev.ide.core.sync

import dev.ide.testkit.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [SyncStamp] matches an importer's build files without walking derived directories, and spots changes. */
class SyncStampTest {

    private val globs = listOf("*.gradle", "*.gradle.kts", "**/*.gradle", "**/*.gradle.kts")

    private fun Path.file(rel: String, text: String = "x"): Path =
        resolve(rel).also { Files.createDirectories(it.parent); it.writeText(text) }

    @Test
    fun matchSkipsPrunedDirectoriesAtAnyDepth() {
        withTempDir("sync-stamp") { root ->
            val settings = root.file("settings.gradle")
            val app = root.file("app/build.gradle.kts")
            root.file("build/generated.gradle")
            root.file(".gradle/cache/deps.gradle.kts")
            root.file("app/build/intermediates/merged.gradle")
            root.file(".git/hooks/x.gradle")
            root.file("app/src/main/Main.kt")

            assertEquals(listOf(app, settings).sorted(), SyncStamp.match(root, globs))
        }
    }

    @Test
    fun stampGoesStaleWhenAWatchedFileChanges() {
        withTempDir("sync-stamp") { root ->
            val app = root.file("app/build.gradle.kts", "plugins {}")
            SyncStamp.write(root, "gradle-compat", SyncStamp.match(root, globs))
            assertFalse(SyncStamp.isStale(root, "gradle-compat", SyncStamp.match(root, globs)))

            root.file("app/build/out.gradle", "derived output is not watched")
            assertFalse(SyncStamp.isStale(root, "gradle-compat", SyncStamp.match(root, globs)))

            app.writeText("plugins { id(\"com.android.application\") }")
            assertTrue(SyncStamp.isStale(root, "gradle-compat", SyncStamp.match(root, globs)))
        }
    }
}
