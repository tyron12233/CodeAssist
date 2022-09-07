package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

interface TestBaseFlavor :
    BaseFlavor,
    TestVariantDimension {
    /**
     * The target SDK version.
     * Setting this it will override previous calls of [targetSdk] and [targetSdkPreview] setters.
     * Only one of [targetSdk] and [targetSdkPreview] should be set.
     *
     * See [uses-sdk element documentation](http://developer.android.com/guide/topics/manifest/uses-sdk-element.html).
     */
    @get:Incubating
    @set:Incubating
    var targetSdk: Int?

    @Deprecated("Replaced by targetSdk property")
    @Incubating
    fun targetSdkVersion(targetSdkVersion: Int)

    /**
     * The target SDK version.
     * Setting this it will override previous calls of [targetSdk] and [targetSdkPreview] setters.
     * Only one of [targetSdk] and [targetSdkPreview] should be set.
     *
     * See [uses-sdk element documentation](http://developer.android.com/guide/topics/manifest/uses-sdk-element.html).
     */
    @get:Incubating
    @set:Incubating
    var targetSdkPreview: String?

    @Deprecated("Replaced by targetSdkPreview property")
    @Incubating
    fun setTargetSdkVersion(targetSdkVersion: String?)

    @Deprecated("Replaced by targetSdkPreview property")
    @Incubating
    fun targetSdkVersion(targetSdkVersion: String?)

    /**
     * The maxSdkVersion, or null if not specified. This is only the value set on this produce
     * flavor.
     *
     * See [uses-sdk element documentation](http://developer.android.com/guide/topics/manifest/uses-sdk-element.html).
     */
    @get:Incubating
    @set:Incubating
    var maxSdk: Int?

    @Deprecated("Replaced by maxSdk property")
    @Incubating
    fun maxSdkVersion(maxSdkVersion: Int)
}