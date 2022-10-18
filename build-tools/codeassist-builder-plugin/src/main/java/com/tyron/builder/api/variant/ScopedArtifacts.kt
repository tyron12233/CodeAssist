package com.tyron.builder.api.variant

import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider

/**
 * Scoped artifacts are artifacts that can be made available in the current variant scope, or
 * may be optionally include the project's dependencies in the results.
 *
 * This is only available for elements that are part of the [com.android.build.api.artifact.ScopedArtifact]
 * hierarchy.
 */
interface ScopedArtifacts {

    /**
     * Defines possible scopes.
     */
    enum class Scope {

        /**
         * Project scope, not including imported projects nor external dependencies.
         */
        PROJECT,

        /**
         * Full scope, including project scope, imported projects and all external dependencies.
         */
        ALL
    }

    /**
     * Access [Task] based operations.
     *
     * So far, all operations are only accessible providing a [Task] instance.
     */
    fun <T: Task> use(taskProvider: TaskProvider<T>): ScopedArtifactsOperation<T>
}