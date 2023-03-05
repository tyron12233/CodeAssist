package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.KeepRules
import com.tyron.builder.gradle.internal.services.DslServices
import javax.inject.Inject

abstract class KeepRulesImpl@Inject constructor(dslService: DslServices) : KeepRules {

    internal abstract val dependencies: MutableSet<String>
    internal abstract var ignoreAllDependencies: Boolean

    override fun ignoreExternalDependencies(vararg ids: String) {
        dependencies.addAll(ids)
    }

    override fun ignoreAllExternalDependencies(ignore: Boolean) {
        ignoreAllDependencies = ignore
    }
}