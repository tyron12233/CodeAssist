package com.tyron.builder.api.variant

import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

interface HasAndroidResources {

    /**
     * Make a [ResValue.Key] to interact with [resValues]'s [MapProperty]
     */
    fun makeResValueKey(type: String, name: String): ResValue.Key

    /**
     * Variant's [ResValue] which will be generated.
     */
    val resValues: MapProperty<ResValue.Key, ResValue>

    /**
     * Variant's is pseudo locales enabled, initialized by the corresponding DSL elements.
     */
    val pseudoLocalesEnabled: Property<Boolean>
}