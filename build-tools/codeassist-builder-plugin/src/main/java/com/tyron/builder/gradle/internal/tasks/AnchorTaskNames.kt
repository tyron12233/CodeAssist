package com.tyron.builder.gradle.internal.tasks

import com.tyron.builder.gradle.internal.component.ComponentCreationConfig

/**
 * Type safe access to anchor tasks naming facilities
 */
object AnchorTaskNames {

    /**
     * Return the anchor task for the IDE to obtain all artifacts related to extracting APKs
     * from a bundle file.
     */
    fun getExtractApksAnchorTaskName(componentImpl: ComponentCreationConfig): String {
        return componentImpl.computeTaskName("extractApksFor")
    }
}