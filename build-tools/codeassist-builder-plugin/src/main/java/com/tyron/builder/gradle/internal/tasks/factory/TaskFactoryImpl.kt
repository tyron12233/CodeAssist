package com.tyron.builder.internal.tasks.factory

import com.tyron.builder.gradle.internal.tasks.factory.*
import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider

@Suppress("OverridingDeprecatedMember", "DEPRECATION")
class TaskFactoryImpl(private val taskContainer: TaskContainer):
    TaskFactory {

    override fun containsKey(name: String): Boolean {
        return taskContainer.findByName(name) != null
    }

    // --- Direct Creation ---

    override fun findByName(name: String): Task? {
        return taskContainer.findByName(name)
    }

    // --- Lazy Creation ---

    override fun named(name: String): TaskProvider<Task> = taskContainer.named(name)

    override fun register(name: String): TaskProvider<Task> = taskContainer.register(name)

    override fun <T : Task> register(creationAction: TaskCreationAction<T>): TaskProvider<T> =
        taskContainer.registerTask(creationAction, null, null, null)

    override fun <T : Task> register(
        creationAction: TaskCreationAction<T>,
        secondaryPreConfigAction: PreConfigAction?,
        secondaryAction: TaskConfigAction<in T>?,
        secondaryProviderCallback: TaskProviderCallback<T>?
    ): TaskProvider<T> =
        taskContainer.registerTask(creationAction, secondaryPreConfigAction, secondaryAction, secondaryProviderCallback)

    override fun register(
        taskName: String,
        preConfigAction: PreConfigAction?,
        action: TaskConfigAction<in Task>?,
        providerCallback: TaskProviderCallback<Task>?
    ): TaskProvider<Task> =
        taskContainer.registerTask(taskName, Task::class.java, preConfigAction, action, providerCallback)

    override fun <T : Task> register(
        taskName: String,
        taskType: Class<T>,
        action: Action<in T>
    ): TaskProvider<T> {
        return taskContainer.register(taskName, taskType, action)
    }

    override fun register(
        taskName: String,
        action: Action<in Task>
    ): TaskProvider<Task> {
        return taskContainer.register(taskName, Task::class.java, action)
    }

    override fun configure(name: String, action: Action<in Task>) {
        taskContainer.named(name).configure(action)
    }

    override fun <T : Task> configure(name: String, type: Class<T>, action: Action<in T>) {
        taskContainer.withType(type).named(name).configure(action)
    }
}