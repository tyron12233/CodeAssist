package com.tyron.builder.gradle.internal.variant

import com.tyron.builder.gradle.internal.BuildTypeData
import com.tyron.builder.gradle.internal.DefaultConfigData
import com.tyron.builder.gradle.internal.ProductFlavorData
import com.tyron.builder.gradle.internal.dependency.SourceSetManager

/**
 * Model containing the inputs for the variants to be created.
 */
interface VariantInputModel<
        DefaultConfigT : com.tyron.builder.api.dsl.DefaultConfig,
        BuildTypeT : com.tyron.builder.api.dsl.BuildType,
        ProductFlavorT : com.tyron.builder.api.dsl.ProductFlavor,
        SigningConfigT : com.tyron.builder.api.dsl.ApkSigningConfig> {

    val defaultConfigData: DefaultConfigData<DefaultConfigT>

    val buildTypes: Map<String, BuildTypeData<BuildTypeT>>

    val productFlavors: Map<String, ProductFlavorData<ProductFlavorT>>

    val signingConfigs: Map<String, SigningConfigT>

    val sourceSetManager: SourceSetManager
}