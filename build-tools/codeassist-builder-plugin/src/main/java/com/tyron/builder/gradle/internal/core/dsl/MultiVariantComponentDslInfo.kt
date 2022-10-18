package com.tyron.builder.gradle.internal.core.dsl

import com.tyron.builder.api.dsl.ProductFlavor
import com.tyron.builder.gradle.internal.variant.DimensionCombination

/**
 * Represents the dsl info for a component that supports multiple variants.
 */
interface MultiVariantComponentDslInfo: ComponentDslInfo, DimensionCombination {
    /** The list of product flavors. Items earlier in the list override later items.  */
    val productFlavorList: List<ProductFlavor>

    override val buildType: String?
        get() = componentIdentity.buildType
    override val productFlavors: List<Pair<String, String>>
        get() = componentIdentity.productFlavors
}