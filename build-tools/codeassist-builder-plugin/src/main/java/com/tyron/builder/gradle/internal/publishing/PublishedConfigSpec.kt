package com.tyron.builder.gradle.internal.publishing

/**
 * Data wrapper class contains all the information about a configuration created for publishing.
 *
 * @param configType Configuration type for published artifacts.
 * @param componentName [SoftwareComponent] this configuration is added to for maven publishing.
 * @param isClassifierRequired Whether the artifact of this configuration needs classifier
 * for maven publishing.
 *
 * Note if [configType] is not a publicationConfig, [componentName] and [isClassifierRequired] don't
 * apply and are set to default values.
 */
data class PublishedConfigSpec @JvmOverloads constructor(
    val configType: AndroidArtifacts.PublishedConfigType,
    val componentName: String? = null,
    val isClassifierRequired: Boolean = false
) {
    constructor(configType: AndroidArtifacts.PublishedConfigType, component: ComponentPublishingInfo)
            : this(configType, component.componentName, component.isClassifierRequired)
}