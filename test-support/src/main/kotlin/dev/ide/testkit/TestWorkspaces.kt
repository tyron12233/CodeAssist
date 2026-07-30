package dev.ide.testkit

import dev.ide.model.BuildSystemId
import dev.ide.model.FacetTemplate
import dev.ide.model.ModuleType
import dev.ide.model.SourceSetTemplate
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.model.impl.ProjectModelStore
import dev.ide.platform.PluginId
import dev.ide.platform.impl.PlatformCore
import java.nio.file.Path

/** A minimal [ModuleType] stand-in for tests (real ones ship in the java/android support modules). */
class TestModuleType(override val id: String) : ModuleType {
    override val displayName: String get() = id
    override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
    override fun defaultFacets(): List<FacetTemplate> = emptyList()
    override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
}

/**
 * Register test [ModuleType]s on this platform's extension registry. With no arguments registers the common
 * `java-lib` and `java-cli` types; otherwise registers a [TestModuleType] for each given id.
 */
fun PlatformCore.registerTestTypes(vararg ids: String) {
    val types = ModuleTypeRegistry(extensions)
    val names = if (ids.isEmpty()) arrayOf("java-lib", "java-cli") else ids
    for (id in names) types.register(TestModuleType(id), PluginId("java-support"))
}

/** Open a fresh [ProjectModelStore] rooted at [dir] with optional facet [codecs]. */
fun openWorkspace(
    dir: Path,
    platform: PlatformCore,
    codecs: FacetCodecRegistry = FacetCodecRegistry(),
): ProjectModelStore = ProjectModel.open(dir, platform, codecs)

/**
 * Open a workspace in a throwaway temp dir with test module types registered, run [block], then dispose the
 * platform and delete the dir. Replaces the per-module `withWorkspace` / `buildWorkspace` boilerplate; the
 * project/module graph is constructed inside [block] off the supplied [ProjectModelStore].
 */
inline fun <T> withWorkspace(
    prefix: String = "codeassist-ws",
    codecs: FacetCodecRegistry = FacetCodecRegistry(),
    block: (PlatformCore, ProjectModelStore) -> T,
): T = testEnv(prefix) { env ->
    env.platform.registerTestTypes()
    block(env.platform, openWorkspace(env.dir, env.platform, codecs))
}
