package dev.ide.model

import dev.ide.platform.impl.ExtensionRegistryImpl

/**
 * A standalone [FacetCodecRegistry] over its own private extension registry, for tests and one-off
 * persistence with no host.
 *
 * A function rather than the secondary constructor it used to be. It was moved out when
 * `ExtensionRegistryImpl` was still JVM-only and a constructor could not be added to a common class from
 * another source set; `:platform-core` is multiplatform now, so only the shape is left. Call sites read
 * identically either way, so it stays a function rather than churning them back.
 */
fun FacetCodecRegistry(): FacetCodecRegistry = FacetCodecRegistry(ExtensionRegistryImpl())
