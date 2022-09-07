package com.tyron.builder.gradle.internal.tasks.factory

import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider

/**
 * Interface for a container that can create Task.
 */
interface TaskFactory {
    /** Returns true if this collection contains an item with the given name.  */
    fun containsKey(name: String): Boolean

    // --- Direct Actions ---

    /**
     * Returns the [Task] named name from the current set of defined tasks.
     *
     * @param name the name of the requested [Task]
     * @return the [Task] instance or null if not found.
     */
    @Deprecated("Use lazyFindByName(...)")
    fun findByName(name: String): Task?

    // --- Lazy Actions ---

    fun named(name: String): TaskProvider<Task>

    fun register(name: String): TaskProvider<Task>

    fun <T : Task> register(
        creationAction: TaskCreationAction<T>
    ): TaskProvider<T>

    fun <T : Task> register(
        creationAction: TaskCreationAction<T>,
        secondaryPreConfigAction: PreConfigAction? = null,
        secondaryAction: TaskConfigAction<in T>? = null,
        secondaryProviderCallback: TaskProviderCallback<T>? = null
    ): TaskProvider<T>

    fun register(
        taskName: String,
        preConfigAction: PreConfigAction? = null,
        action: TaskConfigAction<in Task>? = null,
        providerCallback: TaskProviderCallback<Task>? = null
    ): TaskProvider<Task>

    fun <T: Task> register(
        taskName: String,
        taskType: Class<T>,
        action: Action<in T>
    ): TaskProvider<T>

    fun register(
        taskName: String,
        action: Action<in Task>
    ): TaskProvider<Task>

    fun configure(name: String, action: Action<in Task>)

    fun <T : Task> configure(name: String, type: Class<T>, action: Action<in T>)
}