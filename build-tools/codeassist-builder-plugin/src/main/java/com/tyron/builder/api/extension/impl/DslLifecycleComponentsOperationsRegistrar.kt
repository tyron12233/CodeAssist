package com.tyron.builder.api.extension.impl

import org.gradle.api.Action

open class DslLifecycleComponentsOperationsRegistrar<T>(
        private val extension: T,
) {
    private val dslFinalizationOperations = mutableListOf<Action<T>>()

    fun add(action: Action<T>) {
        dslFinalizationOperations.add(action)
    }

    fun executeDslFinalizationBlocks() {
        dslFinalizationOperations.forEach { it.execute(extension) }
    }
}