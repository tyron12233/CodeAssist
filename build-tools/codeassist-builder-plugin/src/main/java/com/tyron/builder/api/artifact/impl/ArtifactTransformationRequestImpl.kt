package com.tyron.builder.api.artifact.impl

import com.tyron.builder.api.artifact.Artifact
import com.tyron.builder.api.artifact.ArtifactTransformationRequest
import com.tyron.builder.api.variant.BuiltArtifact
import com.tyron.builder.api.variant.BuiltArtifacts
import com.tyron.builder.api.variant.impl.BuiltArtifactsImpl
import com.tyron.builder.api.variant.impl.BuiltArtifactsLoaderImpl
import com.tyron.builder.gradle.internal.workeractions.DecoratedWorkParameters
import com.tyron.builder.tasks.BaseTask
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.FileSystemLocationProperty
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkQueue
import java.io.File
import java.io.Serializable
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

class ArtifactTransformationRequestImpl<TaskT: Task>(
    private val builtArtifactsReference: AtomicReference<BuiltArtifactsImpl>,
    private val inputLocation: (TaskT) -> FileSystemLocationProperty<Directory>,
    private val outputLocation: (TaskT) -> FileSystemLocationProperty<Directory>,
    private val outputArtifactType: Artifact.Single<Directory>
) : Serializable, ArtifactTransformationRequest<TaskT> {

    override fun <ParamT: WorkParameters> submit(
        task: TaskT,
        workQueue: WorkQueue,
        actionType: Class<out WorkAction<ParamT>>,
        parameterConfigurator: (builtArtifact: BuiltArtifact, outputLocation: Directory, parameters: ParamT) -> File
    ): Supplier<BuiltArtifacts> {

        val mapOfBuiltArtifactsToParameters = mutableMapOf<BuiltArtifact, File>()
        val sourceBuiltArtifacts =
            BuiltArtifactsLoaderImpl().load(inputLocation(task).get())

        if (sourceBuiltArtifacts == null) {
            builtArtifactsReference.set(
                BuiltArtifactsImpl(
                    artifactType = outputArtifactType,
                    applicationId = "unknown",
                    variantName = "unknown",
                    elements = listOf()))
            return Supplier { builtArtifactsReference.get() }
        }

        sourceBuiltArtifacts.elements.forEach {builtArtifact ->
            workQueue.submit(actionType) {parameters ->

                if (task is BaseTask && parameters is DecoratedWorkParameters) {
                    // Record the worker creation and provide enough context to the WorkerActionAdapter
                    // to be able to send the necessary events.
                    val workerKey = "${task.path}${builtArtifact.hashCode()}"
                    parameters.taskPath.set(task.path)
                    parameters.workerKey.set(workerKey)
//                    parameters.analyticsService.set(task.analyticsService)

//                    parameters.analyticsService.get()
//                        .workerAdded(task.path, workerKey)
                }

                mapOfBuiltArtifactsToParameters[builtArtifact] =
                    parameterConfigurator(
                        builtArtifact,
                        outputLocation(task).get(),
                        parameters)
            }
        }

        builtArtifactsReference.set(
            BuiltArtifactsImpl(
                artifactType = outputArtifactType,
                applicationId = sourceBuiltArtifacts.applicationId,
                variantName = sourceBuiltArtifacts.variantName,
                elements = sourceBuiltArtifacts.elements
                    .map {
                        val output = mapOfBuiltArtifactsToParameters[it]
                            ?: throw RuntimeException("Unknown BuiltArtifact $it, file a bug")
                        it.newOutput(output.toPath())
                    }
            )
        )
        return Supplier {
            // since the user code wants to have access to the new BuiltArtifacts, await on the
            // WorkQueue so we are sure the output files are all present.
            workQueue.await()
            builtArtifactsReference.get()
        }
    }

    internal fun wrapUp(task: TaskT) {
        // save the metadata file in the output location upon completion of all the workers.
        builtArtifactsReference.get().save(outputLocation(task).get())
    }

    override fun submit(
        task: TaskT,
        transformer: (input: BuiltArtifact) -> File) {

        val sourceBuiltArtifacts = BuiltArtifactsLoaderImpl().load(inputLocation(task).get())
            ?: throw RuntimeException("No provided artifacts.")

        builtArtifactsReference.set(BuiltArtifactsImpl(
            applicationId = sourceBuiltArtifacts.applicationId,
            variantName = sourceBuiltArtifacts.variantName,
            artifactType = outputArtifactType,
            elements = sourceBuiltArtifacts.elements.map {
                it.newOutput(transformer(it).toPath())
            }))
    }
}