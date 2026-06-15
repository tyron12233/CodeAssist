package dev.ide.model.impl

import dev.ide.model.Facet
import dev.ide.model.FacetKey

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

class FacetCodecRegistry {
    private val keysToCodec = LinkedHashMap<FacetKey<*>, FacetCodec<*>>()
    private val tablesToCodec = LinkedHashMap<String, FacetCodec<*>>()

    fun register(codec: FacetCodec<*>): FacetCodecRegistry {
        keysToCodec[codec.key] = codec
        tablesToCodec[codec.tomlTable] = codec
        return this
    }

    fun codecFor(key: FacetKey<*>): FacetCodec<*>? = keysToCodec[key]
    fun codecForTable(table: String): FacetCodec<*>? = tablesToCodec[table]

    @Suppress("UNCHECKED_CAST")
    fun encode(facet: Facet): FacetData? {
        val codec = (keysToCodec[facet.key] ?: return null) as FacetCodec<Facet>
        return FacetData(codec.tomlTable, codec.encode(facet))
    }

    fun decode(data: FacetData): Facet? = tablesToCodec[data.tomlTable]?.decode(data.values)
}
