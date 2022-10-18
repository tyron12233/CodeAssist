package com.tyron.builder.gradle.internal.tasks

import com.android.utils.FileUtils
import com.tyron.builder.api.artifact.SingleArtifact
import com.tyron.builder.gradle.internal.component.ApkCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.packaging.JarMerger
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.io.File

/**
 * Package all the APKs and mapping file into a zip for publishing to a repo.
 */
@DisableCachingByDefault
//@BuildAnalyzer(primaryTaskCategory = TaskCategory.APK_PACKAGING)
abstract class ApkZipPackagingTask : NonIncrementalTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apkFolder: DirectoryProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val mappingFile: RegularFileProperty

    @get:OutputFile
    abstract val apkZipFile: RegularFileProperty

    override fun doTaskAction() {
        workerExecutor.noIsolation().submit(ApkZipPackagingRunnable::class.java) {
//            it.initializeFromAndroidVariantTask(this)
            it.apkFolder.set(apkFolder)
            it.mappingFile.set(mappingFile)
            it.zipOutputFile.set(apkZipFile)
        }
    }

    abstract class Params : WorkParameters {
        abstract val apkFolder: DirectoryProperty
        abstract val mappingFile: RegularFileProperty
        abstract val zipOutputFile: RegularFileProperty
    }

    abstract class ApkZipPackagingRunnable : WorkAction<Params> {
        override fun execute() {
            FileUtils.deleteIfExists(parameters.zipOutputFile.asFile.get())

            val sourceFiles = parameters.apkFolder.asFile.get().listFiles() ?: emptyArray<File>()

            JarMerger(parameters.zipOutputFile.asFile.get().toPath()).use { jar ->
                for (sourceFile in sourceFiles) {
                    jar.addFile(sourceFile.name, sourceFile.toPath())
                }

                parameters.mappingFile.asFile.orNull?.let {
                    jar.addFile(it.name, it.toPath())
                }
            }
        }
    }

    class CreationAction(creationConfig: ApkCreationConfig) :
        VariantTaskCreationAction<ApkZipPackagingTask, ApkCreationConfig>(
            creationConfig
        ) {

        override val name: String
            get() = computeTaskName("zipApksFor")
        override val type: Class<ApkZipPackagingTask>
            get() = ApkZipPackagingTask::class.java

        override fun handleProvider(
            taskProvider: TaskProvider<ApkZipPackagingTask>
        ) {
            super.handleProvider(taskProvider)

            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                ApkZipPackagingTask::apkZipFile
            ).withName("apks.zip").on(InternalArtifactType.APK_ZIP)
        }

        override fun configure(
            task: ApkZipPackagingTask
        ) {
            super.configure(task)

            creationConfig.artifacts.setTaskInputToFinalProduct(
                SingleArtifact.APK, task.apkFolder
            )
            creationConfig.artifacts.setTaskInputToFinalProduct(
                SingleArtifact.OBFUSCATION_MAPPING_FILE,
                task.mappingFile
            )
        }
    }
}
