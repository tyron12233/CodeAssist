package com.tyron.builder.api.component.impl

import com.tyron.builder.api.artifact.impl.ArtifactsImpl
import com.tyron.builder.api.variant.Component
import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.api.variant.JavaCompilation
import com.tyron.builder.core.dsl.ComponentDslInfo
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.dependency.VariantDependencies
import com.tyron.builder.gradle.internal.scope.BuildFeatureValues
import com.tyron.builder.gradle.internal.services.TaskCreationServices
import com.tyron.builder.gradle.internal.services.VariantServices
import com.tyron.builder.gradle.internal.scope.MutableTaskContainer
import com.tyron.builder.internal.tasks.factory.GlobalTaskCreationConfig
import com.tyron.builder.internal.variant.VariantPathHelper
import org.gradle.api.provider.Provider

abstract class ComponentImpl<DslInfoT: ComponentDslInfo>(
    open val componentIdentity: ComponentIdentity,
    final override val buildFeatures: BuildFeatureValues,
    protected val dslInfo: DslInfoT,
    final override val variantDependencies: VariantDependencies,
//    override val variantSources: VariantSources,
    override val paths: VariantPathHelper,
    override val artifacts: ArtifactsImpl,
//    private val variantData: BaseVariantData? = null,
    override val taskContainer: MutableTaskContainer,
//    override val transformManager: TransformManager,
    protected val internalServices: VariantServices,
    final override val services: TaskCreationServices,
    final override val global: GlobalTaskCreationConfig,
) : Component, ComponentCreationConfig, ComponentIdentity by componentIdentity {

    // ---------------------------------------------------------------------------------------------
    // PUBLIC API
    // ---------------------------------------------------------------------------------------------
    override val namespace: Provider<String> =
        internalServices.providerOf(
            type = String::class.java,
            value = dslInfo.namespace
        )

    override val javaCompilation: JavaCompilation = TODO()
//        JavaCompilationImpl(
//            dslInfo.javaCompileOptionsSetInDSL,
//            buildFeatures.dataBinding,
//            internalServices)

    override val compileConfiguration = variantDependencies.compileClasspath

    override val runtimeConfiguration = variantDependencies.runtimeClasspath
}