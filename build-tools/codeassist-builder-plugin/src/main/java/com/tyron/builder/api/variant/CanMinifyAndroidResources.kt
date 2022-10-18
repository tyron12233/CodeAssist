package com.tyron.builder.api.variant

import org.gradle.api.Incubating

/**
 * Interface for component that can shrink resources
 */
@Incubating
interface CanMinifyAndroidResources {

    /**
     * Specifies whether resources will be shrinked.
     * At this point the value is final. You can change it via
     * [AndroidComponentsExtension.beforeVariants] and
     * [CanMinifyAndroidResourcesBuilder.shrinkResources]
     */
    val shrinkResources: Boolean
}