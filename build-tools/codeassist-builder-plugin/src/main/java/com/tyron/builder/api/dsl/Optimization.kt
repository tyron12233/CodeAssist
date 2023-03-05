package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

/**
 *  DSL object for configurations aimed for optimizing build process(e.g. speed, correctness). This
 *  DSL object is applicable to buildTypes and productFlavors.
 */
@Incubating
interface Optimization {

    /**
     * Configure keep rules inherited from external library dependencies
     */
    @Incubating
    fun keepRules(action: KeepRules.() -> Unit)
}