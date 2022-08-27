package com.tyron.builder.api.variant

import org.gradle.api.Incubating
import org.gradle.api.Named
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

/**
 * Defines all common behaviors to sources access in AGP.
 *
 * Adding directories is always added to the "Variant" overlay and will therefore carry the
 * highest possible priority among all directories for the source type.
 */
@Incubating
interface SourceDirectories: Named {
    /**
     * Add the output of a custom task to the list of source directories.
     *
     * The [Directory] is the output of a Task [TASK] that has been registered using the Gradle's Task
     * manager.
     *
     * The [Directory] is added last to the variant's list of source directories. In case there is
     * merging for the source type, the [Directory] will have the highest priority.
     *
     * @param taskProvider the [TaskProvider] returned by Gradle's Task manager when registering the
     * Task of type [TASK].
     * @param wiredWith the method reference returning the [TASK] task's output to use as a source
     * directory. The generated source directory location is automatically determined by the
     * Android Gradle Plugin
     */
    fun <TASK: Task> addGeneratedSourceDirectory(
        taskProvider: TaskProvider<TASK>,
        wiredWith: (TASK) -> DirectoryProperty
    )

    /**
     * Add a source directory with sources already present to the variant.
     *
     * The directory will be added last in the list of source folders for the variant. In case there
     * is merging for the source type, [srcDir] will be the folder with the highest priority.
     *
     * Do not use [addStaticSourceDirectory] to add sources that are generated by a task,
     * instead use [addGeneratedSourceDirectory]
     *
     * @param srcDir the source directory path, that will be resolved using the [Directory.dir] API
     * relative to the Gradle project directory.
     */
    fun addStaticSourceDirectory(srcDir: String)

    /**
     * Represents all the source folders for a source type in the variant.
     *
     * All folders are considered of the same priority, so if there is a conflict (e.g, two files
     * with the same relative path and name), it will require a resolution step like merging to
     * ensure nothing gets lost.
     *
     * since 7.2
     */
    @Incubating
    interface Flat: SourceDirectories {

        /**
         * Get all registered source folders and files as a [List] of [Directory].
         *
         * Some source types do not have the concept of overriding, while others require a merging
         * step to ensure only one source file is used when processing begins.
         *
         * The returned [Provider] can be used directly in a [org.gradle.api.tasks.InputFiles]
         * annotated property of a [Task]
         */
        val all: Provider<out Collection<Directory>>
    }

    /**
     * Represent a collection of directories that have overlay properties to each other. This mean
     * that some directories will carry higher priority when merging content will be carried out.
     *
     * The collection is represented as a [List] where the first elements are the highest priority ones.
     *
     * For a specific priority, there can be more than one directory defined and is represented as a
     * [Collection] of [Directory].
     *
     * Therefore, [Layered] can be represented as a [List] of [Collection] of
     * [Directory].
     *
     */
    @Incubating
    interface Layered: SourceDirectories {
        /**
         * Get all registered source folders and files as a [List] of [Collection] of [Directory].
         *
         * The outer [List] represents the priority of [Directory] respective to each other, meaning that
         * elements first in the list overrides elements last in the list.
         *
         * The inner [Collection] represents all [Directory] with the same priority  respective to each
         * other.
         *
         * The returned [Provider] can be used directly in a [org.gradle.api.tasks.InputFiles] annotated
         * property of a [Task]
         */
        val all: Provider<List<Collection<Directory>>>
    }
}