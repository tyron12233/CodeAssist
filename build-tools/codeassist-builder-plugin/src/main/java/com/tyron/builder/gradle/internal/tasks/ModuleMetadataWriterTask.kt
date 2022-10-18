package com.tyron.builder.gradle.internal.tasks

import com.tyron.builder.gradle.internal.component.ApplicationCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.internal.utils.setDisallowChanges
import com.tyron.builder.gradle.internal.tasks.TaskCategory
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskProvider
import org.gradle.work.DisableCachingByDefault

/**
 * Task responsible for publishing this module metadata (like its application ID) for other modules
 * to consume.
 *
 * If the module is an application module, it publishes the value coming from the variant config.
 *
 * If the module is a base feature, it consumes the value coming from the (installed) application
 * module and republishes it.
 *
 * Both dynamic-feature and feature modules consumes it, from the application module and the base
 * feature module respectively.
 */
@DisableCachingByDefault
@BuildAnalyzer(primaryTaskCategory = TaskCategory.METADATA)
abstract class ModuleMetadataWriterTask : NonIncrementalTask() {

    @get:Input
    abstract val applicationId: Property<String>

    @get:Input
    @get:Optional
    abstract val versionCode: Property<Int?>

    @get:Input
    @get:Optional
    abstract val versionName: Property<String?>

    @get:Input
    abstract val debuggable: Property<Boolean>

    @get:Input
    abstract val abiFilters: ListProperty<String>

    @get:Input
    abstract val ignoredLibraryKeepRules: SetProperty<String>

    @get:Input
    abstract val ignoreAllLibraryKeepRules: Property<Boolean>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    override fun doTaskAction() {
        val declaration =
            ModuleMetadata(
                applicationId = applicationId.get(),
                versionCode = versionCode.orNull?.toString(),
                versionName = versionName.orNull,
                debuggable = debuggable.get(),
                abiFilters = abiFilters.get(),
                ignoredLibraryKeepRules = ignoredLibraryKeepRules.get(),
                ignoreAllLibraryKeepRules = ignoreAllLibraryKeepRules.get()
            )

        declaration.save(outputFile.get().asFile)
    }

    internal class CreationAction(creationConfig: ApplicationCreationConfig) :
        VariantTaskCreationAction<ModuleMetadataWriterTask, ApplicationCreationConfig>(
            creationConfig
        ) {

        override val name: String
            get() = computeTaskName("write", "ModuleMetadata")
        override val type: Class<ModuleMetadataWriterTask>
            get() = ModuleMetadataWriterTask::class.java

        override fun handleProvider(
            taskProvider: TaskProvider<ModuleMetadataWriterTask>
        ) {
            super.handleProvider(taskProvider)
            // publish the ID for the dynamic features (whether it's hybrid or not) to consume.
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                ModuleMetadataWriterTask::outputFile
            ).withName(ModuleMetadata.PERSISTED_FILE_NAME)
                .on(InternalArtifactType.BASE_MODULE_METADATA)
        }

        override fun configure(
            task: ModuleMetadataWriterTask
        ) {
            super.configure(task)
            task.applicationId.set(creationConfig.applicationId)
            task.debuggable.setDisallowChanges(creationConfig.debuggable)
            task.versionCode.setDisallowChanges(creationConfig.outputs.getMainSplit().versionCode)
            task.versionName.setDisallowChanges(creationConfig.outputs.getMainSplit().versionName)
            task.abiFilters.setDisallowChanges(creationConfig.supportedAbis.sorted())
            task.ignoredLibraryKeepRules.setDisallowChanges(creationConfig.ignoredLibraryKeepRules)
            task.ignoreAllLibraryKeepRules.setDisallowChanges(
                    creationConfig.ignoreAllLibraryKeepRules)
        }
    }
}
