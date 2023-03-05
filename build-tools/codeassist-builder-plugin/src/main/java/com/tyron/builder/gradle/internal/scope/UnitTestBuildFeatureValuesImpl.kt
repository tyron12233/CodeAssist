package com.tyron.builder.gradle.internal.scope

class UnitTestBuildFeatureValuesImpl(
    delegate: BuildFeatureValues
): BuildFeatureValues by delegate {

    override val renderScript: Boolean = false
}