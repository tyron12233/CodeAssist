package dev.ide.model.impl

import dev.ide.model.BuildSystemId
import dev.ide.model.ModuleId
import dev.ide.model.WORKSPACE_SERVICE
import dev.ide.model.module
import dev.ide.model.workspace
import dev.ide.platform.Disposable
import dev.ide.platform.ServiceKey
import dev.ide.platform.impl.ApplicationContainer
import dev.ide.platform.impl.PlatformCore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The three service scopes integrated against the real [ProjectModelStore]: parent fallback
 * (module -> workspace -> application), the bound [Workspace]/[Module] a factory receives, prompt
 * disposal of a module's services when the module is removed, and an application singleton surviving a
 * project swap (the workspace container is fresh, the application one is shared).
 */
class ScopedServiceLifecycleTest {

    private class Probe : Disposable {
        var disposed = false
            private set
        override fun dispose() { disposed = true }
    }

    private fun ProjectModelStore.addProjectWithModule(): ModuleId {
        val javaLib = ModuleTypeRegistry(extensions).resolve("java-lib")
        workspace.beginModification().apply { addProject("app", BuildSystemId.NATIVE, vfs.root()); commit() }
        workspace.projects.single().beginModification().apply { addModule("core", javaLib); commit() }
        return workspace.projects.single().modules.single().id
    }

    @Test
    fun scopesFallBackAndExposeTheBoundObjects() = withWorkspace { _, store ->
        val moduleId = store.addProjectWithModule()
        val module = store.workspace.projects.single().modules.single()

        // WORKSPACE_SERVICE yields the bound workspace, from the workspace AND (via fallback) a module.
        assertSame(store.workspace, store.workspaceContainer.getService(WORKSPACE_SERVICE))
        assertSame(store.workspace, module.service(WORKSPACE_SERVICE))

        // An application service is reachable from a module by walking module -> workspace -> application.
        val appKey = ServiceKey<String>("test.app")
        store.appContainer.registerService(appKey) { "app-value" }
        assertEquals("app-value", module.service(appKey))

        // A module factory reaches its Module and its Workspace through the scope helpers.
        val modKey = ServiceKey<String>("test.mod")
        store.moduleContainer(moduleId).registerService(modKey) { "mod:${module().name}:${workspace().projects.size}" }
        assertEquals("mod:core:1", module.service(modKey))
    }

    @Test
    fun removingAModuleDisposesItsContainer() = withWorkspace { _, store ->
        val moduleId = store.addProjectWithModule()
        val key = ServiceKey<Probe>("test.disposable")
        val container = store.moduleContainer(moduleId)
        container.registerService(key) { Probe() }
        val probe = container.getService(key)
        assertFalse(probe.disposed)

        store.workspace.projects.single().beginModification().apply { removeModule(moduleId); commit() }

        assertTrue(probe.disposed, "the module's container (and its services) is disposed on ModuleRemoved")
    }

    @Test
    fun applicationSingletonSurvivesAProjectSwap() {
        val app = ApplicationContainer()
        val key = ServiceKey<Probe>("test.appSingleton")
        app.registerService(key) { Probe() }

        val dir1 = Files.createTempDirectory("codeassist-ws1")
        val dir2 = Files.createTempDirectory("codeassist-ws2")
        val platform1 = PlatformCore().also { it.registerTestTypes() }
        val platform2 = PlatformCore().also { it.registerTestTypes() }
        try {
            val store1 = ProjectModel.open(dir1, platform1, FacetCodecRegistry().register(JavaFacetCodec), app)
            val probe1 = store1.workspaceContainer.getService(key) // resolves the app singleton via fallback
            val ws1 = store1.workspaceContainer
            store1.close() // closes the workspace container, NOT the shared application container

            val store2 = ProjectModel.open(dir2, platform2, FacetCodecRegistry().register(JavaFacetCodec), app)
            val probe2 = store2.workspaceContainer.getService(key)

            assertSame(probe1, probe2, "the application singleton is shared across the swap")
            assertFalse(probe1.disposed, "closing the first project must not dispose application services")
            assertTrue(ws1 !== store2.workspaceContainer, "each open project gets a fresh workspace container")
            store2.close()
        } finally {
            platform1.dispose(); platform2.dispose()
            dir1.toFile().deleteRecursively(); dir2.toFile().deleteRecursively()
        }
    }
}
