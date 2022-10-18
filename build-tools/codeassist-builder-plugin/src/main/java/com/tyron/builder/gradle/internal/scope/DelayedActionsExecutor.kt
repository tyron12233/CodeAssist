package com.tyron.builder.gradle.internal.scope

/**
 * Keep a list of actions to execute later. Actions are Kotlin functions that take no parameter
 * and return nothing.
 */
class DelayedActionsExecutor {
    private val actions = mutableListOf<() -> Unit>()

    /**
     * Adds a new function to be executed once [runAll] is called.
     */
    fun addAction(action: () -> Unit) {
        actions.add(action)
    }

    /**
     * Runs all registered function in the order it was registered.
     */
    fun runAll() {
        actions.forEach { it() }
    }
}