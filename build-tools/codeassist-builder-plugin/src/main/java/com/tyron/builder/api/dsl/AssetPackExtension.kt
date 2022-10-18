package com.tyron.builder.api.dsl

import org.gradle.api.Incubating
import org.gradle.api.provider.Property

/**
 * Extension properties for the Asset Pack plugin.
 */
interface AssetPackExtension {
    /**
     * The split name to assign to the asset pack.
     */
    @get:Incubating
    val packName: Property<String>
    /**
     * Contains the dynamic delivery settings for the asset pack.
     */
    @get:Incubating
    val dynamicDelivery: DynamicDelivery
    /** @see dynamicDelivery */
    @Incubating
    fun dynamicDelivery(action: DynamicDelivery.() -> Unit)
}
