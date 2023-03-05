package com.tyron.builder.gradle.internal.scope

import org.gradle.api.tasks.Input

/**
 * Gradle aware version of the model's [com.tyron.builder.FilterData]
 */
interface GradleAwareFilterData: com.tyron.builder.FilterData {
    @Input
    override fun getFilterType(): String

    @Input
    override fun getIdentifier(): String
}