package com.tyron.builder.gradle.internal.variant

import com.tyron.builder.core.ComponentType

/**
 * A tested variant
 */
interface TestedVariantData {
    fun setTestVariantData(
        testVariantData: TestVariantData,
        type: ComponentType
    )

    fun getTestVariantData(type: ComponentType): TestVariantData?
}