package com.tyron.builder.api.component.impl

import com.android.utils.appendCapitalized
import com.tyron.builder.api.artifact.impl.ArtifactsImpl
import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.api.variant.TestComponent
import com.tyron.builder.gradle.internal.component.TestComponentCreationConfig
import com.tyron.builder.gradle.internal.component.VariantCreationConfig
import com.tyron.builder.gradle.internal.core.VariantSources
import com.tyron.builder.gradle.internal.core.dsl.TestComponentDslInfo
import com.tyron.builder.gradle.internal.dependency.VariantDependencies
import com.tyron.builder.gradle.internal.pipeline.TransformManager
import com.tyron.builder.gradle.internal.scope.BuildFeatureValues
import com.tyron.builder.gradle.internal.scope.MutableTaskContainer
import com.tyron.builder.gradle.internal.services.TaskCreationServices
import com.tyron.builder.gradle.internal.services.VariantServices
import com.tyron.builder.gradle.internal.tasks.factory.GlobalTaskCreationConfig
import com.tyron.builder.gradle.internal.variant.BaseVariantData
import com.tyron.builder.gradle.internal.variant.VariantPathHelper
import javax.inject.Inject

abstract class TestComponentImpl<DslInfoT: TestComponentDslInfo> @Inject constructor(
    componentIdentity: ComponentIdentity,
    buildFeatureValues: BuildFeatureValues,
    dslInfo: DslInfoT,
    variantDependencies: VariantDependencies,
    variantSources: VariantSources,
    paths: VariantPathHelper,
    artifacts: ArtifactsImpl,
    variantData: BaseVariantData,
    taskContainer: MutableTaskContainer,
    override val mainVariant: VariantCreationConfig,
    transformManager: TransformManager,
    variantServices: VariantServices,
    taskCreationServices: TaskCreationServices,
    global: GlobalTaskCreationConfig,
) : ComponentImpl<DslInfoT>(
    componentIdentity,
    buildFeatureValues,
    dslInfo,
    variantDependencies,
    variantSources,
    paths,
    artifacts,
    variantData,
    taskContainer,
    transformManager,
    variantServices,
    taskCreationServices,
    global
), TestComponent, TestComponentCreationConfig {

    override val description: String
        get() {
            val componentType = dslInfo.componentType

            val prefix = if (componentType.isApk) {
                "android (on device) tests"
            } else {
                "unit tests"
            }

            return if (componentIdentity.productFlavors.isNotEmpty()) {
                val sb = StringBuilder(50)
                sb.append(prefix)
                sb.append(" for the ")
                componentIdentity.flavorName?.let { sb.appendCapitalized(it) }
                componentIdentity.buildType?.let { sb.appendCapitalized(it) }
                sb.append(" build")
                sb.toString()
            } else {
                val sb = StringBuilder(50)
                sb.append(prefix)
                sb.append(" for the ")
                sb.appendCapitalized(componentIdentity.buildType!!)
                sb.append(" build")
                sb.toString()
            }
        }

    override fun <T> onTestedVariant(action: (VariantCreationConfig) -> T): T {
        return action(mainVariant)
    }

    override val supportedAbis: Set<String>
        get() = mainVariant.supportedAbis
}
