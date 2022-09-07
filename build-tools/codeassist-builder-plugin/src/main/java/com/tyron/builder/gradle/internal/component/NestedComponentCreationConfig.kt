package com.tyron.builder.gradle.internal.component

interface NestedComponentCreationConfig: ComponentCreationConfig {

    /**
     * Returns the main variant.
     */
    val mainVariant: VariantCreationConfig
}