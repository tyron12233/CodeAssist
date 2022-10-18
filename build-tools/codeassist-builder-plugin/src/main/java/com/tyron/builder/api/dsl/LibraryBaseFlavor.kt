package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

interface LibraryBaseFlavor :
    BaseFlavor,
    LibraryVariantDimension {

    /**
     * The target SDK version used for building the test APK.
     *
     * This is propagated in the library manifest, but that is only advisory for libraries that
     * depend on this library.
     *
     * Setting this it will override previous calls of [targetSdk] and [targetSdkPreview] setters.
     * Only one of [targetSdk] and [targetSdkPreview] should be set.
     *
     * See [uses-sdk element documentation](http://developer.android.com/guide/topics/manifest/uses-sdk-element.html).
     */
    @Deprecated("Will be removed from library DSL in v9.0")
    @get:Incubating
    @set:Incubating
    var targetSdk: Int?

    @Deprecated("Replaced by targetSdk property")
    @Incubating
    fun targetSdkVersion(targetSdkVersion: Int)

    /**
     * The target SDK version used for building the test APK.
     *
     * This is propagated in the library manifest, but that is only advisory for libraries that
     * depend on this library.
     *
     * Setting this it will override previous calls of [targetSdk] and [targetSdkPreview] setters.
     * Only one of [targetSdk] and [targetSdkPreview] should be set.
     *
     * See [uses-sdk element documentation](http://developer.android.com/guide/topics/manifest/uses-sdk-element.html).
     */
    @Deprecated("Will be removed from library DSL in v9.0")
    @get:Incubating
    @set:Incubating
    var targetSdkPreview: String?

    @Deprecated("Replaced by targetSdkPreview property")
    @Incubating
    fun setTargetSdkVersion(targetSdkVersion: String?)

    @Deprecated("Replaced by targetSdkPreview property")
    @Incubating
    fun targetSdkVersion(targetSdkVersion: String?)
}