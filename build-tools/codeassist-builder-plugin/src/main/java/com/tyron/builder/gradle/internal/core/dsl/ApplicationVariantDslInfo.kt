package com.tyron.builder.gradle.internal.core.dsl

import com.tyron.builder.gradle.internal.core.dsl.features.AndroidResourcesDslInfo
import org.gradle.api.provider.Provider

/**
 * Represents the dsl info for an application variant, initialized from the DSL object model
 * (extension, default config, build type, flavors)
 *
 * This class allows querying for the values set via the DSL model.
 *
 * Use [DslInfoBuilder] to instantiate.
 *
 * @see [com.tyron.builder.gradle.internal.component.ApplicationCreationConfig]
 */
interface ApplicationVariantDslInfo:
    VariantDslInfo,
    ApkProducingComponentDslInfo,
    PublishableComponentDslInfo,
    TestedVariantDslInfo,
    MultiVariantComponentDslInfo {

    /**
     * Returns the version name for this variant. This could be specified by the product flavors,
     * or, if not, it could be coming from the manifest. A suffix may be specified by the build
     * type.
     *
     * @return the version name or null if none defined
     */
    val versionName: Provider<String?>

    /**
     * Returns the version code for this variant. This could be specified by the product flavors,
     * or, if not, it could be coming from the manifest.
     *
     * @return the version code or -1 if there was none defined.
     */
    val versionCode: Provider<Int?>

    val isWearAppUnbundled: Boolean?

    val isEmbedMicroApp: Boolean

    val isProfileable: Boolean

    override val androidResourcesDsl: AndroidResourcesDslInfo
}
