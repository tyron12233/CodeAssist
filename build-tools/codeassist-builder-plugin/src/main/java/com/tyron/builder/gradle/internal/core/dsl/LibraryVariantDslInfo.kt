package com.tyron.builder.gradle.internal.core.dsl

import com.tyron.builder.api.dsl.AarMetadata
import com.tyron.builder.gradle.internal.core.dsl.features.AndroidResourcesDslInfo

/**
 * Represents the dsl info for a library variant, initialized from the DSL object model
 * (extension, default config, build type, flavors)
 *
 * This class allows querying for the values set via the DSL model.
 *
 * Use [DslInfoBuilder] to instantiate.
 *
 * @see [com.tyron.builder.gradle.internal.component.LibraryCreationConfig]
 */
interface LibraryVariantDslInfo:
    VariantDslInfo,
    AarProducingComponentDslInfo,
    PublishableComponentDslInfo,
    TestedVariantDslInfo,
    MultiVariantComponentDslInfo {
    val aarMetadata: AarMetadata

    // TODO: Clean this up
    val isDebuggable: Boolean

    override val androidResourcesDsl: AndroidResourcesDslInfo
}
