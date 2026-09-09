package dev.ide.core

import dev.ide.core.sync.UnrecognizedProjectMarker
import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a clone lands as. [ProjectManager.adoptFolderInPlace] has to make any directory under the projects
 * root listable and openable, and report which of the three cases it was, so the clone screen can warn about
 * the one that opens for editing only.
 */
class ClonedFolderAdoptionTest {

    private fun write(dir: Path, rel: String, text: String) {
        val file = dir.resolve(rel)
        Files.createDirectories(file.parent)
        file.writeText(text.trimIndent())
    }

    /** A repository holding no build system at all: adopted anyway, listed, marked, and browsable. */
    @Test
    fun adoptsAFolderNothingRecognizesAndMarksIt() {
        withTempDir("clone-plain") { root ->
            val manager = ProjectManager.desktop(root.resolve("projects"))
            val cloned = manager.projectsRoot.resolve("some-repo")
            write(cloned, "README.md", "# some-repo")
            write(cloned, "src/main.py", "print('hi')")

            assertEquals(ImportableKind.NONE, manager.adoptFolderInPlace(cloned, origin = "https://host/some-repo.git"))

            val listed = manager.list()
            assertEquals(listOf("some-repo"), listed.map { it.name }, "an adopted clone shows in the picker")
            assertEquals(0, listed.first().moduleCount, "nothing was invented for it")
            assertFalse(listed.first().compatibility, "it is not a compatibility-mode import")

            manager.open(listed.first().rootPath).use { ide ->
                assertTrue(ide.isUnrecognizedProject(), "the editor has to be able to say what this is")
                assertEquals("https://host/some-repo.git", ide.unrecognizedProjectOrigin())
            }
        }
    }

    /** With no modules the curated Project view has nothing to curate, so it shows the real tree instead. */
    @Test
    fun showsTheRealTreeForAModuleLessProject() {
        withTempDir("clone-tree") { root ->
            val manager = ProjectManager.desktop(root.resolve("projects"))
            val cloned = manager.projectsRoot.resolve("some-repo")
            write(cloned, "README.md", "# some-repo")
            write(cloned, "src/main.py", "print('hi')")
            manager.adoptFolderInPlace(cloned)

            manager.open(cloned.toString()).use { ide ->
                val names = IdeServicesBackend(ide).files.fileTree().children.map { it.name }
                assertTrue("src" in names, "the sources must be reachable from the Project view; got $names")
                assertTrue("README.md" in names, "root files stay visible; got $names")
            }
        }
    }

    /** A cloned Gradle repository is still imported, so it opens in compatibility mode rather than empty. */
    @Test
    fun importsAClonedGradleRepository() {
        withTempDir("clone-gradle") { root ->
            val manager = ProjectManager.desktop(root.resolve("projects"))
            val cloned = manager.projectsRoot.resolve("gradle-repo")
            write(cloned, "settings.gradle", "rootProject.name = 'GradleRepo'\ninclude ':lib'")
            write(cloned, "build.gradle", "// top-level")
            write(cloned, "lib/build.gradle", "apply plugin: 'java-library'")
            write(cloned, "lib/src/main/java/com/example/Lib.java", "package com.example; public class Lib {}")

            assertEquals(ImportableKind.EXTERNAL, manager.adoptFolderInPlace(cloned))

            val listed = manager.list()
            assertEquals(1, listed.size)
            assertTrue(listed.first().compatibility, "a Gradle clone opens in compatibility mode")
            manager.open(listed.first().rootPath).use { ide ->
                assertFalse(ide.isUnrecognizedProject(), "an imported project is recognized")
                assertTrue(ide.moduleNames().isNotEmpty(), "the importer built the model")
            }
        }
    }

    /** A repository that already holds a CodeAssist workspace is adopted verbatim, model untouched. */
    @Test
    fun adoptsAClonedCodeAssistWorkspaceVerbatim() {
        withTempDir("clone-native") { root ->
            val manager = ProjectManager.desktop(root.resolve("projects"))
            // Author a real project, then treat its directory as the freshly-cloned one.
            manager.create("java-console", mapOf("name" to "Cloned App", "packageName" to "com.acme.cloned")).use { }
            val cloned = Path.of(manager.list().single().rootPath)

            assertEquals(ImportableKind.CODE_ASSIST, manager.adoptFolderInPlace(cloned))

            assertFalse(
                UnrecognizedProjectMarker.exists(cloned),
                "a project the IDE authored must not be marked unrecognized",
            )
            manager.open(cloned.toString()).use { ide ->
                assertFalse(ide.isUnrecognizedProject())
                assertEquals(listOf("app"), ide.moduleNames(), "the existing model survived adoption")
            }
        }
    }

    /** Adding a module retires the notice, so a project the user has set up stops being called unrecognized. */
    @Test
    fun stopsReportingUnrecognizedOnceTheProjectHasAModule() {
        withTempDir("clone-outgrown") { root ->
            val manager = ProjectManager.desktop(root.resolve("projects"))
            val cloned = manager.projectsRoot.resolve("outgrown")
            write(cloned, "README.md", "# outgrown")
            manager.adoptFolderInPlace(cloned)

            manager.open(cloned.toString()).use { ide ->
                assertTrue(ide.isUnrecognizedProject())
                val added = runBlocking {
                    IdeServicesBackend(ide).modules.createModule("app", "java-lib", null, emptyMap())
                }
                assertTrue(added.success, "module added for the test: ${'$'}{added.message}")
                assertFalse(ide.isUnrecognizedProject(), "a project with a module is no longer unrecognized")
            }
        }
    }
}
