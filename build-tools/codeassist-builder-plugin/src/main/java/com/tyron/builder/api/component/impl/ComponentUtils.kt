@file:JvmName("ComponentUtils")
package com.tyron.builder.api.component.impl

import com.android.sdklib.AndroidTargetHash
import com.google.common.base.Strings
import com.tyron.builder.api.variant.AndroidResources
import com.tyron.builder.errors.IssueReporter
import com.tyron.builder.gradle.internal.component.ApkCreationConfig
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.options.BooleanOption
import com.tyron.builder.gradle.options.IntegerOption
import com.tyron.builder.gradle.options.OptionalBooleanOption
import com.tyron.builder.gradle.options.StringOption

val ENABLE_LEGACY_API: String =
    "Turn on with by putting '${BooleanOption.ENABLE_LEGACY_API.propertyName}=true in gradle.properties'\n" +
            "Using this deprecated API may still fail, depending on usage of the new Variant API, like computing applicationId via a task output."

/**
 * AndroidResources block currently contains asset options, while disabling android resources
 * doesn't disable assets. To work around this, AndroidResources block is duplicated between
 * [AssetsCreationConfig] and [AndroidResourcesCreationConfig]. If android resources is disabled,
 * the value is returned from [AssetsCreationConfig].
 */
internal fun ComponentImpl<*>.getAndroidResources(): AndroidResources {
    return androidResourcesCreationConfig?.androidResources
        ?: assetsCreationConfig.androidResources
}

/**
 * Determine if the final output should be marked as testOnly to prevent uploading to Play
 * store.
 *
 * <p>Uploading to Play store is disallowed if:
 *
 * <ul>
 *   <li>An injected option is set (usually by the IDE for testing purposes).
 *   <li>compileSdkVersion, minSdkVersion or targetSdkVersion is a preview
 * </ul>
 *
 * <p>This value can be overridden by the OptionalBooleanOption.IDE_TEST_ONLY property.
 *
 * @param variant {@link VariantCreationConfig} for this variant scope.
 */
internal fun ApkCreationConfig.isTestApk(): Boolean {
    val projectOptions = services.projectOptions

    return projectOptions.get(OptionalBooleanOption.IDE_TEST_ONLY) ?:
            !Strings.isNullOrEmpty(projectOptions.get(StringOption.IDE_BUILD_TARGET_ABI))
            || !Strings.isNullOrEmpty(projectOptions.get(StringOption.IDE_BUILD_TARGET_DENSITY))
            || projectOptions.get(IntegerOption.IDE_TARGET_DEVICE_API) != null
            || AndroidTargetHash.getVersionFromHash(global.compileSdkHashString)?.isPreview == true
            || minSdkVersion.codename != null
            || targetSdkVersion.codename != null
}

internal fun<T> ComponentCreationConfig.warnAboutAccessingVariantApiValueForDisabledFeature(
    featureName: String,
    apiName: String,
    value: T
): T {
    services.issueReporter.reportWarning(
        IssueReporter.Type.ACCESSING_DISABLED_FEATURE_VARIANT_API,
        "Accessing value $apiName in variant $name has no effect as the feature" +
                " $featureName is disabled."
    )
    return value
}