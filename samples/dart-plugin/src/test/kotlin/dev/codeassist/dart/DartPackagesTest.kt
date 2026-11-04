package dev.codeassist.dart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class DartPackagesTest {

    @TempDir
    lateinit var dir: Path

    private fun write(rel: String, text: String = ""): Path =
        dir.resolve(rel).also { Files.createDirectories(it.parent); Files.write(it, text.toByteArray()) }

    @Test
    fun theNameComesFromThePubspec() {
        assertEquals("my_app", DartPackages.nameOf("name: my_app\nversion: 1.0.0\n"))
        assertEquals("quoted", DartPackages.nameOf("description: x\nname: 'quoted' # a comment\n"))
        // A nested `name:` (a dependency's) is not the package's.
        assertNull(DartPackages.nameOf("dependencies:\n  foo:\n    name: other\n"))
    }

    @Test
    fun projectNamesBecomeValidPackageNames() {
        assertEquals("my_app", DartPackages.packageName("My App"))
        assertEquals("hello_world", DartPackages.packageName("HelloWorld"))
        assertEquals("app_2048", DartPackages.packageName("2048"))
        assertEquals("app", DartPackages.packageName("!!!"))
    }

    @Test
    fun pubGetIsNeededUntilTheConfigIsNewerThanThePubspec() {
        val pubspec = write("pubspec.yaml", "name: p\n")
        assertTrue(DartPackages.needsPubGet(dir), "never fetched")
        val config = write(DartPackages.PACKAGE_CONFIG, "{}")
        Files.setLastModifiedTime(pubspec, FileTime.fromMillis(1_000_000))
        Files.setLastModifiedTime(config, FileTime.fromMillis(2_000_000))
        assertFalse(DartPackages.needsPubGet(dir))
        Files.setLastModifiedTime(pubspec, FileTime.fromMillis(3_000_000))
        assertTrue(DartPackages.needsPubGet(dir), "the pubspec changed since")
        Files.setLastModifiedTime(pubspec, FileTime.fromMillis(1_000_000))
        val lock = write("pubspec.lock")
        Files.setLastModifiedTime(lock, FileTime.fromMillis(3_000_000))
        assertTrue(DartPackages.needsPubGet(dir), "the lock file changed since")
    }

    @Test
    fun executablesAreTheTopLevelDartFilesInBin() {
        assertEquals(emptyList<Path>(), DartPackages.executables(dir))
        write("bin/zeta.dart")
        write("bin/alpha.dart")
        write("bin/notes.txt")
        write("bin/src/helper.dart")
        assertEquals(listOf("alpha.dart", "zeta.dart"), DartPackages.executables(dir).map { it.fileName.toString() })
    }

    @Test
    fun theRootIsTheNearestPubspecWithinTheProject() {
        write("pubspec.yaml", "name: outer\n")
        write("packages/inner/pubspec.yaml", "name: inner\n")
        val file = write("packages/inner/lib/src/a.dart")
        assertEquals(dir.resolve("packages/inner"), DartPackages.packageRootOf(file, dir))
        assertEquals(dir, DartPackages.packageRootOf(write("tool/b.dart"), dir))
        // Never above the project, even when a pubspec is there.
        assertNull(DartPackages.packageRootOf(file, dir.resolve("packages/inner/lib")))
    }

    @Test
    fun contentRootsAreTheConventionalDirectoriesThatExist() {
        // `lib/` and `test/` even before they exist; the rest only when they are there.
        assertEquals(mapOf("main" to listOf("lib"), "test" to listOf("test")), DartPackages.contentRoots(dir))
        write("bin/app.dart")
        write("tool/build.dart")
        write("test/app_test.dart")
        assertEquals(
            mapOf("main" to listOf("lib", "bin", "tool"), "test" to listOf("test")),
            DartPackages.contentRoots(dir),
        )
    }
}
