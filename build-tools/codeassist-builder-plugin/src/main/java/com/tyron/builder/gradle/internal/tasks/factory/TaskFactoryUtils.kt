@file:JvmName("TaskFactoryUtils")
package com.tyron.builder.gradle.internal.tasks.factory

import org.gradle.api.Action
import org.gradle.api.Buildable
import org.gradle.api.Task
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider

/**
 * Extension function for [TaskContainer] to add a way to create a task with our
 * [VariantTaskCreationAction] without having a [TaskFactory]
 */
fun <T : Task> TaskContainer.registerTask(
    creationAction: TaskCreationAction<T>,
    secondaryPreConfigAction: PreConfigAction? = null,
    secondaryAction: TaskConfigAction<in T>? = null,
    secondaryProviderCallback: TaskProviderCallback<T>? = null
): TaskProvider<T> {
    val actionWrapper = TaskConfigurationActions(
        creationAction,
        secondaryPreConfigAction,
        secondaryAction,
        secondaryProviderCallback
    )
    return this.register(creationAction.name, creationAction.type, actionWrapper)
        .also { provider ->
            actionWrapper.postRegisterHook(provider)
        }
}

/**
 * Extension function for [TaskContainer] to add a way to create a task with our
 * [PreConfigAction] and [TaskConfigAction] without having a [TaskFactory]
 */
fun <T : Task> TaskContainer.registerTask(
    taskName: String,
    taskType: Class<T>,
    preConfigAction: PreConfigAction? = null,
    action: TaskConfigAction<in T>? = null,
    providerCallback: TaskProviderCallback<T>? = null
): TaskProvider<T> {
    val actionWrapper = TaskConfigurationActions(
        preConfigAction = preConfigAction,
        configureAction = action,
        providerHandler = providerCallback
    )
    return this.register(taskName, taskType, actionWrapper)
        .also { provider ->
            actionWrapper.postRegisterHook(provider)
        }
}

/**
 * Wrapper for the [VariantTaskCreationAction] as a simple [Action] that is passed
 * to [TaskContainer.register].
 *
 * If the task is configured during the register then [VariantTaskCreationAction.preConfigure] is called
 * right away.
 *
 * After register, if it has not been called then it is called,
 * alongside [VariantTaskCreationAction.handleProvider]
 */
internal class TaskConfigurationActions<T: Task>(
    private val creationAction: TaskCreationAction<T>? = null,
    private val preConfigAction: PreConfigAction? = null,
    private val configureAction: TaskConfigAction<in T>? = null,
    private val providerHandler: TaskProviderCallback<T>? = null
) : Action<T> {

    var hasRunTaskProviderHandler = false
    var delayedTask: T? = null

    override fun execute(task: T) {
        // if we have not yet processed the task provider handle, then we delay this
        // to the post register hook
        if (hasRunTaskProviderHandler) {
            creationAction?.configure(task)
            configureAction?.configure(task)
        } else {
            delayedTask = task
        }
    }

    fun postRegisterHook(taskProvider: TaskProvider<T>) {
        creationAction?.preConfigure(taskProvider.name)
        preConfigAction?.preConfigure(taskProvider.name)

        creationAction?.handleProvider(taskProvider)
        providerHandler?.handleProvider(taskProvider)

        delayedTask?.let {
            creationAction?.configure(it)
            configureAction?.configure(it)
        }

        hasRunTaskProviderHandler = true
    }
}


/**
 * Sets dependency between 2 [TaskProvider], as an extension method on [TaskProvider].
 *
 * This handles if either of the 2 providers are null or not present.
 */
fun <T: Task> TaskProvider<out T>?.dependsOn(task: TaskProvider<out T>?): TaskProvider<out T>? {
    this?.letIfPresent { nonNullThis ->
        task?.letIfPresent { nonNullTask ->
            nonNullThis.configure { it.dependsOn(nonNullTask.get()) }
        }
    }

    return this
}

/**
 * Sets dependency between a [TaskProvider] and a task name, as an extension method on [TaskProvider].
 *
 * This handles if the provider is null or not present.
 */
fun <T: Task> TaskProvider<out T>?.dependsOn(taskName: String): TaskProvider<out T>? {
    this?.letIfPresent { nonNullThis ->
        nonNullThis.configure { it.dependsOn(taskName) }
    }

    return this
}

/**
 * Sets dependency between a [TaskProvider] and a [Buildable], as an extension method on [TaskProvider].
 *
 * This handles if the provider is null or not present.
 */
fun <T: Task> TaskProvider<out T>?.dependsOn(buildable: Buildable): TaskProvider<out T>? {
    this?.letIfPresent { nonNullThis ->
        nonNullThis.configure { it.dependsOn(buildable) }
    }

    return this
}

/**
 * Sets dependency between a [TaskProvider] and a [Task], as an extension method on [TaskProvider].
 *
 * This handles if the provider or the task are null or not present.
 *
 * @deprecated This is meant to be replaced with the version using 2 [TaskProvider] as [Task]
 * get replaced with [TaskProvider]
 */
@Deprecated("Use TaskProvider.dependsOn(TaskProvider)")
fun <T: Task> TaskProvider<out T>?.dependsOn(task: Task?): TaskProvider<out T>? {
    this?.letIfPresent { nonNullThis ->
        task?.let { nonNullTask ->
            nonNullThis.configure { it.dependsOn(nonNullTask) }
        }
    }

    return this
}

/**
 * Sets dependency between a [Task] and a [TaskProvider], as an extension method on [Task].
 *
 * This handles if the provider or the task are null or not present.
 *
 * @deprecated This is meant to be replaced with the version using 2 [TaskProvider] as [Task]
 * get replaced with [TaskProvider]
 */
@Deprecated("Use TaskProvider.dependsOn(TaskProvider)")
fun <T: Task> Task?.dependsOn(task: TaskProvider<out T>?): Task? {
    this?.let { nonNullThis ->
        task?.letIfPresent { nonNullTask ->
            nonNullThis.dependsOn(nonNullTask.get())
        }
    }

    return this
}

inline fun <T: Task> TaskProvider<out T>?.letIfPresent(block: (TaskProvider<out T>) -> Unit) {
    if (this != null && isPresent) {
        block(this)
    }
}

fun <T: Task> TaskProvider<out T>.dependsOn(tasks: Collection<TaskProvider<out Task>>): TaskProvider<out T> {
    if (tasks.isEmpty().not()) {
        configure { it.dependsOn(tasks) }
    }

    return this
}

fun <T: Task> TaskProvider<out T>.dependsOn(vararg tasks: TaskProvider<out Task>): TaskProvider<out T> {
    if (tasks.isEmpty().not()) {
        configure { it.dependsOn(*tasks) }
    }

    return this
}

@Deprecated("Use TaskProvider.dependsOn(Collection<TaskProvider>)")
fun <T: Task> TaskProvider<out T>.dependsOn(vararg tasks: Task): TaskProvider<out T> {
    if (tasks.isEmpty().not()) {
        configure { it.dependsOn(*tasks) }
    }

    return this
}