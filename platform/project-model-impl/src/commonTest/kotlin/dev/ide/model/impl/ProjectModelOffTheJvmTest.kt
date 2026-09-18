package dev.ide.model.impl

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.FacetCodecRegistry
import dev.ide.model.LanguageLevel
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryKind
import dev.ide.model.LibraryRef
import dev.ide.model.ModuleType
import dev.ide.model.ModuleTypeRegistry
import dev.ide.model.SourceSetTemplate
import dev.ide.model.FacetTemplate
import dev.ide.model.event.ProjectModelListener
import dev.ide.model.event.ProjectModelTopics
import dev.ide.platform.PluginId
import dev.ide.platform.fileInfo
import dev.ide.platform.impl.PlatformCore
import dev.ide.platform.resolvePath
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The project model, on whatever platform this runs on.
 *
 * Everything below it was already portable or has been made so — the message bus, the scoped service
 * containers, the model lock, the file system, the path arithmetic — but each of those passed its own tests
 * on one side of a seam. What none of them says is whether a workspace BUILT through the transaction api,
 * committed under the model lock, published on the bus, written to `.platform/workspace.json` and a
 * per-module `module.toml`, and read back, still describes the same project.
 *
 * That is four subsystems and two on-disk formats agreeing, and the only way to find out is to run it.
 *
 * It is not a second copy of `PersistenceRoundTripTest`: that one is exhaustive about the FORMAT (every
 * dependency kind, every facet shape, SDKs, exclusions) and runs where the format was written. This one is
 * about the model being alive at all off the JVM.
 */
class ProjectModelOffTheJvmTest {

    private val dir = scratchDir("project-model")

    @AfterTest
    fun cleanUp() {
        deleteTree(dir)
    }

    /** A module type with nothing in it; the registry only has to hand back what was registered. */
    private class PlainType(override val id: String) : ModuleType {
        override val displayName: String get() = id
        override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
        override fun defaultFacets(): List<FacetTemplate> = emptyList()
        override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
    }

    private fun openWorkspace(platform: PlatformCore): ProjectModelStore {
        ModuleTypeRegistry(platform.extensions).register(PlainType("java-lib"), PluginId("test"))
        return ProjectModel.open(dir, platform, FacetCodecRegistry())
    }

    @Test
    fun aWorkspaceIsBuiltCommittedSavedAndReadBack() {
        val platform = PlatformCore()
        val store = openWorkspace(platform)
        val types = ModuleTypeRegistry(platform.extensions)

        store.workspace.beginModification().apply {
            addProject("app", BuildSystemId.NATIVE, store.vfs.root())
            commit()
        }
        store.workspace.projects.single().beginModification().apply {
            addModule("core", types.byId("java-lib")!!).apply {
                languageLevel = LanguageLevel.JAVA_17
                addSourceSet(
                    SourceSetTemplate(
                        "main", DependencyScope.IMPLEMENTATION,
                        mapOf(
                            "src/main/java" to setOf(ContentRole.SOURCE),
                            "src/main/resources" to setOf(ContentRole.RESOURCE),
                        ),
                    ),
                )
            }
            commit()
        }
        store.save()
        store.close()

        // The format really is on disk, at the paths the model names.
        assertTrue(
            fileInfo(resolvePath(dir, ".platform/workspace.json")) != null,
            "workspace.json must be written",
        )
        assertTrue(fileInfo(resolvePath(dir, "core/module.toml")) != null, "the module manifest must be written")

        // ... and a second open reads the same project back.
        val reopened = ProjectModel.open(dir, PlatformCore(), FacetCodecRegistry())
        try {
            val project = reopened.data.projects.single()
            assertEquals("app", project.name)
            val module = project.modules.single()
            assertEquals("core", module.name)
            assertEquals("java-lib", module.typeId)
            assertEquals(LanguageLevel.JAVA_17, module.languageLevel)
            assertEquals(listOf("main"), module.sourceSets.map { it.name })
        } finally {
            reopened.close()
        }
    }

    /** A commit publishes on the message bus — the fan-out that replaced the JVM's dynamic proxy. */
    @Test
    fun aCommitReachesASubscriber() {
        val platform = PlatformCore()
        val store = openWorkspace(platform)
        val seen = ArrayList<String>()
        val connection = platform.messageBus.connect()
        connection.subscribe(
            ProjectModelTopics.CHANGES,
            ProjectModelListener { events -> events.forEach { seen += it::class.simpleName.orEmpty() } },
        )
        try {
            store.workspace.beginModification().apply {
                addProject("app", BuildSystemId.NATIVE, store.vfs.root())
                commit()
            }
            assertTrue(seen.isNotEmpty(), "a commit must publish; the topic's fan-out is what delivers it")
        } finally {
            connection.dispose()
            store.close()
        }
    }

    /** The model revision is what a separate process compares against to know its copy is stale. */
    @Test
    fun everyCommitBumpsTheGeneration() {
        val platform = PlatformCore()
        val store = openWorkspace(platform)
        try {
            val before = store.generation
            store.workspace.beginModification().apply {
                addProject("app", BuildSystemId.NATIVE, store.vfs.root())
                commit()
            }
            assertTrue(store.generation > before, "a commit is a model change: $before -> ${store.generation}")
        } finally {
            store.close()
        }
    }

    /** The view layer resolves a module's directories through the portable file system. */
    @Test
    fun theViewsResolveRealPaths() {
        val platform = PlatformCore()
        val store = openWorkspace(platform)
        val types = ModuleTypeRegistry(platform.extensions)
        try {
            store.workspace.beginModification().apply {
                addProject("app", BuildSystemId.NATIVE, store.vfs.root())
                commit()
            }
            store.workspace.projects.single().beginModification().apply {
                addModule("core", types.byId("java-lib")!!)
                commit()
            }

            val module = store.workspace.projects.single().modules.single()
            assertEquals(resolvePath(dir, "core"), module.dir.path, "a module's dir is root-relative")
            val output = assertNotNull(module.outputDir, "a module has an output directory")
            assertTrue(output.path.startsWith(dir), "and it is under the workspace root: ${output.path}")
        } finally {
            store.close()
        }
    }

    /** A library declared in one session is still declared in the next. */
    @Test
    fun aLibraryDependencySurvivesTheRoundTrip() {
        val platform = PlatformCore()
        val store = openWorkspace(platform)
        val types = ModuleTypeRegistry(platform.extensions)

        store.workspace.beginModification().apply {
            addProject("app", BuildSystemId.NATIVE, store.vfs.root())
            commit()
        }
        val library = LibraryRef("com.squareup.okhttp3:okhttp:4.12.0")
        store.workspace.libraryTable.create(library.name).apply {
            kind = LibraryKind.JAR
            addClassesRoot(store.vfs.fileFor(resolvePath(dir, "libs/okhttp-4.12.0.jar")))
            commit()
        }
        store.workspace.projects.single().beginModification().apply {
            addModule("core", types.byId("java-lib")!!).apply {
                addDependency(LibraryDependency(library, DependencyScope.IMPLEMENTATION))
            }
            commit()
        }
        store.save()
        store.close()

        val reopened = ProjectModel.open(dir, PlatformCore(), FacetCodecRegistry())
        try {
            assertEquals(
                listOf("com.squareup.okhttp3:okhttp:4.12.0"),
                reopened.data.libraries.map { it.name },
                "the library table is part of the saved model",
            )
        } finally {
            reopened.close()
        }
    }
}
