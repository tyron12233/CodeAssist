package dev.ide.core.plugins

import dev.ide.model.BuildSystemId
import dev.ide.model.FacetCodecRegistry
import dev.ide.model.FacetTemplate
import dev.ide.model.ModuleType
import dev.ide.model.ModuleTypeRegistry
import dev.ide.model.SourceSetTemplate
import dev.ide.model.impl.ProjectModel
import dev.ide.model.impl.open
import dev.ide.platform.PluginId
import dev.ide.testkit.testEnv
import kotlin.test.Test
import kotlin.test.assertEquals

class RequiredPluginsTest {

    private class Type(override val id: String) : ModuleType {
        override val displayName = id
        override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
        override fun defaultFacets(): List<FacetTemplate> = emptyList()
        override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
    }

    @Test
    fun namesTheInstalledPluginsBehindTheProjectsModulesAndNotTheBuiltIns() {
        testEnv("required-plugins") { env ->
            val types = ModuleTypeRegistry(env.platform.extensions)
            types.register(Type("dart"), PluginId("dev.codeassist.dart"))
            types.register(Type("java-lib"), PluginId("java-support"))
            types.register(Type("csharp"), PluginId("dev.codeassist.csharp"))
            val store = ProjectModel.open(env.dir, env.platform, FacetCodecRegistry())
            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.projects.single().beginModification().apply {
                addModule("app", types.resolve("dart"))
                addModule("lib", types.resolve("java-lib"))
                commit()
            }

            val required = RequiredPlugins.of(
                store.workspace.projects, env.platform.extensions,
                installed = setOf("dev.codeassist.dart", "dev.codeassist.csharp"),
            )
            // Dart's module type is used; C#'s is installed but nothing in the project uses it; java-support is
            // built in, so every IDE has it.
            assertEquals(listOf("dev.codeassist.dart"), required)
        }
    }
}
