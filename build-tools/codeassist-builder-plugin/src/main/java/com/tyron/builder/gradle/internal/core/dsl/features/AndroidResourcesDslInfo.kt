package com.tyron.builder.gradle.internal.core.dsl.features

import com.tyron.builder.api.dsl.AndroidResources
import com.tyron.builder.api.variant.ResValue
import com.tyron.builder.model.VectorDrawablesOptions
import com.google.common.collect.ImmutableSet

/**
 * Contains the final dsl info computed from the DSL object model (extension, default config,
 * build type, flavors) that are needed by components that support AndroidResources.
 */
interface AndroidResourcesDslInfo {
    val androidResources: AndroidResources

    val resourceConfigurations: ImmutableSet<String>

    val vectorDrawables: VectorDrawablesOptions

    val isPseudoLocalesEnabled: Boolean

    val isCrunchPngs: Boolean?

    @Deprecated("Can be removed once the AaptOptions crunch method is removed.")
    val isCrunchPngsDefault: Boolean

    /**
     * Returns a list of generated resource values.
     *
     *
     * Items can be either fields (instance of [com.tyron.builderer.model.ClassField]) or
     * comments (instance of String).
     *
     * @return a list of items.
     */
    fun getResValues(): Map<ResValue.Key, ResValue>
}