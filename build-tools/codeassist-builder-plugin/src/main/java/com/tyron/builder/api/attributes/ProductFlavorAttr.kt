package com.tyron.builder.api.attributes

import org.gradle.api.Incubating
import org.gradle.api.attributes.Attribute

import org.gradle.api.Named

/**
 * Type for the attribute holding ProductFlavor information.
 *
 *
 * There can be more than one attribute associated to each
 * [org.gradle.api.artifacts.Configuration] object, where each represents a different flavor
 * dimension.
 *
 * The key should be created with `ProductFlavorAttr.of(flavorDimension)`.
 *
 */
interface ProductFlavorAttr : Named {
    @Incubating
    companion object {
        /**
         * Returns a product flavor attribute for the given flavor dimension
         *
         * @param flavorDimension The name of the flavor dimension, as specified in the Android
         *                        Gradle Plugin DSL.
         */
        @Incubating
        @JvmStatic
        fun of(flavorDimension: String) : Attribute<ProductFlavorAttr> {
            return Attribute.of("com.android.build.api.attributes.ProductFlavor:$flavorDimension", ProductFlavorAttr::class.java)
        }
    }
}