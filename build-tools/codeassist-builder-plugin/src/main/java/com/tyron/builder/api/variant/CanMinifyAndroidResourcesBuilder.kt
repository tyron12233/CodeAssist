package com.tyron.builder.api.variant

import org.gradle.api.Incubating

/**
 * Interface for component builder that can shrink resources
 */
@Incubating
interface CanMinifyAndroidResourcesBuilder {

    /**
     * Specifies whether resources will be shrinked
     */
    var shrinkResources: Boolean
}