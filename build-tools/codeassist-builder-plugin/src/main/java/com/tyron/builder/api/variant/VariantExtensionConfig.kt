package com.tyron.builder.api.variant

import org.gradle.api.Incubating

/**
 * Configuration object passed to the lambda responsible for creating a
 * [com.android.build.api.variant.VariantExtension] for each [com.android.build.api.variant.Variant]
 * instance.
 *
 * @param VariantT the type of [com.android.build.api.variant.Variant] object.
 */
@Incubating
interface VariantExtensionConfig<VariantT: Variant> {

    /**
     * Gets the variant object the [com.android.build.api.variant.VariantExtension] should be
     * associated with.
     */
    val variant: VariantT

    /**
     * Returns the project (across variants) extension registered through the
     * [com.android.build.api.extension.DslExtension.projectExtensionType] API.
     */
    fun <T> projectExtension(extensionType: Class<T>): T

    /**
     * Returns the [variant] specific extension registered through the
     * [com.android.build.api.extension.DslExtension.buildTypeExtensionType] API.
     *
     * @return the custom extension for the [variant]'s build type.
     */
    fun <T> buildTypeExtension(extensionType: Class<T>): T

    /**
     * Returns the [variant] specific extension registered through the
     * [com.android.build.api.extension.DslExtension.productFlavorExtensionType] API.
     *
     * @return a [List] of [T] extension for all the defined product flavors in the project.
     * The order of the elements is the same as the order of product flavors returned by the
     * [Variant.productFlavors]
     */
    fun <T> productFlavorsExtensions(extensionType: Class<T>): List<T>
}