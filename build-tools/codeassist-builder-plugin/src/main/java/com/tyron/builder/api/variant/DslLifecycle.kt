package com.tyron.builder.api.variant

import org.gradle.api.Action
import org.gradle.api.Incubating

@Incubating
interface DslLifecycle<T> {

    /**
     * API to customize the DSL Objects programmatically after they have been evaluated from the
     * build files and before used in the build process next steps like variant or tasks creation.
     *
     * Example of a build type creation:
     * ```kotlin
     * androidComponents.finalizeDsl { extension ->
     *     extension.buildTypes.create("extra")
     * }
     * ```
     */
    fun finalizeDsl(callback: (T) -> Unit)

    /**
     * [Action] based version of [finalizeDsl] above.
     */
    fun finalizeDsl(callback: Action<T>)
}