package com.tyron.builder.gradle.internal.publishing

import com.tyron.builder.gradle.internal.dsl.AbstractPublishing

/**
 * A data class wraps publishing info related to a software component.
 */
data class ComponentPublishingInfo(
    val componentName: String,
    val type: AbstractPublishing.Type,
    val attributesConfig: AttributesConfig? = null,
    val isClassifierRequired: Boolean = false,
    val withSourcesJar: Boolean = false,
    val withJavadocJar: Boolean = false
) {

    /**
     * Configs for attributes to be added to the variant.
     */
    data class AttributesConfig(
        val buildType: String?,
        val flavorDimensions: Set<String>
    )
}
