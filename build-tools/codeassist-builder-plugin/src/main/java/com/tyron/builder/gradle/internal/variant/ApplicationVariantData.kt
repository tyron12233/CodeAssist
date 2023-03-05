package com.tyron.builder.gradle.internal.variant

import com.tyron.builder.api.artifact.impl.ArtifactsImpl
import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.gradle.internal.scope.MutableTaskContainer
import com.tyron.builder.gradle.internal.services.VariantServices
import com.tyron.builder.core.ComponentType

/**
 * Data about a variant that produce an application APK.
 *
 *
 * This includes application, dynamic-feature and standalone Test plugins.
 */
class ApplicationVariantData(
    componentIdentity: ComponentIdentity,
    artifacts: ArtifactsImpl,
    services: VariantServices,
    taskContainer: MutableTaskContainer
) : ApkVariantData(
    componentIdentity,
    artifacts,
    services,
    taskContainer
), TestedVariantData {

    private val testVariants: MutableMap<ComponentType, TestVariantData> = mutableMapOf()

    override fun setTestVariantData(testVariantData: TestVariantData, type: ComponentType) {
        testVariants[type] = testVariantData
    }

    override fun getTestVariantData(type: ComponentType): TestVariantData? {
        return testVariants[type]
    }
}
