package com.tyron.builder.api.dsl

import com.google.common.collect.ListMultimap
import org.gradle.api.Incubating

/**
 * Options for configuring scoped shader options.
 */
@Incubating
interface Shaders {
    /**
     * The list of glslc args.
     */
    val glslcArgs: MutableList<String>

    /**
     * Adds options to the list of glslc args.
     */
    fun glslcArgs(vararg options: String)

    /**
     * The list of scoped glsl args.
     */
    val scopedGlslcArgs: ListMultimap<String, String>

    /**
     * Adds options to the list of scoped glsl args.
     */
    fun glslcScopedArgs(key: String, vararg options: String)
}
