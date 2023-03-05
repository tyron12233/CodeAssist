package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.BuildFeatures
import com.tyron.builder.api.dsl.ViewBinding
import com.tyron.builder.gradle.api.ViewBindingOptions
import com.tyron.builder.gradle.internal.services.DslServices
import com.tyron.builder.gradle.options.BooleanOption
import java.util.function.Supplier
import javax.inject.Inject

/** DSL object for configuring view binding options.  */
abstract class ViewBindingOptionsImpl @Inject constructor(
    private val features: Supplier<BuildFeatures>,
    private val dslServices: DslServices
) : ViewBindingOptions, ViewBinding {

    override var enable: Boolean
        get() {
            val bool = features.get().viewBinding
            if (bool != null) {
                return bool
            }
            return dslServices.projectOptions[BooleanOption.BUILD_FEATURE_VIEWBINDING]
        }
        set(value) {
            features.get().viewBinding = value
        }

    /** Whether to enable view binding.  */
    override var isEnabled: Boolean
        get() = enable
        set(value) {
            enable = value
        }
}
