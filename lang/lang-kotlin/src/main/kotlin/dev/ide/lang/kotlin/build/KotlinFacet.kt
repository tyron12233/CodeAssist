package dev.ide.lang.kotlin.build

import dev.ide.model.Facet
import dev.ide.model.FacetCodec
import dev.ide.model.FacetKey

/**
 * Per-module Kotlin compilation settings, as the `[kotlin]` table of `module.toml`.
 *
 * It exists for [commonSourceRoots], which cannot be inferred from anything else the model holds and
 * decides whether an imported multiplatform module compiles at all.
 */
data class KotlinFacet(
    /**
     * The module's COMMON source roots, relative to the module directory: `src/commonMain/kotlin` and any
     * intermediate source set above the platform one.
     *
     * A CodeAssist module is a single compilation, while a Kotlin Multiplatform module is several; importing
     * one collapses the source sets that feed a single target (`commonMain` + `androidMain`, plus whatever
     * sits between them) into that one compilation. K2 accepts exactly this, but only when it is told which
     * of the sources form the common fragment: multiplatform mode alone still rejects the pair with
     * "'expect' and corresponding 'actual' are declared in the same module".
     *
     * Empty (the default) is an ordinary single-platform module, compiled with no multiplatform flags at all.
     */
    val commonSourceRoots: List<String> = emptyList(),
) : Facet {

    /** True when this module compiles as one multiplatform compilation. */
    val multiplatform: Boolean get() = commonSourceRoots.isNotEmpty()

    override val key: FacetKey<KotlinFacet> get() = KEY

    companion object {
        val KEY = FacetKey<KotlinFacet>("kotlin")
    }
}

/**
 * Round-trips [KotlinFacet] through the `[kotlin]` table. Like the Android codec, a default-valued field is
 * an absent key rather than a written default, so an encoded facet and a saved-then-reloaded one compare
 * equal and an untouched module gains no table.
 */
object KotlinFacetCodec : FacetCodec<KotlinFacet> {
    override val key get() = KotlinFacet.KEY
    override val tomlTable = "kotlin"

    override fun encode(facet: KotlinFacet): Map<String, Any?> = buildMap {
        if (facet.commonSourceRoots.isNotEmpty()) put("commonSourceRoots", facet.commonSourceRoots)
    }

    override fun decode(values: Map<String, Any?>): KotlinFacet = KotlinFacet(
        commonSourceRoots = (values["commonSourceRoots"] as? List<*>).orEmpty().mapNotNull { it as? String },
    )
}
