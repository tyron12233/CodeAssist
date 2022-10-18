package com.tyron.builder.api.component.impl

import com.tyron.builder.gradle.internal.component.legacy.ModelV1LegacySupport
import com.tyron.builder.gradle.internal.core.MergedFlavor
import com.tyron.builder.gradle.internal.core.dsl.ComponentDslInfo
import com.tyron.builder.gradle.internal.core.dsl.impl.ComponentDslInfoImpl
import org.gradle.api.provider.Provider

class ModelV1LegacySupportImpl(
    private val dslInfo: ComponentDslInfo
): ModelV1LegacySupport {

    override val mergedFlavor: MergedFlavor
        get() = (dslInfo as ComponentDslInfoImpl).mergedFlavor

    override val dslApplicationId: Provider<String>
        get() = dslInfo.applicationId
}