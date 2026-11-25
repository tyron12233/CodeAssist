package dev.ide.core.plugins

import dev.ide.core.plugins.ProjectPlugins.Entry
import dev.ide.core.plugins.ProjectPlugins.State
import dev.ide.model.BuildSystemId
import dev.ide.model.FacetCodecRegistry
import dev.ide.model.FacetTemplate
import dev.ide.model.ModuleType
import dev.ide.model.ModuleTypeRegistry
import dev.ide.model.SourceSetTemplate
import dev.ide.model.impl.ModelPersistence
import dev.ide.model.impl.ProjectModel
import dev.ide.model.impl.open
import dev.ide.platform.PluginId
import dev.ide.testkit.testEnv
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectPluginsTest {

    private class Type(override val id: String) : ModuleType {
        override val displayName = id
        override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
        override fun defaultFacets(): List<FacetTemplate> = emptyList()
        override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
    }

    private val dart = Entry("dev.codeassist.dart", "Dart")

    @Test
    fun aRecordedPluginThatIsNotRunningIsKeptByAnOpenThatCannotSeeIt() {
        // The project opened without the Dart plugin: its model names no plugin, and that must not erase the record.
        val merged = ProjectPlugins.merge(listOf(dart), required = emptyList(), isRunning = { false })
        assertEquals(listOf(dart), merged)
    }

    @Test
    fun aRecordedPluginThatRunsAndIsNoLongerNeededIsDropped() {
        val merged = ProjectPlugins.merge(listOf(dart), required = emptyList(), isRunning = { true })
        assertEquals(emptyList(), merged)
    }

    @Test
    fun recordsThePluginBehindAModuleTypeAndNamesItWhenItIsGone() {
        testEnv("project-plugins") { env ->
            val types = ModuleTypeRegistry(env.platform.extensions)
            types.register(Type("dart"), PluginId(dart.id))
            val store = ProjectModel.open(env.dir, env.platform, FacetCodecRegistry())
            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.projects.single().beginModification().apply { addModule("app", types.resolve("dart")); commit() }
            ModelPersistence.save(store)
            val root = Paths.get(env.dir.toString())

            ProjectPlugins.record(root, store.workspace.projects, env.platform.extensions, mapOf(dart.id to dart.name)) { true }
            assertEquals(listOf(dart), ProjectPlugins.read(root))

            fun check(present: Set<String>, disabled: Set<String> = emptySet(), consented: Set<String> = setOf(dart.id), runningAtOpen: Set<String>? = null, running: Boolean = false) =
                ProjectPlugins.check(
                    root, env.platform.extensions, present, disabled, consented,
                    installedExternally = present, isRunning = { running }, runningAtOpen = runningAtOpen,
                    workspace = ModelPersistence.load(root.toString()),
                )

            assertEquals(State.NOT_INSTALLED, check(present = emptySet()).missing.single().state)
            assertEquals(State.TURNED_OFF, check(present = setOf(dart.id), disabled = setOf(dart.id)).missing.single().state)
            assertEquals(State.NEEDS_PERMISSION, check(present = setOf(dart.id), consented = emptySet()).missing.single().state)
            // Running now, and running when the project opened: nothing is missing.
            assertTrue(check(present = setOf(dart.id), running = true, runningAtOpen = setOf(dart.id)).ok)
            // Running now, but it arrived after the open project was opened.
            assertEquals(State.NOT_LOADED, check(present = setOf(dart.id), running = true, runningAtOpen = emptySet()).missing.single().state)
            assertEquals("Dart", check(present = emptySet()).missing.single().name)
        }
    }

    @Test
    fun aProjectWithNoRecordNamesTheModuleTypesNothingProvides() {
        testEnv("project-plugins-unknown") { env ->
            val other = ModuleTypeRegistry(env.platform.extensions)
            val store = ProjectModel.open(env.dir, env.platform, FacetCodecRegistry())
            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            // No plugin provides "dart" here: it resolves to an unknown type, and is saved by its id.
            store.workspace.projects.single().beginModification().apply { addModule("app", other.resolve("dart")); commit() }
            ModelPersistence.save(store)
            val root = Paths.get(env.dir.toString())
            assertFalse(Files.exists(ProjectPlugins.file(root)))

            val check = ProjectPlugins.check(
                root, env.platform.extensions, present = emptySet(), disabled = emptySet(), consented = emptySet(),
                installedExternally = emptySet(), isRunning = { false }, runningAtOpen = null,
                workspace = ModelPersistence.load(root.toString()),
            )
            assertEquals(listOf("dart"), check.unknownModuleTypes)
            assertFalse(check.ok)
        }
    }
}
