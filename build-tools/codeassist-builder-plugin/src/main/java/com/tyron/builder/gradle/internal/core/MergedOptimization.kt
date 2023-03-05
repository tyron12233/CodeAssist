package com.tyron.builder.gradle.internal.core


import com.tyron.builder.gradle.internal.dsl.KeepRulesImpl
import com.tyron.builder.gradle.internal.dsl.OptimizationImpl

class MergedOptimization : MergedOptions<OptimizationImpl> {

    var ignoredLibraryKeepRules: MutableSet<String> = mutableSetOf()
        private set

    var ignoreAllLibraryKeepRules: Boolean = false
        private set

    override fun reset() {
        ignoredLibraryKeepRules = mutableSetOf()
        ignoreAllLibraryKeepRules = false
    }

    override fun append(option: OptimizationImpl) {
        ignoredLibraryKeepRules.addAll((option.keepRules as KeepRulesImpl).dependencies)
        ignoreAllLibraryKeepRules = ignoreAllLibraryKeepRules ||
                (option.keepRules as KeepRulesImpl).ignoreAllDependencies
    }
}
