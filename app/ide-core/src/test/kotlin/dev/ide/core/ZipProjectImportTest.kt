package dev.ide.core

import dev.ide.core.project.ImportableKind
import dev.ide.core.project.ProjectManager
import dev.ide.testkit.withTempDir
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A Gradle project handed over as a `.zip`: unpacked, found inside its download wrapper, imported like a picked
 * folder (compatibility mode, model built from the scripts), and the staging folder cleared afterwards.
 */
class ZipProjectImportTest {

    private fun zip(file: Path, entries: Map<String, String>) {
        ZipOutputStream(Files.newOutputStream(file)).use { out ->
            for ((name, text) in entries) {
                out.putNextEntry(ZipEntry(name)); out.write(text.toByteArray()); out.closeEntry()
            }
        }
    }

    @Test
    fun importsTheGradleProjectInsideADownloadedZip() {
        withTempDir("zip-import") { root ->
            val manager = ProjectManager.desktop(root.resolve("projects"))
            val archive = root.resolve("gradle-repo-main.zip")
            zip(archive, mapOf(
                "gradle-repo-main/settings.gradle" to "rootProject.name = 'GradleRepo'\ninclude ':lib'",
                "gradle-repo-main/build.gradle" to "// top-level",
                "gradle-repo-main/lib/build.gradle" to "apply plugin: 'java-library'",
                "gradle-repo-main/lib/src/main/java/com/example/Lib.java" to "package com.example; public class Lib {}",
                "gradle-repo-main/lib/build/classes/Lib.class" to "stale",
            ))

            val contents = assertNotNull(manager.extractProjectArchive(archive.toString()))
            assertEquals(listOf("gradle-repo-main"), contents.projects.map { it.relativePath })
            assertEquals(ImportableKind.EXTERNAL, contents.projects.single().kind)
            val staging = contents.root

            manager.importProjectFolder(contents.projects.single().path)!!.use { ide ->
                assertTrue(ide.moduleNames().isNotEmpty(), "the importer built the model")
                assertTrue(Files.exists(ide.workspaceRoot.resolve("lib/src/main/java/com/example/Lib.java")))
                assertFalse(Files.exists(ide.workspaceRoot.resolve("lib/build")), "build outputs are not imported")
            }
            assertFalse(Files.exists(staging), "the unpacked copy is removed once imported")
            val listed = manager.list()
            assertEquals(1, listed.size)
            assertTrue(listed.single().compatibility, "a Gradle zip opens in compatibility mode")
        }
    }

    @Test
    fun aZipWithoutAProjectLeavesNothingBehind() {
        withTempDir("zip-empty") { root ->
            val manager = ProjectManager.desktop(root.resolve("projects"))
            val archive = root.resolve("notes.zip")
            zip(archive, mapOf("notes/readme.txt" to "hi"))
            val contents = assertNotNull(manager.extractProjectArchive(archive.toString()))
            assertTrue(contents.projects.isEmpty())
            assertFalse(Files.exists(contents.root))
            assertEquals(emptyList(), manager.list())
        }
    }
}
