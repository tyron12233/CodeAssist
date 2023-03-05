package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

interface ApplicationBaseFlavor :
    BaseFlavor,
    ApplicationVariantDimension {
    /**
     * The application ID.
     *
     * See [Set the Application ID](https://developer.android.com/studio/build/application-id.html)
     */
    var applicationId: String?

    /**
     * Version code.
     *
     * See [Versioning Your Application](http://developer.android.com/tools/publishing/versioning.html)
     */
    var versionCode: Int?

    /**
     * Version name.
     *
     * See [Versioning Your Application](http://developer.android.com/tools/publishing/versioning.html)
     */
    var versionName: String?

    /**
     * The target SDK version.
     * Setting this it will override previous calls of [targetSdk] and [targetSdkPreview] setters.
     * Only one of [targetSdk] and [targetSdkPreview] should be set.
     *
     * See [uses-sdk element documentation](http://developer.android.com/guide/topics/manifest/uses-sdk-element.html).
     */
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
    var maxSdk: Int?

    @Deprecated("Replaced by maxSdk property")
    @Incubating
    fun maxSdkVersion(maxSdkVersion: Int)
}
