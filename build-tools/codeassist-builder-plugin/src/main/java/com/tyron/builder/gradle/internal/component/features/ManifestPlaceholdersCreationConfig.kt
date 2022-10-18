package com.tyron.builder.gradle.internal.component.features

import org.gradle.api.provider.MapProperty

/**
 * Creation config for components that support manifest placeholders.
 */
interface ManifestPlaceholdersCreationConfig {
    val placeholders: MapProperty<String, String>
}