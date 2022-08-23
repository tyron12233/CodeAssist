package com.tyron.builder.api.variant

import com.tyron.builder.api.artifact.ScopedArtifact
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * Defines all possible operations on a [ScopedArtifact] artifact type.
 *
 * Depending on the scope, inputs may contain a mix of [org.gradle.api.file.FileCollection],
 * [RegularFile] or [Directory] so all [Task] consuming the current value of the artifact must
 * provide two input fields that will contain the list of [RegularFile] and [Directory].
 *
 */
interface ScopedArtifactsOperation<T: Task> {

    /**
     * Append a new [FileSystemLocation] (basically, either a [Directory] or a [RegularFile]) to
     * the artifact type referenced by [to]
     *
     * @param to the [ScopedArtifact] to add the [with] to.
     * @param with lambda that returns the [Property] used by the [Task] to save the appended
     * element. The [Property] value will be automatically set by the Android Gradle Plugin and its
     * location should not be considered part of the API and can change in the future.
     */
    fun toAppend(
        to: ScopedArtifact,
        with: (T) -> Property<out FileSystemLocation>,
    )

    /**
     * Set the final version of the [type] artifact to the input fields of the [Task] [T].
     * Those input fields should be annotated with [org.gradle.api.tasks.InputFiles] for Gradle to
     * property set the task dependency.
     *
     * @param type the [ScopedArtifact] to obtain the final value of.
     * @param inputJars lambda that returns a [ListProperty] or [RegularFile] that will be used to
     * set all incoming files for this artifact type.
     * @param inputDirectories lambda that returns a [ListProperty] or [Directory] that will be used
     * to set all incoming directories for this artifact type.
     */
    fun toGet(
        type: ScopedArtifact,
        inputJars: (T) -> ListProperty<RegularFile>,
        inputDirectories: (T) -> ListProperty<Directory>)

    /**
     * Transform the current version of the [type] artifact into a new version. The order in which
     * the transforms are applied is directly set by the order of this method call. First come,
     * first served, last one provides the final version of the artifacts.
     *
     * @param type the [ScopedArtifact] to transform.
     * @param inputJars lambda that returns a [ListProperty] or [RegularFile] that will be used to
     * set all incoming files for this artifact type.
     * @param inputDirectories lambda that returns a [ListProperty] or [Directory] that will be used
     * to set all incoming directories for this artifact type.
     * @param into lambda that returns the [Property] used by the [Task] to save the transformed
     * element. The [Property] value will be automatically set by the Android Gradle Plugin and its
     * location should not be considered part of the API and can change in the future.
     */
    fun toTransform(
        type: ScopedArtifact,
        inputJars: (T) -> ListProperty<RegularFile>,
        inputDirectories: (T) -> ListProperty<Directory>,
        into: (T) -> RegularFileProperty)

    /**
     * Transform the current version of the [type] artifact into a new version. The order in which
     * the replace [Task]s are applied is directly set by the order of this method call. Last one
     * wins and none of the previously set append/transform/replace registered [Task]s will be
     * invoked since this [Task] [T] replace the final version.
     *
     * @param type the [ScopedArtifact] to replace.
     * @param into lambda that returns the [Property] used by the [Task] to save the replaced
     * element. The [Property] value will be automatically set by the Android Gradle Plugin and its
     * location should not be considered part of the API and can change in the future.
     */
    fun toReplace(
        type: ScopedArtifact,
        into: (T) -> RegularFileProperty
    )
}