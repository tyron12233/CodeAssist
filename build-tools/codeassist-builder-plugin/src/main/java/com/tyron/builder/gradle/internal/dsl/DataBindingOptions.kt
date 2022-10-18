package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.ApplicationBuildFeatures
import com.tyron.builder.api.dsl.BuildFeatures
import com.tyron.builder.api.dsl.DynamicFeatureBuildFeatures
import com.tyron.builder.api.dsl.LibraryBuildFeatures
import com.tyron.builder.gradle.internal.dsl.decorator.annotation.WithLazyInitialization
import com.tyron.builder.gradle.internal.services.DslServices
import com.tyron.builder.gradle.options.BooleanOption
import java.util.function.Supplier
import javax.inject.Inject

/** DSL object for configuring databinding options. */
abstract class DataBindingOptions @Inject @WithLazyInitialization("lateInit") constructor(
    private val featuresProvider: Supplier<BuildFeatures>,
    private val dslServices: DslServices
) : com.tyron.builder.model.DataBindingOptions, com.tyron.builder.api.dsl.DataBinding {
    override var enable: Boolean
        get() {
            return when (val buildFeatures = featuresProvider.get()) {
                is ApplicationBuildFeatures -> buildFeatures.dataBinding
                is LibraryBuildFeatures -> buildFeatures.dataBinding
                is DynamicFeatureBuildFeatures -> buildFeatures.dataBinding
                else -> false
            } ?: dslServices.projectOptions.get(BooleanOption.BUILD_FEATURE_DATABINDING)
        }
        set(value) {
            when (val buildFeatures = featuresProvider.get()) {
                is ApplicationBuildFeatures -> buildFeatures.dataBinding = value
                is LibraryBuildFeatures -> buildFeatures.dataBinding = value
                is DynamicFeatureBuildFeatures -> buildFeatures.dataBinding = value
                else -> dslServices.logger
                    .warn("dataBinding.setEnabled has no impact on this sub-project type")
            }
        }

    override var isEnabled: Boolean
        get() = enable
        set(value) {
            enable = value
        }

    override var isEnabledForTests: Boolean
        get() = enableForTests
        set(value) {
            enableForTests = value
        }

    @Suppress("unused") // call injected in the constructor by the dsl decorator
    protected fun lateInit() {
        addDefaultAdapters = true
    }
}
