package dev.ide.model.impl

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.LanguageLevel
import dev.ide.model.LibraryDependency
import dev.ide.model.ModuleDependency
import dev.ide.model.sync.ExternalFacet
import dev.ide.model.sync.ExternalLibrary
import dev.ide.model.sync.ExternalModule
import dev.ide.model.sync.ExternalModuleRef
import dev.ide.model.sync.ExternalProjectModel
import dev.ide.model.sync.ExternalSourceSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The applier turns an importer's snapshot into the live model: it creates the project bound to the
 * importer's build system, adds modules with their roots/dependencies/facets, re-derives them on a second
 * sync, and removes what the build files stopped declaring.
 */
class ExternalModelApplierTest {

    private val bazel = BuildSystemId("bazel")

    private fun snapshot(vararg modules: ExternalModule) =
        ExternalProjectModel(name = "imported", buildSystemId = bazel, modules = modules.toList())

    private fun appModule(
        dependencies: List<dev.ide.model.sync.ExternalDependency> = emptyList(),
        facets: List<ExternalFacet> = emptyList(),
    ) = ExternalModule(
        name = "app",
        dirRelPath = "apps/app",
        typeId = "java-lib",
        sourceSets = listOf(
            ExternalSourceSet(
                "main",
                DependencyScope.IMPLEMENTATION,
                mapOf("src/main/java" to setOf(ContentRole.SOURCE)),
            )
        ),
        dependencies = dependencies,
        facets = facets,
    )

    @Test
    fun createsProjectModulesRootsDependenciesAndFacets() = withWorkspace { platform, store ->
        platform.registerTestTypes()
        val model = snapshot(
            appModule(
                dependencies = listOf(
                    ExternalLibrary("com.squareup.okhttp3:okhttp:4.12.0", DependencyScope.IMPLEMENTATION),
                    ExternalModuleRef("core", DependencyScope.API),
                ),
                facets = listOf(ExternalFacet("java", mapOf("annotationProcessors" to listOf("dagger"), "preview" to true))),
            ),
            ExternalModule(name = "core", dirRelPath = "libs/core", typeId = "java-lib"),
        )

        val report = ExternalModelApplier(store).apply(model, LanguageLevel.JAVA_17)

        assertTrue(report.projectCreated)
        assertEquals(listOf("app", "core"), report.added.sorted())
        val project = store.workspace.projects.single()
        assertEquals(bazel, project.buildSystemId, "the project is bound to the importer's build system")

        val app = project.modules.single { it.name == "app" }
        // The module's directory comes from the snapshot, not from its name.
        assertTrue(app.dir.path.replace('\\', '/').endsWith("apps/app"), app.dir.path)
        assertTrue(app.sourceSets.single { it.name == "main" }.contentRoots.any { ContentRole.SOURCE in it.roles })
        assertTrue(app.dependencies.any { it is LibraryDependency && it.library.name == "com.squareup.okhttp3:okhttp:4.12.0" })
        val moduleDep = app.dependencies.filterIsInstance<ModuleDependency>().single()
        assertEquals("core", moduleDep.target.value)
        assertTrue(moduleDep.exported, "an api-scoped module dependency is exported")

        // The facet crossed the boundary as table + values and was decoded by the registered codec.
        val facet = assertNotNull(app.facets.get(JavaFacet.KEY))
        assertEquals(listOf("dagger"), facet.annotationProcessors)
        assertTrue(facet.preview)
    }

    /**
     * A refresh keeps a module's type, merges its roots and keeps its facets. A module the build now describes
     * differently (a multiplatform module imported for another target) is named in `recreate` and rebuilt.
     */
    @Test
    fun recreateRebuildsAModuleInsteadOfMergingIntoIt() {
        withWorkspace { platform, store ->
            platform.registerTestTypes()
            val applier = ExternalModelApplier(store)
            fun module(type: String, root: String, facets: List<ExternalFacet>) = ExternalModule(
                name = "app", dirRelPath = "app", typeId = type,
                sourceSets = listOf(ExternalSourceSet("main", DependencyScope.IMPLEMENTATION, mapOf(root to setOf(ContentRole.SOURCE)))),
                facets = facets,
            )
            applier.apply(snapshot(module("java-lib", "src/androidMain/kotlin", listOf(ExternalFacet("java", mapOf("preview" to true))))))

            // Without it, the old shape survives: that is what a target switch must not leave behind.
            applier.apply(snapshot(module("java-cli", "src/desktopMain/kotlin", emptyList())))
            val refreshed = store.workspace.projects.single().modules.single()
            assertEquals("java-lib", refreshed.type.id)
            assertTrue(refreshed.sourceSets.single().contentRoots.any { it.dir.path.endsWith("androidMain/kotlin") })

            val report = applier.apply(snapshot(module("java-cli", "src/desktopMain/kotlin", emptyList())), recreate = setOf("app"))
            val app = store.workspace.projects.single().modules.single()
            assertEquals("java-cli", app.type.id)
            assertEquals(
                listOf("desktopMain/kotlin"),
                app.sourceSets.single { it.name == "main" }.contentRoots.map { it.dir.path.replace('\\', '/').substringAfter("src/") },
            )
            assertEquals(null, app.facets.get(JavaFacet.KEY), "the old shape's facet is gone")
            assertEquals(listOf("app"), report.added)
        }
    }

    @Test
    fun secondSyncReplacesDependenciesAndKeepsTheProject() = withWorkspace { platform, store ->
        platform.registerTestTypes()
        val applier = ExternalModelApplier(store)
        applier.apply(
            snapshot(appModule(dependencies = listOf(ExternalLibrary("a:one:1.0")))),
            LanguageLevel.JAVA_17,
        )

        val report = applier.apply(
            snapshot(appModule(dependencies = listOf(ExternalLibrary("b:two:2.0")))),
            LanguageLevel.JAVA_17,
        )

        assertTrue(!report.projectCreated && report.added.isEmpty())
        assertEquals(listOf("app"), report.updated)
        val declared = store.workspace.projects.single().modules.single()
            .dependencies.filterIsInstance<LibraryDependency>().map { it.library.name }
        assertEquals(listOf("b:two:2.0"), declared, "the build files are the source of truth for declarations")
    }

    @Test
    fun removeAbsentDropsModulesTheBuildFilesNoLongerDeclare() = withWorkspace { platform, store ->
        platform.registerTestTypes()
        val applier = ExternalModelApplier(store)
        applier.apply(
            snapshot(appModule(), ExternalModule(name = "core", dirRelPath = "core", typeId = "java-lib")),
            LanguageLevel.JAVA_17,
        )

        // Without removeAbsent nothing is dropped: the IDE model may hold modules no sync produced.
        val kept = applier.apply(snapshot(appModule()), LanguageLevel.JAVA_17)
        assertEquals(emptyList(), kept.removed)
        assertEquals(2, store.workspace.projects.single().modules.size)

        val removed = applier.apply(snapshot(appModule()), LanguageLevel.JAVA_17, removeAbsent = true)
        assertEquals(listOf("core"), removed.removed)
        assertEquals(listOf("app"), store.workspace.projects.single().modules.map { it.name })
    }
}
