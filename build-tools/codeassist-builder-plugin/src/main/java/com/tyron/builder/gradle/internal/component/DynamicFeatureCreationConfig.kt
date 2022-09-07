package com.tyron.builder.gradle.internal.component

import org.gradle.api.provider.Provider

interface DynamicFeatureCreationConfig: VariantCreationConfig, ApkCreationConfig {

    val featureName: Provider<String>
    val resOffset: Provider<Int>
    val baseModuleDebuggable: Provider<Boolean>
}