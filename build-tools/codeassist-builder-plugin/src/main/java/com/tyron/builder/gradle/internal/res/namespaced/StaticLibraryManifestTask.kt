package com.tyron.builder.gradle.internal.res.namespaced

import com.android.SdkConstants
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.BuildAnalyzer
import com.tyron.builder.gradle.internal.tasks.NonIncrementalTask
import com.tyron.builder.gradle.internal.tasks.TaskCategory
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.internal.utils.setDisallowChanges
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskProvider
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.nio.charset.StandardCharsets

/**
 * Task to write an android manifest for the res.apk static library
 *
 * Caching disabled by default for this task because the task does very little work.
 * Calculating cache hit/miss and fetching results is likely more expensive than
 * simply executing the task.
 */
@DisableCachingByDefault
@BuildAnalyzer(primaryTaskCategory = TaskCategory.MANIFEST)
abstract class StaticLibraryManifestTask : NonIncrementalTask() {

    @get:Input
    abstract val packageName: Property<String>

    @get:OutputFile
    abstract val manifestFile: RegularFileProperty

    override fun doTaskAction() {
        workerExecutor.noIsolation().submit(StaticLibraryManifestWorkAction::class.java) {
//            it.initializeFromAndroidVariantTask(this)
            it.manifestFile.set(manifestFile)
            it.packageName.set(packageName)
        }
    }

    class CreationAction(
        creationConfig: ComponentCreationConfig
    ) : VariantTaskCreationAction<StaticLibraryManifestTask, ComponentCreationConfig>(
        creationConfig
    ) {

        override val name: String
            get() = computeTaskName("create", "StaticLibraryManifest")
        override val type: Class<StaticLibraryManifestTask>
            get() = StaticLibraryManifestTask::class.java

        override fun handleProvider(
            taskProvider: TaskProvider<StaticLibraryManifestTask>
        ) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                StaticLibraryManifestTask::manifestFile
            ).withName(SdkConstants.ANDROID_MANIFEST_XML)
                .on(InternalArtifactType.STATIC_LIBRARY_MANIFEST)
        }

        override fun configure(
            task: StaticLibraryManifestTask
        ) {
            super.configure(task)
            task.packageName.setDisallowChanges(creationConfig.namespace)
        }
    }
}

abstract class StaticLibraryManifestWorkAction :
    WorkAction<StaticLibraryManifestRequest> {
    override fun execute() {
        parameters.manifestFile.asFile.get().outputStream().writer(StandardCharsets.UTF_8)
            .buffered().use {
                it.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                    .append("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n")
                    .append("    package=\"${parameters.packageName.get()}\"/>\n")
            }
    }
}

abstract class StaticLibraryManifestRequest : WorkParameters {
    abstract val manifestFile: RegularFileProperty
    abstract val packageName: Property<String>
}
