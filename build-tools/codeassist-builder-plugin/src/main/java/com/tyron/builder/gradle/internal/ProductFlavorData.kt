package com.tyron.builder.gradle.internal

import com.tyron.builder.api.dsl.ProductFlavor
import com.tyron.builder.gradle.internal.api.DefaultAndroidSourceSet

/**
 * Class containing a ProductFlavor and associated data (sourcesets)
 *
 * This generated during DSL execution and is used for variant creation
 */
class ProductFlavorData<ProductFlavorT : ProductFlavor>(
    val productFlavor: ProductFlavorT,
    sourceSet: DefaultAndroidSourceSet,
    testFixturesSourceSet: DefaultAndroidSourceSet?,
    androidTestSourceSet: DefaultAndroidSourceSet?,
    unitTestSourceSet: DefaultAndroidSourceSet?
) : VariantDimensionData(sourceSet, testFixturesSourceSet, androidTestSourceSet, unitTestSourceSet)