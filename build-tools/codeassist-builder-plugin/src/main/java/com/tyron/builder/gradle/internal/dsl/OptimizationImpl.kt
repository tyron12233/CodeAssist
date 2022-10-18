package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.KeepRules
import com.tyron.builder.api.dsl.Optimization
import com.tyron.builder.gradle.internal.services.DslServices
import javax.inject.Inject

abstract class OptimizationImpl@Inject constructor(dslService: DslServices) : Optimization {

    abstract val keepRules: KeepRules

    override fun keepRules(action: KeepRules.() -> Unit) {
        action.invoke(keepRules)
    }

    fun initWith(that: OptimizationImpl) {
        (keepRules as KeepRulesImpl).ignoreAllDependencies =
                (that.keepRules as KeepRulesImpl).ignoreAllDependencies

        (keepRules as KeepRulesImpl).dependencies.clear()
        (keepRules as KeepRulesImpl).dependencies.addAll(
                (that.keepRules as KeepRulesImpl).dependencies)
    }
}