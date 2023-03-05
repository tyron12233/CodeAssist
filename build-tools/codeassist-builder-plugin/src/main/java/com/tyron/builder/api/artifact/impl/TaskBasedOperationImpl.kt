package com.tyron.builder.api.artifact.impl

import com.tyron.builder.api.artifact.CombiningOperationRequest
import com.tyron.builder.api.artifact.InAndOutFileOperationRequest
import com.tyron.builder.api.artifact.OutOperationRequest
import com.tyron.builder.api.artifact.TaskBasedOperation
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.FileSystemLocationProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.TaskProvider

class TaskBasedOperationImpl<TaskT: Task>(
    val objects: ObjectFactory,
    override val artifacts: ArtifactsImpl,
    val taskProvider: TaskProvider<TaskT>
): TaskBasedOperation<TaskT>, ArtifactOperationRequest {

    override fun <FileTypeT : FileSystemLocation> wiredWith(
        taskOutput: (TaskT) -> FileSystemLocationProperty<FileTypeT>
    ): OutOperationRequest<FileTypeT> =
        OutOperationRequestImpl(artifacts, taskProvider, taskOutput).also {
            artifacts.addRequest(it)
            closeRequest()
        }

    override fun wiredWithFiles(
        taskInput: (TaskT) -> RegularFileProperty,
        taskOutput: (TaskT) -> RegularFileProperty
    ): InAndOutFileOperationRequest =
        InAndOutFileOperationRequestImpl(artifacts, taskProvider, taskInput, taskOutput).also {
            artifacts.addRequest(it)
            closeRequest()
        }

    override fun <FileTypeT : FileSystemLocation> wiredWith(
        taskInput: (TaskT) -> ListProperty<FileTypeT>,
        taskOutput: (TaskT) -> FileSystemLocationProperty<FileTypeT>
    ): CombiningOperationRequest<FileTypeT> =
        CombiningOperationRequestImpl(objects, artifacts, taskProvider, taskInput, taskOutput).also {
            artifacts.addRequest(it)
            closeRequest()
        }

    override fun wiredWithDirectories(
        taskInput: (TaskT) -> DirectoryProperty,
        taskOutput: (TaskT) -> DirectoryProperty
    ): InAndOutDirectoryOperationRequestImpl<TaskT> =
        InAndOutDirectoryOperationRequestImpl(artifacts, taskProvider, taskInput, taskOutput).also {
            artifacts.addRequest(it)
            closeRequest()
        }

    override val description: String
        get() = "Task ${taskProvider.name} was passed to Artifacts::use method without wiring any " +
            "input and/or output to an artifact."
}