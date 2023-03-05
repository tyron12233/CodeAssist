package com.tyron.builder.gradle.internal.core.dsl

import com.tyron.builder.gradle.internal.publishing.VariantPublishingInfo

/**
 * Contains the final dsl info computed from the DSL object model (extension, default config,
 * build type, flavors) that are needed by publishable components.
 */
interface PublishableComponentDslInfo {
    val publishInfo: VariantPublishingInfo
}