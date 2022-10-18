package com.tyron.builder.api.variant

import com.tyron.builder.model.BuildType
import com.tyron.builder.model.ProductFlavor

/**
 * Interface for variant control, allowing to query a variant for some base
 * data and allowing to disable some variants.
 */
@Deprecated("Use AndroidComponentsExtension.beforeVariants API to disable specific variants")
interface VariantFilter {

    /**
     * Whether or not to ignore this particular variant. Default is false.
     */
    var ignore: Boolean

    /**
     * Returns the ProductFlavor that represents the default config.
     */
    val defaultConfig: ProductFlavor

    /**
     * Returns the Build Type.
     */
    val buildType: BuildType

    /**
     * Returns the list of flavors, or an empty list.
     */
    val flavors: List<ProductFlavor>

    /**
     * Returns the unique variant name.
     */
    val name: String
}
