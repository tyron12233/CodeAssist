package com.tyron.builder.api.artifact

import com.tyron.builder.api.variant.BuiltArtifact
import com.tyron.builder.api.variant.BuiltArtifacts
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkQueue
import java.io.File
import java.util.function.Supplier

/**
 * When a [Directory] contains more than one artifact (for example, consider [SingleArtifact.APK] with multiple
 * APKs for different screen densities), this object will abstract away having to deal with
 * [BuiltArtifacts] and manually load and write the metadata files.
 *
 * Instead, users can focus on writing transformation code that transform one file at at time into
 * a newer version. It will also provide the ability to use Gradle's [WorkQueue] to multi-thread
 * the transformations.
 *
 * Here is an example of a [Task] copying (unchanged) the APK files from one location to another.
 *
 *
 * ```kotlin
 * // parameter interface to pass to work items.
 * interface WorkItemParameters: WorkParameters, Serializable {
 *    val inputApkFile: RegularFileProperty
 *    val outputApkFile: RegularFileProperty
 * }
 * ```
 *
 *
 *
 * ```kotlin
 * // work item that copies one file at a time.
 * abstract class WorkItem @Inject constructor(
 *      private val workItemParameters: WorkItemParameters
 * ): WorkAction<WorkItemParameters> {
 *
 *    override fun execute() {
 *      workItemParameters.inputApkFile.asFile.get().copyTo(
 *      workItemParameters.outputApkFile.get().asFile)
 *    }
 * }
 * ```
 *
 *
 * And the task that wires things together:
 *
 *
 * ```kotlin
 * abstract class CopyApksTask @Inject constructor(private val workers: WorkerExecutor): DefaultTask() {
 *
 * @get:InputFiles
 * abstract val apkFolder: DirectoryProperty
 *
 * @get:OutputDirectory
 * abstract val outFolder: DirectoryProperty
 *
 * @get:Internal
 * abstract val transformationRequest: Property<ArtifactTransformationRequest<CopyApksTask>>
 *
 * @TaskAction
 * fun taskAction() {
 *  transformationRequest.get().submit(
 *     this,
 *     workers.noIsolation(),
 *     WorkItem::class.java) {
 *     builtArtifact: BuiltArtifact,
 *     outputLocation: Directory,
 *     param: WorkItemParameters ->
 *       val inputFile = File(builtArtifact.outputFile)
 *       param.inputApkFile.set(inputFile)
 *       param.outputApkFile.set(File(outputLocation.asFile, inputFile.name))
 *       param.outputApkFile.get().asFile
 *     }
 *   }
 * }
 * ```
 *
 */
interface ArtifactTransformationRequest<TaskT: Task> {

    /**
     * Submit a `org.gradle.workers` style of [WorkAction] to process each input [BuiltArtifact].
     *
     * @param task The Task initiating the [WorkQueue] requests.
     * @param workQueue The Gradle [WorkQueue] instance to use to spawn worker items with.
     * @param actionType The type of the [WorkAction] subclass that process that input [BuiltArtifact].
     * @param parameterConfigurator The lambda to configure instances of [parameterType] for each
     * [BuiltArtifact].
     */
    fun <ParamT: WorkParameters> submit(
        task: TaskT,
        workQueue: WorkQueue,
        actionType: Class<out WorkAction<ParamT>>,
        parameterConfigurator: (
            builtArtifact: BuiltArtifact,
            outputLocation: Directory,
            parameters: ParamT) -> File
    ): Supplier<BuiltArtifacts>

    /**
     * Submit a lambda to process each input [BuiltArtifact] object synchronously.
     */
    fun submit(task: TaskT, transformer: (input: BuiltArtifact) -> File)
}