package dev.ide.core

import dev.ide.model.ContentRole
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises user-managed source sets: explicitly adding a typed source root, and the convention-aware
 * auto-detect that turns a `resources` folder created under a source-set base into a Java resources root.
 */
class SourceSetManagementTest {

    /** The roles of the (single) content root whose leaf folder is [dirName] in [moduleName], or null. */
    private fun IdeServices.rolesOf(moduleName: String, dirName: String): Set<ContentRole>? =
        modules().first { it.name == moduleName }.sourceSets
            .flatMap { it.contentRoots }
            .firstOrNull { Paths.get(it.dir.path).fileName.toString() == dirName }?.roles

    @Test
    fun addSourceRootCreatesDirAndRegistersRole() {
        val dir = Files.createTempDirectory("ide-srcset")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val created = assertNotNull(ide.addSourceRoot("core", "main", "kotlin", setOf(ContentRole.SOURCE)))
            assertTrue(Files.isDirectory(created), "the new source root dir is created on disk")
            assertEquals(setOf(ContentRole.SOURCE), ide.rolesOf("core", "kotlin"))
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun creatingResourcesFolderUnderSourceSetBaseAutoRegisters() {
        val dir = Files.createTempDirectory("ide-autodetect")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val backend = IdeServicesBackend(ide)
            val moduleRoot = assertNotNull(ide.moduleRoot(ide.modules().first { it.name == "core" }))
            val base = moduleRoot.resolve("src").resolve("main") // sibling of the existing src/main/java root

            // `resources` under the source-set base → auto-registered as a Java resources root.
            assertNotNull(backend.createDirectory(base.toString(), "resources"))
            assertEquals(setOf(ContentRole.RESOURCE), ide.rolesOf("core", "resources"))

            // A non-convention name stays a plain folder (no content root added).
            assertNotNull(backend.createDirectory(base.toString(), "templates"))
            assertNull(ide.rolesOf("core", "templates"))

            // `resources` NOT under a source-set base (here, directly in the module root) is left alone.
            assertNotNull(backend.createDirectory(moduleRoot.toString(), "resources"))
            val resourceRoots = ide.modules().first { it.name == "core" }
                .sourceSets.flatMap { it.contentRoots }.filter { ContentRole.RESOURCE in it.roles }
            assertEquals(1, resourceRoots.size, "only the source-set-base resources folder is registered")
        }
        dir.toFile().deleteRecursively()
    }
}
