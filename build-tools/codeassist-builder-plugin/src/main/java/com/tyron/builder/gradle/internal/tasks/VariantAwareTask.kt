package com.tyron.builder.gradle.internal.tasks

import org.gradle.api.services.BuildServiceRegistry
import org.gradle.api.tasks.Internal

/**
 * A task associated with a variant name.
 */
interface VariantAwareTask {

    @get:Internal
    /** the name of the variant */
    var variantName: String

//    @get:Internal
//    val analyticsService: Property<AnalyticsService>
}

fun VariantAwareTask.configureVariantProperties(
    variantName: String,
    serviceRegistry: BuildServiceRegistry
) {
    this.variantName = variantName
//    this.analyticsService.setDisallowChanges(getBuildService(serviceRegistry))
}