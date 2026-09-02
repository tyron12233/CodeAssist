package dev.ide.model.impl

import dev.ide.model.FACET_CODEC_EP
import dev.ide.model.Facet
import dev.ide.model.FacetCodec
import dev.ide.model.FacetKey
import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.PluginId
import dev.ide.platform.impl.ExtensionRegistryImpl

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
