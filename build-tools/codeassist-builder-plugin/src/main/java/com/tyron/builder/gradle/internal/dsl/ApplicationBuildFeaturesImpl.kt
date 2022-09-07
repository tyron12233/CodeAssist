package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.ApplicationBuildFeatures

abstract class ApplicationBuildFeaturesImpl: BuildFeaturesImpl(), ApplicationBuildFeatures {
    override var dataBinding: Boolean? = null
    override var mlModelBinding: Boolean? = null
}