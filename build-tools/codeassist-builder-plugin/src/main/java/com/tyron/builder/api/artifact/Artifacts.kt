package com.tyron.builder.api.artifact

import com.tyron.builder.api.variant.BuiltArtifactsLoader
import com.tyron.builder.api.variant.ScopedArtifacts
import org.gradle.api.Task
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

/**
 * Access to the artifacts on a Variant object.
 *
 * Artifacts are temporary or final files or directories that are produced by the Android Gradle
 * plugin during the build. Depending on its configuration, each [com.android.build.api.variant.VariantBuilder]
 * produces different versions of some of the output artifacts.
 *
 * An example of temporary artifacts are .class files obtained from compiling source files that will
 * eventually get transformed further into dex files. Final artifacts are APKs and bundle files that
 * are not transformed further.
 *
 * Artifacts are uniquely defined by their [Artifact] type and public artifact types that can be
 * accessed from third-party plugins or build script are defined in [SingleArtifact]
 */
interface Artifacts {

    /**
     * Get the [Provider] of [FileTypeT] for the passed [Artifact].
     *
     * @param type Type of the single artifact.
     */
    fun <FileTypeT: FileSystemLocation> get(
        type: SingleArtifact<FileTypeT>
    ): Provider<FileTypeT>

    /**
     * Get all the [Provider]s of [FileTypeT] for the passed [Artifact].
     *
     * @param type Type of the multiple artifact.
     */
    fun <FileTypeT: FileSystemLocation> getAll(
        type: MultipleArtifact<FileTypeT>
    ): Provider<List<FileTypeT>>

    /**
     * Access [Task] based operations.
     *
     * @param taskProvider The [TaskProvider] for the [TaskT] that will be producing and/or
     * consuming artifact types.
     * @return A [TaskBasedOperation] object using the passed [TaskProvider] for all its operations.
     */
    fun <TaskT: Task> use(taskProvider: TaskProvider<TaskT>): TaskBasedOperation<TaskT>

    /**
     * Provides an implementation of [BuiltArtifactsLoader] that can be used to load built artifacts
     * metadata.
     *
     * @return A thread safe implementation of [BuiltArtifactsLoader] that can be reused.
     */
    fun getBuiltArtifactsLoader(): BuiltArtifactsLoader

    /**
     * Some artifacts do not have a single origin (like compiled from source code). Some artifacts
     * can be obtained from a combination of [Task]s running or incoming dependencies. For example,
     * classes used for dexing can come from compilation related tasks as well as .aar or .jar
     * files expressed as a project dependency.
     *
     * Therefore, these artifacts values can have a scope like [ScopedArtifacts.Scope.PROJECT] for
     * values directly produced by this module (as a [Task] output most likely). Alternatively,
     * the [ScopedArtifacts.Scope.ALL] adds all incoming dependencies (including transitive ones)
     * to the previous scope.
     *
     * For such cases, the artifact is represented as [ScopedArtifact] and can be manipulated by
     * its own set of API that are scope aware.
     *
     * Return [ScopedArtifacts] for a [ScopedArtifacts.Scope]
     */
    fun forScope(scope: ScopedArtifacts.Scope): ScopedArtifacts
}