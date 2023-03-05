package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.BuildFeatures

abstract class BuildFeaturesImpl : BuildFeatures {
    override var aidl: Boolean? = null
    override var compose: Boolean? = null
    override var buildConfig: Boolean? = null
    override var renderScript: Boolean? = null
    override var resValues: Boolean? = null
    override var shaders: Boolean? = null
    override var viewBinding: Boolean? = null
}