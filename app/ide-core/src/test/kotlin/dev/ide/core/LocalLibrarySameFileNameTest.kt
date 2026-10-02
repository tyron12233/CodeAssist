package dev.ide.core

import dev.ide.model.LibraryDependency
import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two different local libraries that share a file name (`a/core.jar`, `b/core.jar`) are two libraries. The
 * workspace library table is keyed by name and `create` replaces a same-named entry, so registering the second
 * under the bare file name repointed the first one's dependents at the wrong file.
 */
class LocalLibrarySameFileNameTest {

    @Test
    fun aSecondSameNamedFileGetsItsOwnLibrary() {
        withTempDir("local-lib-same-name") { dir ->
            val a = jar(dir.resolve("a/core.jar"), "a/A.class")
            val b = jar(dir.resolve("b/core.jar"), "b/B.class")
            val root = dir.resolve("proj")
            IdeServices.bootstrapJavaDemo(root).use { ide ->
                val first = runBlocking { ide.dependencies.addLocalLibrary("app", a.toString(), "implementation") }
                assertTrue(first.success, first.message)
                val second = runBlocking { ide.dependencies.addLocalLibrary("app", b.toString(), "implementation") }
                assertTrue(second.success, second.message)

                assertEquals(listOf("core.jar", "core (2).jar"), localLibraries(ide))
                assertEquals(a.toString(), rootOf(ide, "core.jar"), "the first library still points at a/core.jar")
                assertEquals(b.toString(), rootOf(ide, "core (2).jar"))

                val again = runBlocking { ide.dependencies.addLocalLibrary("app", b.toString(), "implementation") }
                assertFalse(again.success, "the same file twice is still a duplicate")
            }
        }
    }

    private fun localLibraries(ide: IdeServices): List<String> =
        ide.modules().first { it.name == "app" }.dependencies.filterIsInstance<LibraryDependency>()
            .map { it.library.name }.filter { it.endsWith(".jar") }

    private fun rootOf(ide: IdeServices, name: String): String? =
        ide.store.workspace.libraryTable.byName(name)?.classesRoots?.singleOrNull()?.path?.let {
            Path.of(it).toAbsolutePath().normalize().toString()
        }

    private fun jar(path: Path, entry: String): Path {
        Files.createDirectories(path.parent)
        ZipOutputStream(Files.newOutputStream(path)).use { z ->
            z.putNextEntry(ZipEntry(entry)); z.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte())); z.closeEntry()
        }
        return path.toAbsolutePath().normalize()
    }
}
