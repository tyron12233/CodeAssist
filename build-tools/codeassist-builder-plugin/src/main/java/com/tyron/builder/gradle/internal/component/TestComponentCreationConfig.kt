package com.tyron.builder.gradle.internal.component

/**
 * Internal interface for all test components
 */
interface TestComponentCreationConfig: TestCreationConfig, NestedComponentCreationConfig {

    /**
     * Runs an action on the tested variant and return the results of the action.
     */
    fun <T> onTestedVariant(action: (VariantCreationConfig) -> T): T
}