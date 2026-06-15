package dev.ide.lang.jdt.context

import dev.ide.model.BuildSystemId
import dev.ide.model.DependencyScope
import dev.ide.model.FacetTemplate
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryKind
import dev.ide.model.LibraryRef
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import dev.ide.model.ModuleType
import dev.ide.model.SourceSetTemplate
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.model.impl.ProjectModelStore
import dev.ide.platform.PluginId
import dev.ide.platform.impl.PlatformCore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression: the **analysis/completion** classpath ([ModuleCompilationContext]) must honor `api` export
 * semantics across a module dependency. An exported (`api`) library of a dependency belongs on the
 * dependent module's classpath; an `implementation` library of a dependency does not.
 *
 * The model's own `ClasspathAssemblyTest` covers the *build* classpath ([dev.ide.model.Module.classpath]);
 * this guards the separate `collect()` walk that the editor's analyzer uses — the path where an `api`
 * library (e.g. Gson) added to a library module once failed to resolve in a dependent module's analysis
 * even though completion still surfaced it from the index.
 */
class ModuleCompilationContextExportTest {

    private class TestType(override val id: String) : ModuleType {
        override val displayName get() = id
        override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
        override fun defaultFacets(): List<FacetTemplate> = emptyList()
        override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
    }

    private fun withWorkspace(block: (ProjectModelStore) -> Unit) {
        val dir = Files.createTempDirectory("mcc-export")
        val platform = PlatformCore()
        ModuleTypeRegistry(platform.extensions).register(TestType("java-lib"), PluginId("java-support"))
        try {
            block(ProjectModel.open(dir, platform, FacetCodecRegistry()))
        } finally {
            platform.dispose()
            dir.toFile().deleteRecursively()
        }
    }

    private fun ProjectModelStore.createJar(name: String) {
        workspace.libraryTable.create(name).apply {
            kind = LibraryKind.JAR
            addClassesRoot(vfs.fileFor(rootPath.resolve("libs/$name.jar")))
            commit()
        }
    }

    private fun ProjectModelStore.moduleNamed(name: String) =
        workspace.projects.single().modules.first { it.name == name }

    @Test
    fun exportedLibraryOfADependencyReachesTheDependentAnalysisClasspath() = withWorkspace { store ->
        // app --(module dep)--> lib ;  lib --api--> apiLib ,  lib --implementation--> implLib
        val javaLib = TestType("java-lib")
        store.createJar("apiLib")
        store.createJar("implLib")
        store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
        store.workspace.projects.single().beginModification().apply {
            addModule("lib", javaLib).apply {
                addDependency(LibraryDependency(LibraryRef("apiLib"), DependencyScope.API, exported = true))
                addDependency(LibraryDependency(LibraryRef("implLib"), DependencyScope.IMPLEMENTATION, exported = false))
            }
            addModule("app", javaLib).apply {
                addDependency(ModuleDependency(ModuleId("lib"), DependencyScope.API, exported = true))
            }
            commit()
        }

        val classpath = ModuleCompilationContext.create(store.workspace, store.moduleNamed("app")).classpath.entries
        val paths = classpath.map { it.root.path }
        assertTrue(paths.any { it.contains("apiLib.jar") }, "exported (api) library of a dependency must be on the dependent's analysis classpath: $paths")
        assertFalse(paths.any { it.contains("implLib.jar") }, "an implementation library of a dependency must NOT leak to the dependent: $paths")
    }

    @Test
    fun aModulesOwnDirectLibraryIsAlwaysOnItsClasspath() = withWorkspace { store ->
        // Even a non-exported (implementation) library declared directly on the module is on its own classpath.
        store.createJar("directLib")
        store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
        store.workspace.projects.single().beginModification().apply {
            addModule("app", TestType("java-lib")).apply {
                addDependency(LibraryDependency(LibraryRef("directLib"), DependencyScope.IMPLEMENTATION, exported = false))
            }
            commit()
        }

        val paths = ModuleCompilationContext.create(store.workspace, store.moduleNamed("app")).classpath.entries.map { it.root.path }
        assertTrue(paths.any { it.contains("directLib.jar") }, "a module's own direct library must be on its classpath: $paths")
    }
}
