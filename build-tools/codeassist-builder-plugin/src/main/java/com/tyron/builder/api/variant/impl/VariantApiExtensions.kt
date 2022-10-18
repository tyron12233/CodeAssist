package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.variant.AndroidVersion

/**
 * Returns the API level as an integer. If this is a preview platform, it
 * will return the expected final version of the API rather than the current API
 * level. This is the "feature level" as opposed to the "release level" returned by
 * [.getApiLevel] in the sense that it is useful when you want
 * to check the presence of a given feature from an API, and we consider the feature
 * present in preview platforms as well.
 *
 * @return the API level of this version, +1 for preview platforms
 */
fun AndroidVersion.getFeatureLevel(): Int =
    if (codename != null) apiLevel + 1 else apiLevel

/**
 * Returns a string representing the API level and/or the code name.
 */
fun AndroidVersion.getApiString(): String = codename ?: apiLevel.toString()

/**
 * Convert public API [AndroidVersion] to one used by the model :
 * [com.android.sdklib.AndroidVersion]
 */
fun AndroidVersion.toSharedAndroidVersion(): com.android.sdklib.AndroidVersion {
    return com.android.sdklib.AndroidVersion(apiLevel, codename)
}