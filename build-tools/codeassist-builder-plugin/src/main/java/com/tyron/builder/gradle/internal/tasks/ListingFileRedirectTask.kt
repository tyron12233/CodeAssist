package com.tyron.builder.gradle.internal.tasks

import com.android.ide.common.build.ListingFileRedirect
import com.tyron.builder.api.artifact.Artifact
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.tasks.factory.TaskCreationAction
import com.tyron.builder.internal.utils.setDisallowChanges
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

/**
 * [org.gradle.api.Task] to create a redirect file that contains the location of the IDE model
 * file. The location of the redirect file is never changing and cannot be "replaced" by anyone.
 * The location is passed through the model to the IDE which is expecting to always find the
 * redirect file at the same location independently on where tasks will put APK, Bundle, etc...
 *
 * For instance, if any other plugin decide to replace the APKs, the APK_IDE_MODEL will be
 * automatically created by the variant API in the new location. The redirect file will not change
 * and will just point to the new location for the model file.
 *
 * Caching is disabled as the full path to the listing file is used as input. Plus the task
 * execution should be so fast, that it outweighs the benefits in performance.
 */
@DisableCachingByDefault
@BuildAnalyzer(primaryTaskCategory = TaskCategory.SYNC)
abstract class ListingFileRedirectTask: NonIncrementalTask() {

    @get:OutputFile
    abstract val redirectFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val listingFile: RegularFileProperty

    @TaskAction
    override fun doTaskAction() {
        ListingFileRedirect.writeRedirect(
            listingFile = listingFile.asFile.get(),
            into = redirectFile.asFile.get()
        )
    }

    internal class CreationAction(
        private val creationConfig: ComponentCreationConfig,
        taskSuffix: String,
        private val inputArtifactType: Artifact.Single<RegularFile>,
        private val outputArtifactType: Artifact.Single<RegularFile>,
    ) : TaskCreationAction<ListingFileRedirectTask>() {

        override val type = ListingFileRedirectTask::class.java
        override val name = creationConfig.computeTaskName(
            prefix = "create",
            suffix = "${taskSuffix}ListingFileRedirect")

        override fun handleProvider(taskProvider: TaskProvider<ListingFileRedirectTask>) {
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                ListingFileRedirectTask::redirectFile
            ).withName(ListingFileRedirect.REDIRECT_FILE_NAME).on(outputArtifactType)
        }

        override fun configure(task: ListingFileRedirectTask) {
            task.configureVariantProperties(variantName = "", creationConfig.services.buildServiceRegistry)
            task.listingFile.setDisallowChanges(
                creationConfig.artifacts.get(inputArtifactType)
            )
        }
    }
}
