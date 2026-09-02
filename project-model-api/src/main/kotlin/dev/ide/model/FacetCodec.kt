package dev.ide.model

import dev.ide.platform.ExtensionPoint

/**
 * Persistence adapter for a facet type. The core cannot serialize a [Facet] generically (the api only
 * exposes its [FacetKey]), so each facet-bearing plugin contributes a codec that maps its facet to and
 * from a declarative `module.toml` table (e.g. `[android]`, `[java]`). Facets without a registered
 * codec round-trip in memory but are skipped by persistence (the loader/saver simply ignores them).
 *
 * Codec values must use only TOML-representable types (String, Long, Boolean, and lists of those), so
 * that `encode` and a load-from-disk produce structurally equal values.
 */
interface FacetCodec<T : Facet> {
    val key: FacetKey<T>
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
