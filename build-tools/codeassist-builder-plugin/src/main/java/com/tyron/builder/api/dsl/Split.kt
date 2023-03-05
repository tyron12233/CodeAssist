package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

/** Base data representing how an FULL_APK should be split for a given dimension (density, abi). */
interface Split {
    /** Whether to split in this dimension. */
    @get:Incubating
    @set:Incubating
    var isEnable: Boolean

    /** Includes some values */
    @Incubating
    fun include(vararg includes: String)

    /** Excludes some values */
    @Incubating
    fun exclude(vararg excludes: String)

    /**
     * Resets the list of included split configuration.
     *
     * Use this before calling include, in order to manually configure the list of configuration
     * to split on, rather than excluding from the default list.
     */
    @Incubating
    fun reset()
}