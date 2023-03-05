package com.tyron.builder.api.variant

/**
 * Cross-cutting interface for components builders that produce APK files.
 */
interface GeneratesApkBuilder {
    /**
     * Gets or sets the target SDK Version for this variant as an integer API level.
     * Setting this will override previous calls of [targetSdk] and [targetSdkPreview] setters.
     * Only one of [targetSdk] and [targetSdkPreview] should be set.
     *
     * The value may be null if set via [targetSdkPreview].
     */
    var targetSdk: Int?

    /**
     * Gets or sets the target SDK Version for this variant as an integer API level.
     * Setting this will override previous calls of [targetSdk] and [targetSdkPreview] setters.
     * Only one of [targetSdk] and [targetSdkPreview] should be set.
     *
     * The value may be null if set via [targetSdk].
     */
    var targetSdkPreview: String?
}
