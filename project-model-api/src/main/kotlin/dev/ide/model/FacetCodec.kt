package dev.ide.model

import dev.ide.platform.ExtensionPoint
import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.PluginId
import dev.ide.platform.impl.ExtensionRegistryImpl

/**
 * Persistence adapter for a facet type. The core cannot serialize a [Facet] generically (the api only
 * exposes its [FacetKey]), so each facet-bearing plugin contributes a codec that maps its facet to and
 * from a declarative `module.toml` table (e.g. `[android]`, `[java]`).
 *
 * A codec is **required**, not optional: [ModifiableModule.putFacet] refuses a facet whose key has none, and
 * [FacetContainer.get] answers null for one. A facet type and its codec are two halves of one contribution,
 * and a plugin registers both or neither. What does survive without a codec is a `module.toml` table nobody
 * claims: it is carried through a load/save cycle untouched, so a project edited with a plugin disabled does
 * not lose that plugin's configuration.
 *
 * Codec values must use only TOML-representable types (String, Long, Boolean, and lists of those), so
 * that `encode` and a load-from-disk produce structurally equal values.
 */
interface FacetCodec<T : Facet> {
    val key: FacetKey<T>

    /**
     * The `module.toml` table this facet occupies. It is the on-disk identity of the facet and a flat global
     * namespace across every plugin, so prefer a name that reads as the domain (`android`, `python`) and
     * expect the last registration for a table to win.
     */
    val tomlTable: String
    fun encode(facet: T): Map<String, Any?>
    fun decode(values: Map<String, Any?>): T
}

/**
 * Plugins contribute facet codecs here; the model persistence resolves a facet's codec against it, so a
 * facet-bearing plugin's `module.toml` codec is a registration like every other capability.
 *
 * Declared alongside [Facet] rather than with the registry that reads it: contributing a codec is something
 * a plugin does, and a plugin should not have to depend on the model's implementation to do it.
 */
val FACET_CODEC_EP = ExtensionPoint<FacetCodec<*>>("platform.facetCodec")

/** A facet persisted as the name of its `module.toml` table plus its declarative values. */
data class FacetData(
    val tomlTable: String,
    val values: Map<String, Any?>,
)

/**
 * An [ExtensionRegistry]-backed view over [FACET_CODEC_EP]: the host builds one over its application registry
 * and a facet-bearing plugin contributes its codec through it (or directly on the EP). A registration wins over
 * earlier ones for the same key/table (last-write-wins). The no-arg constructor makes a standalone registry
 * over its own private EP registry, for tests or one-off persistence with no host.
 *
 * Contributing through a plugin's `PluginRegistration` is equivalent and is what a plugin should do, because the
 * registration is then attributed to it and removed when it unloads:
 *
 * ```
 * override fun register(reg: PluginRegistration) {
 *     reg.register(FACET_CODEC_EP, PythonFacetCodec)
 * }
 * ```
 *
 * The codec list is read through the registry on every lookup rather than captured, so a plugin that loads
 * after this object was built is still seen.
 */
class FacetCodecRegistry(private val extensions: ExtensionRegistry) {
    constructor() : this(ExtensionRegistryImpl())

    private val codecs: List<FacetCodec<*>> get() = extensions.extensions(FACET_CODEC_EP)

    fun register(codec: FacetCodec<*>, plugin: PluginId = DEFAULT_PLUGIN): FacetCodecRegistry {
        extensions.register(FACET_CODEC_EP, codec, plugin)
        return this
    }

    /**
     * The codec for [key]. [FacetKey] has reference identity, so this matches the key *instance* the codec
     * declares: a facet and its codec must name the same `val`, and two keys sharing an id are still two keys.
     */
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
