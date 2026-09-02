package dev.ide.model.impl

import dev.ide.model.FacetCodec
import dev.ide.model.Facet
import dev.ide.model.FacetKey
import dev.ide.platform.PluginId
import dev.ide.platform.impl.PlatformCore

/** A minimal [dev.ide.model.ModuleType] stand-in (shared with the rest of the framework's tests). */
typealias TestModuleType = dev.ide.testkit.TestModuleType

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

/** Open a fresh workspace in a throwaway temp dir (with the sample [JavaFacetCodec]); clean up afterward. */
internal fun withWorkspace(block: (PlatformCore, ProjectModelStore) -> Unit) =
    dev.ide.testkit.withWorkspace(codecs = FacetCodecRegistry().register(JavaFacetCodec)) { platform, store ->
        block(platform, store)
    }
