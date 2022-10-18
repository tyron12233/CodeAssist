package com.tyron.builder.gradle.internal.component.legacy

import com.tyron.builder.gradle.internal.core.MergedFlavor
import org.gradle.api.provider.Provider

interface ModelV1LegacySupport {
    val mergedFlavor: MergedFlavor
    val dslApplicationId: Provider<String>
}