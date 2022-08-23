package com.tyron.builder.api.artifact

import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.FileSystemLocationProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty

/**
 * Interface with methods to wire input and output method references to [Task]-based operations.
 *
 * A [Task]-based operation will create, transform, or append files or directories to public
 * [SingleArtifact] to customize or participate in the build flow.
 *
 * Each operation should indicate through the methods of this interface which method can be used
 * to set or retrieve the [Task] inputs and outputs.
 */
interface TaskBasedOperation<TaskT: Task> {

    /**
     * Sets the [TaskT] output method reference so the [TaskT] result can be retrieved after
     * successful task execution.
     *
     * This method is useful when creating a new version, when appending to existing artifacts,
     * or when access to the current version of an artifact isn't required.
     *
     * @param taskOutput The method reference to retrieve the task output after successful task
     * execution.
     * @return The [OutOperationRequest] to set the desired operation type and [SingleArtifact] on
     * which the operation applies.
     */
    fun <FileTypeT: FileSystemLocation> wiredWith(
        taskOutput: (TaskT) -> FileSystemLocationProperty<FileTypeT>
    ): OutOperationRequest<FileTypeT>

    /**
     * Sets the [TaskT] input and output methods references so the [Task] can retrieve the
     * current version of the target [SingleArtifact] when invoking [taskInput]. [TaskT] will also
     * produce a new version of the same artifact type accessible through the [taskOutput] method
     * after successful execution.
     *
     * This method is useful when [TaskT] is transforming an [SingleArtifact] from its current
     * version to a new one and the [SingleArtifact]'s [Artifact.kind] is [Artifact.FILE]
     *
     * @param taskInput The method reference the [TaskT] will use to retrieve the current artifact
     * version during [TaskT] execution (and only then).
     * @param taskOutput The method reference to retrieve the task output after successful task
     * execution.
     * @return The [OutOperationRequest] to set the desired operation type and [SingleArtifact] as
     * well as the target [SingleArtifact].
     */
    fun wiredWithFiles(
        taskInput: (TaskT) -> RegularFileProperty,
        taskOutput: (TaskT) -> RegularFileProperty
    ): InAndOutFileOperationRequest

    /**
     * Sets the [TaskT] input and output methods references so the [Task] can retrieve the
     * current versions of a [Artifact.Multiple] [SingleArtifact] when invoking [taskInput]
     * while producing a new version of the same artifact type accessible through the [taskOutput]
     * method after successful execution.
     *
     * This method is useful when [TaskT] is combining all elements of an [SingleArtifact] into a
     * single element.
     *
     * @param taskInput The method reference the [TaskT] will use to retrieve the current artifact
     * versions during [TaskT] execution (and only then).
     * @param taskOutput The method reference to retrieve the task output after successful task
     * execution.
     * @return The [CombiningOperationRequest] to set the desired operation type and [SingleArtifact]
     * as well as the target [SingleArtifact].
     */
    fun <FileTypeT: FileSystemLocation> wiredWith(
        taskInput: (TaskT) -> ListProperty<FileTypeT>,
        taskOutput: (TaskT) -> FileSystemLocationProperty<FileTypeT>
    ): CombiningOperationRequest<FileTypeT>

    /**
     * Sets the [TaskT] input and output methods references so the [Task] can retrieve the
     * current version of the target [SingleArtifact] when invoking [taskInput]. [TaskT] will also
     * produce a new version of the same artifact type accessible through the [taskOutput] method
     * after successful execution.
     *
     * This method is useful when [TaskT] is transforming an [SingleArtifact] from its current
     * version to a new one and the [SingleArtifact]'s [Artifact.kind] is [Artifact.DIRECTORY]
     *
     * @param taskInput The method reference the [TaskT] will use to retrieve the current artifact
     * version during [TaskT] execution (and only then).
     * @param taskOutput The method reference to retrieve the task output after successful task
     * execution.
     * @return The [InAndOutDirectoryOperationRequest] to set the desired operation type as well
     * as the target [SingleArtifact].
     */
    fun wiredWithDirectories(
        taskInput: (TaskT) -> DirectoryProperty,
        taskOutput: (TaskT) -> DirectoryProperty
    ): InAndOutDirectoryOperationRequest<TaskT>
}