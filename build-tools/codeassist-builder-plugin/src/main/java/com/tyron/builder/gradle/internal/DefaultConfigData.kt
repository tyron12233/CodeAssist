package com.tyron.builder.gradle.internal

import com.tyron.builder.api.dsl.DefaultConfig
import com.tyron.builder.gradle.internal.api.DefaultAndroidSourceSet

/**
 * Class containing the DefaultConfig and associated data (sourcesets)
 *
 * This generated during DSL execution and is used for variant creation
 */
class DefaultConfigData<DefaultConfigT : DefaultConfig>(
    val defaultConfig: DefaultConfigT,
    sourceSet: DefaultAndroidSourceSet,
    testFixturesSourceSet: DefaultAndroidSourceSet?,
    androidTestSourceSet: DefaultAndroidSourceSet?,
    unitTestSourceSet: DefaultAndroidSourceSet?
) : VariantDimensionData(sourceSet, testFixturesSourceSet, androidTestSourceSet, unitTestSourceSet)