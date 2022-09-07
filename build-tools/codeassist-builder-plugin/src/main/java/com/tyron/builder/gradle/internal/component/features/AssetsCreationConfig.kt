package com.tyron.builder.gradle.internal.component.features

import com.tyron.builder.api.variant.AndroidResources

/**
 * Creation config for components that support assets.
 *
 * To use this in a task that requires assets support, use
 * [com.tyron.builder.gradle.internal.tasks.factory.features.AssetsTaskCreationAction].
 * Otherwise, access the nullable property on the component
 * [com.tyron.builder.gradle.internal.component.ComponentCreationConfig.assetsCreationConfig].
 */
interface AssetsCreationConfig {
    /**
     * AndroidResources block currently contains asset options, while disabling android resources
     * doesn't disable assets. To work around this, AndroidResources block is duplicated between
     * here and [AndroidResourcesCreationConfig]. If android resources is enabled, the value will
     * correspond to the same object as [AndroidResourcesCreationConfig.androidResources].
     */
    val androidResources: AndroidResources
}
