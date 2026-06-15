package dev.ide.model.impl

import dev.ide.model.BuildSystemId
import dev.ide.model.DependencyScope
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryRef
import dev.ide.model.ModuleId
import dev.ide.model.ProjectId
import dev.ide.model.event.DependenciesChanged
import dev.ide.model.event.ModuleAdded
import dev.ide.model.event.ProjectAdded
import dev.ide.model.event.ProjectModelEvent
import dev.ide.model.event.ProjectModelListener
import dev.ide.model.event.ProjectModelTopics
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransactionEventsTest {

    private fun subscribe(platform: dev.ide.platform.impl.PlatformCore, sink: MutableList<ProjectModelEvent>) {
        platform.messageBus.connect().subscribe(ProjectModelTopics.CHANGES, ProjectModelListener { sink.addAll(it) })
    }

    @Test
    fun commitPublishesTypedEventsInOrder() = withWorkspace { platform, store ->
        val events = CopyOnWriteArrayList<ProjectModelEvent>()
        subscribe(platform, events)
        val javaLib = ModuleTypeRegistry(platform.extensions).resolve("java-lib")

        store.workspace.beginModification().apply {
            addProject("app", BuildSystemId.NATIVE, store.vfs.root())
            commit()
        }
        store.workspace.projects.single().beginModification().apply {
            addModule("core", javaLib).addDependency(LibraryDependency(LibraryRef("g:a:1"), DependencyScope.IMPLEMENTATION))
            commit()
        }

        // A new module reports ModuleAdded only (its initial deps are part of "added", not a change).
        assertEquals(
            listOf(ProjectAdded(ProjectId("app")), ModuleAdded(ProjectId("app"), ModuleId("core"))),
            events.toList(),
        )

        events.clear()
        store.workspace.projects.single().beginModification().apply {
            module(ModuleId("core")).addDependency(LibraryDependency(LibraryRef("g:b:1"), DependencyScope.API))
            commit()
        }
        assertEquals(listOf(DependenciesChanged(ProjectId("app"), ModuleId("core"))), events.toList())
    }

    @Test
    fun disposedTransactionPublishesNothingAndDoesNotMutate() = withWorkspace { platform, store ->
        val events = CopyOnWriteArrayList<ProjectModelEvent>()
        subscribe(platform, events)

        store.workspace.beginModification().apply {
            addProject("app", BuildSystemId.NATIVE, store.vfs.root())
            dispose() // rollback
        }

        assertTrue(events.isEmpty())
        assertTrue(store.workspace.projects.isEmpty())
    }

    @Test
    fun readersHoldAConsistentSnapshotAcrossACommit() = withWorkspace { platform, store ->
        val javaLib = ModuleTypeRegistry(platform.extensions).resolve("java-lib")
        store.workspace.beginModification().apply {
            addProject("app", BuildSystemId.NATIVE, store.vfs.root())
            commit()
        }

        val captured = store.workspace.projects // snapshot captured now (project has 0 modules)

        store.workspace.projects.single().beginModification().apply {
            addModule("core", javaLib)
            commit()
        }

        assertEquals(0, captured.single().modules.size, "captured snapshot must be unaffected by the later commit")
        assertEquals(1, store.workspace.projects.single().modules.size, "a fresh read sees the new module")
    }

    @Test
    fun committedSnapshotIsNormalizedAndExportedFollowsApi() = withWorkspace { platform, store ->
        val javaLib = ModuleTypeRegistry(platform.extensions).resolve("java-lib")
        store.workspace.beginModification().apply {
            addProject("app", BuildSystemId.NATIVE, store.vfs.root())
            commit()
        }
        store.workspace.projects.single().beginModification().apply {
            // add out of canonical scope order; an API entry left as exported=false
            addModule("core", javaLib).apply {
                addDependency(LibraryDependency(LibraryRef("impl"), DependencyScope.IMPLEMENTATION))
                addDependency(LibraryDependency(LibraryRef("api"), DependencyScope.API, exported = false))
            }
            commit()
        }
        val deps = store.workspace.projects.single().modules.single().dependencies
        // API sorts before IMPLEMENTATION, and exported is normalized to api semantics.
        assertEquals(listOf("api", "impl"), deps.map { (it as LibraryDependency).library.name })
        assertEquals(listOf(true, false), deps.map { it.exported })
    }
}
