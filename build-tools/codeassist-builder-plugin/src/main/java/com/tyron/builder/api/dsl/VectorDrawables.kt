package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

/**
 * DSL object used to configure `vector` drawable options.
 */
interface VectorDrawables {
    /**
     * Densities used when generating PNGs from vector drawables at build time. For the PNGs to be
     * generated, minimum SDK has to be below 21.
     *
     * If set to an empty collection, all special handling of vector drawables will be
     * disabled.
     *
     * See
     * [Supporting Multiple Screens](http://developer.android.com/guide/practices/screens_support.html).
     */
    @get:Incubating
    val generatedDensities: MutableSet<String>?

    /**
     * Densities used when generating PNGs from vector drawables at build time. For the PNGs to be
     * generated, minimum SDK has to be below 21.
     *
     * If set to an empty collection, all special handling of vector drawables will be
     * disabled.
     *
     * See
     * [Supporting Multiple Screens](http://developer.android.com/guide/practices/screens_support.html).
     */
    @Incubating
    fun generatedDensities(vararg densities: String)

    /**
     * Whether to use runtime support for `vector` drawables, instead of build-time support.
     *
     * See [Vector Asset Studio](http://developer.android.com/tools/help/vector-asset-studio.html).
     */
    @get:Incubating
    @set:Incubating
    var useSupportLibrary: Boolean?
}
