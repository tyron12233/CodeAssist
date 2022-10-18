package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.variant.ApplicationVariantBuilder
import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.api.variant.DependenciesInfoBuilder
import com.tyron.builder.api.variant.VariantBuilder
import com.tyron.builder.gradle.internal.core.dsl.ApplicationVariantDslInfo
import com.tyron.builder.gradle.internal.services.ProjectServices
import com.tyron.builder.gradle.internal.services.VariantBuilderServices
import javax.inject.Inject

open class ApplicationVariantBuilderImpl @Inject constructor(
    globalVariantBuilderConfig: GlobalVariantBuilderConfig,
    dslInfo: ApplicationVariantDslInfo,
    componentIdentity: ComponentIdentity,
    variantBuilderServices: VariantBuilderServices
) : VariantBuilderImpl(
    globalVariantBuilderConfig,
    dslInfo,
    componentIdentity,
    variantBuilderServices
), ApplicationVariantBuilder {

    override val debuggable: Boolean
        get() = (dslInfo as ApplicationVariantDslInfo).isDebuggable

    override var androidTestEnabled: Boolean
        get() = enableAndroidTest
        set(value) {
            enableAndroidTest = value
        }

    override var enableAndroidTest: Boolean = true

    override var enableTestFixtures: Boolean = dslInfo.testFixtures.enable

    // only instantiate this if this is needed. This allows non-built variant to not do too much work.
    override val dependenciesInfo: DependenciesInfoBuilder by lazy {
        variantBuilderServices.newInstance(
            DependenciesInfoBuilderImpl::class.java,
            variantBuilderServices,
            globalVariantBuilderConfig.dependenciesInfo
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T: VariantBuilder> createUserVisibleVariantObject(
        projectServices: ProjectServices,
        stats: Any?,
    ): T = if (stats == null) {
            this as T
        } else {
//            projectServices.objectFactory.newInstance(
//                AnalyticsEnabledApplicationVariantBuilder::class.java,
//                this,
//                stats
//            ) as T
            TODO()
        }

    override var isMinifyEnabled: Boolean =
        dslInfo.postProcessingOptions.codeShrinkerEnabled()
        set(value) = setMinificationIfPossible("minifyEnabled", value){ field = it }

    override var shrinkResources: Boolean =
        dslInfo.postProcessingOptions.resourcesShrinkingEnabled()
        set(value) = setMinificationIfPossible("shrinkResources", value){ field = it }

}
