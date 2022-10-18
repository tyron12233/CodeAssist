package com.tyron.builder.gradle.internal.tasks

import com.android.SdkConstants.DOT_JAR
import com.android.zipflinger.BytesSource
import com.android.zipflinger.ZipArchive
import com.google.common.annotations.VisibleForTesting
import com.tyron.builder.api.artifact.SingleArtifact
import com.tyron.builder.files.KeyedFileCache
import com.tyron.builder.gradle.internal.component.ApkCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.gradle.internal.tasks.factory.features.AssetsTaskCreationAction
import com.tyron.builder.gradle.internal.tasks.factory.features.AssetsTaskCreationActionImpl
import com.tyron.builder.internal.utils.setDisallowChanges
import com.tyron.builder.packaging.PackagingUtils
import com.tyron.builder.tasks.IncrementalTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileType
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.ChangeType
import org.gradle.work.FileChange
import org.gradle.work.Incremental
import org.gradle.work.InputChanges
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkQueue
import java.io.File
import java.nio.file.Files
import java.util.function.Predicate
import java.util.zip.Deflater
import java.util.zip.Deflater.BEST_SPEED
import java.util.zip.Deflater.DEFAULT_COMPRESSION
import javax.inject.Inject

/**
 * Task to compress assets before they're packaged in the APK.
 *
 * This task outputs a directory of single-entry jars (instead of a single jar) so that the
 * downstream packaging task doesn't have to manage its incremental state via a [KeyedFileCache].
 *
 * Each single-entry jar file's relative path in the output directory is equal to "assets/" + the
 * corresponding asset's relative path in the input directory + ".jar".
 */
@CacheableTask
@BuildAnalyzer(primaryTaskCategory = TaskCategory.APK_PACKAGING)
abstract class CompressAssetsTask: IncrementalTask() {

    @get:Incremental
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirs: DirectoryProperty

    @get:Input
    abstract val noCompress: ListProperty<String>

    @get:Input
    abstract val compressionLevel: Property<Int>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    override fun doTaskAction(inputChanges: InputChanges) {
        CompressAssetsDelegate(
            workerExecutor.noIsolation(),
            outputDir.get().asFile,
            PackagingUtils.getNoCompressPredicateForJavaRes(noCompress.get()),
            compressionLevel.get(),
            inputChanges.getFileChanges(inputDirs)
        ).run()
    }

    class CreationAction(
        creationConfig: ApkCreationConfig
    ) : VariantTaskCreationAction<CompressAssetsTask, ApkCreationConfig>(
        creationConfig
    ), AssetsTaskCreationAction by AssetsTaskCreationActionImpl(creationConfig) {

        override val name: String
            get() = computeTaskName("compress", "Assets")

        override val type: Class<CompressAssetsTask>
            get() = CompressAssetsTask::class.java

        override fun handleProvider(
            taskProvider: TaskProvider<CompressAssetsTask>
        ) {
            super.handleProvider(taskProvider)

            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                CompressAssetsTask::outputDir
            ).withName("out").on(InternalArtifactType.COMPRESSED_ASSETS)
        }

        override fun configure(
            task: CompressAssetsTask
        ) {
            super.configure(task)

            task.inputDirs.set(creationConfig.artifacts.get(SingleArtifact.ASSETS))
            task.noCompress.setDisallowChanges(assetsCreationConfig.androidResources.noCompress)
            task.compressionLevel.setDisallowChanges(
                if (creationConfig.debuggable) {
                    BEST_SPEED
                } else {
                    DEFAULT_COMPRESSION
                }
            )
        }
    }
}

/**
 * Delegate to compress assets
 */
@VisibleForTesting
class CompressAssetsDelegate(
    private val workQueue: WorkQueue,
    val outputDir: File,
    private val noCompressPredicate: Predicate<String>,
    private val compressionLevel: Int,
    val changes: Iterable<FileChange>
) {

    fun run() {
        for (change in changes) {
            if (change.fileType == FileType.DIRECTORY) {
                continue
            }
            val entryPath = "assets/${change.normalizedPath}"
            val targetFile = File(outputDir, entryPath + DOT_JAR)
            val entryCompressionLevel = if (noCompressPredicate.test(entryPath)) {
                Deflater.NO_COMPRESSION
            } else {
                compressionLevel
            }
            workQueue.submit(CompressAssetsWorkAction::class.java) {
                it.input.set(change.file)
                it.output.set(targetFile)
                it.entryPath.set(entryPath)
                it.entryCompressionLevel.set(entryCompressionLevel)
                it.changeType.set(change.changeType)
            }
        }
    }
}

/**
 * [WorkAction] to compress an asset file into a single-entry jar
 */
abstract class CompressAssetsWorkAction @Inject constructor(
    private val compressAssetsWorkParameters: CompressAssetsWorkParameters
): WorkAction<CompressAssetsWorkParameters> {

    override fun execute() {
        val output = compressAssetsWorkParameters.output.get().asFile.toPath()
        val changeType = compressAssetsWorkParameters.changeType.get()
        if (changeType != ChangeType.ADDED) {
            Files.deleteIfExists(output)
        }
        if (changeType != ChangeType.REMOVED) {
            Files.createDirectories(output.parent)
            ZipArchive(output).use { jar ->
                jar.add(
                    BytesSource(
                        compressAssetsWorkParameters.input.get().asFile.toPath(),
                        compressAssetsWorkParameters.entryPath.get(),
                        compressAssetsWorkParameters.entryCompressionLevel.get()
                    )
                )
            }
        }
    }
}

/**
 * [WorkParameters] for [CompressAssetsWorkAction]
 */
abstract class CompressAssetsWorkParameters: WorkParameters {
    abstract val input: RegularFileProperty
    abstract val output: RegularFileProperty
    abstract val entryPath: Property<String>
    abstract val entryCompressionLevel: Property<Int>
    abstract val changeType: Property<ChangeType>
}
