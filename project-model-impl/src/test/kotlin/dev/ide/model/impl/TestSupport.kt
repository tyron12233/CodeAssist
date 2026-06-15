package dev.ide.model.impl

import dev.ide.model.BuildSystemId
import dev.ide.model.Facet
import dev.ide.model.FacetKey
import dev.ide.model.FacetTemplate
import dev.ide.model.ModuleType
import dev.ide.model.SourceSetTemplate
import dev.ide.platform.PluginId
import dev.ide.platform.impl.PlatformCore
import java.nio.file.Files
import java.nio.file.Path

/** A minimal [ModuleType] stand-in (real ones ship in java-support/android-support). */
class TestModuleType(override val id: String) : ModuleType {
    override val displayName: String get() = id
    override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
    override fun defaultFacets(): List<FacetTemplate> = emptyList()
    override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
}

/** A sample facet + codec to exercise facet persistence (analogous to a real JavaFacet). */
data class JavaFacet(val annotationProcessors: List<String>, val preview: Boolean) : Facet {
    override val key: FacetKey<*> get() = KEY
    companion object {
        val KEY = FacetKey<JavaFacet>("java")
    }
}

object JavaFacetCodec : FacetCodec<JavaFacet> {
    override val key: FacetKey<JavaFacet> = JavaFacet.KEY
    override val tomlTable: String = "java"
    override fun encode(facet: JavaFacet): Map<String, Any?> =
        linkedMapOf("annotationProcessors" to facet.annotationProcessors, "preview" to facet.preview)

    override fun decode(values: Map<String, Any?>): JavaFacet = JavaFacet(
        annotationProcessors = (values["annotationProcessors"] as? List<*>)?.map { it as String } ?: emptyList(),
        preview = values["preview"] as? Boolean ?: false,
    )
}

fun PlatformCore.registerTestTypes() {
    val types = ModuleTypeRegistry(extensions)
    types.register(TestModuleType("java-lib"), PluginId("java-support"))
    types.register(TestModuleType("java-cli"), PluginId("java-support"))
}

/** Open a fresh workspace in a throwaway temp dir; clean everything up afterward. */
internal fun withWorkspace(block: (PlatformCore, ProjectModelStore) -> Unit) {
    val dir: Path = Files.createTempDirectory("codeassist-ws")
    val platform = PlatformCore()
    platform.registerTestTypes()
    try {
        block(platform, ProjectModel.open(dir, platform, FacetCodecRegistry().register(JavaFacetCodec)))
    } finally {
        platform.dispose()
        dir.toFile().deleteRecursively()
    }
}
