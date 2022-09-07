package com.tyron.builder.gradle.internal.publishing

/**
 * A data class contains all the components that this variant is published to.
 */
data class VariantPublishingInfo(
    val components: List<ComponentPublishingInfo>
)