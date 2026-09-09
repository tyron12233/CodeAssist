package dev.ide.build.engine

import dev.ide.testkit.withTempDir
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `generateSources` starts from an empty output directory ([clearGeneratedOutput]).
 *
 * Source generation is not incremental — each run rewrites everything it produces — so nothing else prunes
 * that directory, and the generated root is compiled as a source root. Without this, a file generated for a
 * source that was later renamed, moved or deleted survives every subsequent build and keeps failing the
 * compile against a class that no longer exists (seen with Hilt: deleting a stray second `@HiltAndroidApp`
 * left its `Hilt_*` and `_MembersInjector` siblings behind).
 */
class ClearGeneratedOutputTest {

    @Test
    fun everythingUnderTheOutputDirectoryGoesAndTheDirectoryStays() {
        withTempDir("gen-clear") { dir ->
            val out = dir.resolve("generated/ksp/debug")
            Files.createDirectories(out.resolve("com/example/di"))
            Files.writeString(out.resolve("com/example/Stale.java"), "class Stale {}")
            Files.writeString(out.resolve("com/example/di/AlsoStale.java"), "class AlsoStale {}")

            clearGeneratedOutput(out)

            assertTrue(Files.isDirectory(out), "the output directory itself must survive")
            assertEquals(
                emptyList(),
                Files.walk(out).use { s -> s.filter { it != out }.map { out.relativize(it).toString() }.toList() },
                "nested directories go too, not just files",
            )
        }
    }

    @Test
    fun anAbsentDirectoryIsCreatedAndSiblingsAreUntouched() {
        withTempDir("gen-clear-absent") { dir ->
            val out = dir.resolve("generated/ksp/debug")
            // The KSP caches/classes sidecar lives NEXT to the output dir, not inside it, precisely so a
            // clear like this doesn't throw them away.
            val sidecar = dir.resolve("generated/ksp/debug.ksp/caches")
            Files.createDirectories(sidecar)
            Files.writeString(sidecar.resolve("symbols"), "cached")

            clearGeneratedOutput(out)

            assertTrue(Files.isDirectory(out), "an absent output directory is created")
            assertTrue(Files.isRegularFile(sidecar.resolve("symbols")), "a sibling must not be touched")
        }
    }

    @Test
    fun aFileWhereTheDirectoryShouldBeIsReplaced() {
        withTempDir("gen-clear-file") { dir ->
            val out = dir.resolve("generated")
            Files.createDirectories(out.parent)
            Files.writeString(out, "not a directory")

            clearGeneratedOutput(out)

            assertTrue(Files.isDirectory(out))
            assertFalse(Files.isRegularFile(out))
        }
    }
}
