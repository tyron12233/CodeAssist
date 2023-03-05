package com.tyron.builder.gradle.internal

import com.tyron.builder.gradle.internal.api.DefaultAndroidSourceSet
import com.tyron.builder.core.ComponentType
import com.google.common.base.Preconditions

/** Common parts of build type and product flavor data objects.  */
abstract class VariantDimensionData(
    val sourceSet: DefaultAndroidSourceSet,
    val testFixturesSourceSet: DefaultAndroidSourceSet?,
    private val androidTestSourceSet: DefaultAndroidSourceSet?,
    private val unitTestSourceSet: DefaultAndroidSourceSet?
) {
    fun getTestSourceSet(type: ComponentType): DefaultAndroidSourceSet? {
        Preconditions.checkState(
            type.isTestComponent,
            "Unknown test variant type $type"
        )

        return if (type.isApk) {
            androidTestSourceSet
        } else {
            unitTestSourceSet
        }
    }
}