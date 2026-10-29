package dev.ide.core

import dev.ide.core.project.ProjectManager
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.UiInstallState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deleting a project leaves nothing the picker, the store or a reopen can trip over: a store row that said
 * "Open in editor" for a deleted project used to open what a half-finished delete left behind, as an empty
 * project.
 */
class ProjectDeleteTest {

    private fun touch(path: Path): Path {
        Files.createDirectories(path.parent)
        path.writeText("x")
        return path
    }

    @Test
    fun deletesThroughTheTrashAndOffersOnlyRemnantsAsLeftovers() {
        withTempDir("delete-trash") { root ->
            val projectsRoot = root.resolve("projects")
            val manager = ProjectManager.desktop(projectsRoot)
            val rootPath = manager.create("java-console", mapOf("name" to "Demo", "packageName" to "com.acme.demo"))
                .use { it.workspaceRoot.toString() }

            assertTrue(manager.delete(rootPath))
            assertFalse(Files.exists(Path.of(rootPath)))
            assertEquals(emptyList(), manager.list())
            assertEquals(emptyList(), manager.leftoverFolders(), "an emptied trash is not a leftover")

            // What an interrupted delete leaves: hidden entries only, `.platform` among them.
            val husk = projectsRoot.resolve("husk")
            touch(husk.resolve(".platform/caches/source-index/seg.bin"))
            touch(husk.resolve(".gitignore"))
            // A folder someone copied in by hand, and a clone still fetching: neither is ours to remove.
            val copied = projectsRoot.resolve("copied")
            touch(copied.resolve("src/Main.java"))
            touch(copied.resolve(".platform/caches/x.bin"))
            val cloning = projectsRoot.resolve("cloning")
            touch(cloning.resolve(".git/HEAD"))
            // Something a delete could not remove yet.
            touch(projectsRoot.resolve(".trash/old-1/.platform/stuck.bin"))

            assertEquals(
                setOf(".trash", "husk"),
                manager.leftoverFolders().map { it.fileName.toString() }.toSet(),
            )
            assertTrue(manager.deleteLeftoverFolders())
            assertFalse(Files.exists(husk))
            assertFalse(Files.exists(projectsRoot.resolve(".trash")))
            assertTrue(Files.exists(copied.resolve("src/Main.java")))
            assertTrue(Files.exists(cloning.resolve(".git/HEAD")))
        }
    }

    @Test
    fun purgesTheTrashAtTheNextStart() {
        withTempDir("delete-purge") { root ->
            val projectsRoot = root.resolve("projects")
            val stuck = touch(projectsRoot.resolve(".trash/demo-1/app/src/Main.java"))
            ProjectManager.desktop(projectsRoot)
            // The purge runs on its own thread.
            val deadline = System.currentTimeMillis() + 10_000
            while (Files.exists(stuck) && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertFalse(Files.exists(stuck))
        }
    }

    /**
     * The loaded project is closed before it is deleted, the store forgets it, and the folder's remains,
     * should any come back, open as nothing rather than as an empty project.
     */
    @Test
    fun deletingTheLoadedProjectClosesItAndTheStoreForgetsIt() {
        withTempDir("delete-loaded") { root ->
            val projectsRoot = root.resolve("projects")
            val manager = ProjectManager.desktop(projectsRoot)
            val backend = IdeServicesBackend(initial = null, manager = manager)
            try {
                runBlocking {
                    val created = backend.projects.createProject(
                        "java-console", mapOf("name" to "Shop", "packageName" to "com.acme.shop"),
                    )
                    val rootPath = created.rootPath!!
                    assertEquals(1, backend.projects.projectEpoch.value)
                    touch(manager.storageRoot.resolve("store/installed.txt")).writeText("shop-item\t$rootPath\n")
                    assertEquals(UiInstallState.INSTALLED, backend.store.installProgress().value["shop-item"]?.state)

                    assertTrue(backend.projects.deleteProject(rootPath))
                    assertEquals(0, backend.projects.projectEpoch.value, "no project is open any more")
                    assertNull(backend.servicesOrNull)
                    assertFalse(Files.exists(Path.of(rootPath)))
                    assertNull(backend.store.installProgress().value["shop-item"], "the store offers Install again")

                    // Remains of the folder: not a project, so neither opened nor counted as installed.
                    touch(Path.of(rootPath).resolve(".platform/caches/source-index/seg.bin"))
                    assertFalse(backend.projects.openProject(rootPath))
                    assertEquals(0, backend.projects.projectEpoch.value)
                    val fresh = IdeServicesBackend(initial = null, manager = manager)
                    try {
                        assertNull(fresh.store.installProgress().value["shop-item"])
                    } finally {
                        fresh.close()
                    }

                    // A later open is a new epoch, not a reused one.
                    val again = backend.projects.createProject(
                        "java-console", mapOf("name" to "Shop 2", "packageName" to "com.acme.shop"),
                    )
                    assertTrue(again.success, again.message)
                    assertEquals(2, backend.projects.projectEpoch.value)
                }
            } finally {
                backend.close()
            }
        }
    }
}
