package dev.ide.model.impl

import dev.ide.model.Facet
import dev.ide.model.FacetKey
import dev.ide.platform.ExtensionPoint
import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.PluginId
import dev.ide.platform.impl.ExtensionRegistryImpl

/**
 * Persistence adapter for a facet type. The core cannot serialize a [Facet] generically (the api only
 * exposes its [FacetKey]), so each facet-bearing plugin contributes a codec that maps its facet to and
 * from a declarative `module.toml` table (e.g. `[android]`, `[java]`). Facets without a registered
 * codec round-trip in memory but are skipped by persistence (the loader/saver simply ignores them).
 *
 * Codec values must use only TOML-representable types (String, Long, Boolean, and lists of those), so
 * that `encode` and a load-from-disk produce structurally equal [FacetData.values].
 */
interface FacetCodec<T : Facet> {
    val key: FacetKey<T>
    val tomlTable: String
    fun encode(facet: T): Map<String, Any?>
    fun decode(values: Map<String, Any?>): T
}

/** Plugins contribute facet codecs here; the model persistence resolves a facet's codec against it. This is
 *  the last of the model registries to become extension-point-backed (matching [ModuleTypeRegistry] /
 *  [FileIconRegistry] / [ProjectTemplateRegistry]), so a facet-bearing plugin's `module.toml` codec is a
 *  registration like every other capability. */
val FACET_CODEC_EP = ExtensionPoint<FacetCodec<*>>("platform.facetCodec")

/**
 * An [ExtensionRegistry]-backed view over [FACET_CODEC_EP]: the host builds one over its application registry
 * and a facet-bearing plugin contributes its codec through it (or directly on the EP). A registration wins over
 * earlier ones for the same key/table (last-write-wins, as the old map did). The no-arg constructor makes a
 * standalone registry over its own private EP registry — for tests / one-off persistence with no host.
 */
class FacetCodecRegistry(private val extensions: ExtensionRegistry) {
    constructor() : this(ExtensionRegistryImpl())

    private val codecs: List<FacetCodec<*>> get() = extensions.extensions(FACET_CODEC_EP)

    fun register(codec: FacetCodec<*>, plugin: PluginId = DEFAULT_PLUGIN): FacetCodecRegistry {
        extensions.register(FACET_CODEC_EP, codec, plugin)
        return this
    }

    fun codecFor(key: FacetKey<*>): FacetCodec<*>? = codecs.lastOrNull { it.key == key }
    fun codecForTable(table: String): FacetCodec<*>? = codecs.lastOrNull { it.tomlTable == table }

    @Suppress("UNCHECKED_CAST")
    fun encode(facet: Facet): FacetData? {
        val codec = (codecFor(facet.key) ?: return null) as FacetCodec<Facet>
        return FacetData(codec.tomlTable, codec.encode(facet))
    }

    fun decode(data: FacetData): Facet? = codecForTable(data.tomlTable)?.decode(data.values)

    private companion object {
        val DEFAULT_PLUGIN = PluginId("facet-codec")
    }
}
