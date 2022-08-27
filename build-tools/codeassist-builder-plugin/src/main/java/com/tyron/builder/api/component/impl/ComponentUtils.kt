@file:JvmName("ComponentUtils")
package com.tyron.builder.api.component.impl

import com.tyron.builder.errors.IssueReporter
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig

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