package dev.ide.model.impl.graph

import dev.ide.model.BuildSystemId
import dev.ide.model.DependencyScope
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import dev.ide.model.ProjectId
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.withWorkspace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphTest {

    private fun apiDep(target: String) = ModuleDependency(ModuleId(target), DependencyScope.API, exported = true)

    @Test
    fun moduleGraphOrdersTopologicallyAndComputesReverseDependents() = withWorkspace { platform, store ->
        val javaLib = ModuleTypeRegistry(platform.extensions).resolve("java-lib")
        store.workspace.beginModification().apply { addProject("app", BuildSystemId.NATIVE, store.vfs.root()); commit() }
        store.workspace.projects.single().beginModification().apply {
            addModule("c", javaLib)
            addModule("b", javaLib).addDependency(apiDep("c"))
            addModule("a", javaLib).addDependency(apiDep("b"))
            commit()
        }
        val graph = store.workspace.projects.single().moduleGraph()

        assertEquals(
            listOf(listOf("c"), listOf("b"), listOf("a")),
            graph.topologicalOrder().map { level -> level.map { it.name } },
        )
        assertTrue(graph.detectCycles().isEmpty())
        assertEquals(setOf("a", "b"), graph.reverseDependents(ModuleId("c")).map { it.name }.toSet())
    }

    @Test
    fun moduleGraphDetectsCycles() = withWorkspace { platform, store ->
        val javaLib = ModuleTypeRegistry(platform.extensions).resolve("java-lib")
        store.workspace.beginModification().apply { addProject("app", BuildSystemId.NATIVE, store.vfs.root()); commit() }
        store.workspace.projects.single().beginModification().apply {
            addModule("x", javaLib).addDependency(apiDep("y"))
            addModule("y", javaLib).addDependency(apiDep("x"))
            commit()
        }
        val cycles = store.workspace.projects.single().moduleGraph().detectCycles()
        assertTrue(cycles.isNotEmpty(), "x <-> y is a cycle")
        assertEquals(setOf("x", "y"), cycles.first().members.map { it.value }.toSet())
    }

    @Test
    fun projectGraphDerivesCrossProjectEdges() = withWorkspace { platform, store ->
        val javaLib = ModuleTypeRegistry(platform.extensions).resolve("java-lib")
        store.workspace.beginModification().apply {
            addProject("libProj", BuildSystemId.NATIVE, store.vfs.fileFor(store.rootPath.resolve("lib")))
            addProject("appProj", BuildSystemId.NATIVE, store.vfs.fileFor(store.rootPath.resolve("app")))
            commit()
        }
        store.workspace.projects.first { it.name == "libProj" }.beginModification().apply {
            addModule("shared", javaLib); commit()
        }
        store.workspace.projects.first { it.name == "appProj" }.beginModification().apply {
            addModule("client", javaLib).addDependency(apiDep("shared")); commit()
        }
        val pg = store.workspace.projectGraph()

        assertEquals(listOf(ProjectId("libProj")), pg.dependenciesOf(ProjectId("appProj")).map { it.to })
        assertEquals(emptyList(), pg.dependenciesOf(ProjectId("libProj")).map { it.to })
        assertEquals(
            listOf(listOf("libProj"), listOf("appProj")),
            pg.topologicalOrder().map { level -> level.map { it.name } },
        )
        assertTrue(pg.detectCycles().isEmpty())
    }
}
