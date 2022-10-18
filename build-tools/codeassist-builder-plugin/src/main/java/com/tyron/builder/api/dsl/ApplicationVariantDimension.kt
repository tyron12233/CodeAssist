package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

/**
 * Shared properties between DSL objects that contribute to an application variant.
 *
 * That is, [ApplicationBuildType] and [ApplicationProductFlavor] and [ApplicationDefaultConfig].
 */
interface ApplicationVariantDimension : VariantDimension {
    /**
     * Application id suffix. It is appended to the "base" application id when calculating the final
     * application id for a variant.
     *
     * In case there are product flavor dimensions specified, the final application id suffix
     * will contain the suffix from the default product flavor, followed by the suffix from product
     * flavor of the first dimension, second dimension and so on. All of these will have a dot in
     * between e.g. &quot;defaultSuffix.dimension1Suffix.dimensions2Suffix&quot;.
     */
    var applicationIdSuffix: String?

    /**
     * Version name suffix. It is appended to the "base" version name when calculating the final
     * version name for a variant.
     *
     * In case there are product flavor dimensions specified, the final version name suffix will
     * contain the suffix from the default product flavor, followed by the suffix from product
     * flavor of the first dimension, second dimension and so on.
     */
    var versionNameSuffix: String?

    /**
     * Returns whether multi-dex is enabled.
     *
     * This can be null if the flag is not set, in which case the default value is used.
     */
    @get:Incubating
    @set:Incubating
    var multiDexEnabled: Boolean?

    /** The associated signing config or null if none are set on the variant dimension. */
    @get:Incubating
    @set:Incubating
    var signingConfig: ApkSigningConfig?
}
