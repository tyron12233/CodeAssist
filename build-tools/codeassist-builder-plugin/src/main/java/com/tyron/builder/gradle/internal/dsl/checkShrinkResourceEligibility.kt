@file:JvmName("ValidationUtilKt")
package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.core.ComponentType
import com.tyron.builder.errors.IssueReporter
import com.tyron.builder.gradle.internal.services.DslServices

/**
 * Function checks if resource shrink flag is eligible.
 * It reports error and throw exception in case it's not
 */
fun checkShrinkResourceEligibility(
    componentType: ComponentType,
    dslServices: DslServices,
    shrinkResourceFlag: Boolean
) {
    if (shrinkResourceFlag) {
        if (componentType.isDynamicFeature) {
            dslServices.issueReporter.reportError(
                IssueReporter.Type.GENERIC,
                "Resource shrinking must be configured for base module."
            )
        }
        if (componentType.isAar) {
            dslServices.issueReporter.reportError(
                IssueReporter.Type.GENERIC,
                "Resource shrinker cannot be used for libraries."
            )

        }
    }
}

