package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

/**
 * Shared properties between DSL objects that contribute to a separate-test-project variant.
 *
 * That is, [TestBuildType] and [TestProductFlavor] and [TestDefaultConfig].
 */
interface TestVariantDimension :
    VariantDimension {
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
