package com.tyron.builder.gradle.internal

import com.tyron.builder.api.dsl.BuildType
import com.tyron.builder.gradle.internal.api.DefaultAndroidSourceSet

/**
 * Class containing a BuildType and associated data (Sourceset for instance).
 *
 * This generated during DSL execution and is used for variant creation
 */
class BuildTypeData<BuildTypeT : BuildType>(
    val buildType: BuildTypeT,
    sourceSet: DefaultAndroidSourceSet,
    testFixturesSourceSet: DefaultAndroidSourceSet?,
    androidTestSourceSet: DefaultAndroidSourceSet?,
    unitTestSourceSet: DefaultAndroidSourceSet?
) : VariantDimensionData(sourceSet, testFixturesSourceSet, androidTestSourceSet, unitTestSourceSet)