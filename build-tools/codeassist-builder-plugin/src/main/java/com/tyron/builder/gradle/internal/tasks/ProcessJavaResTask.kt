package com.tyron.builder.gradle.internal.tasks

import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.work.DisableCachingByDefault
import java.io.File

@DisableCachingByDefault
@BuildAnalyzer(primaryTaskCategory = TaskCategory.JAVA_RESOURCES)
abstract class ProcessJavaResTask : Sync(), VariantAwareTask {

    @get:OutputDirectory
    abstract val outDirectory: DirectoryProperty

    // override to remove the @OutputDirectory annotation
    @Internal
    override fun getDestinationDir(): File {
        return outDirectory.get().asFile
    }

    @get:Internal
    override lateinit var variantName: String

    /** Configuration Action for a process*JavaRes tasks.  */
    class CreationAction(
        creationConfig: ComponentCreationConfig
    ) : VariantTaskCreationAction<ProcessJavaResTask, ComponentCreationConfig>(
        creationConfig
    ) {

        override val name: String
            get() = computeTaskName("process", "JavaRes")

        override val type: Class<ProcessJavaResTask>
            get() = ProcessJavaResTask::class.java

        override fun handleProvider(
            taskProvider: TaskProvider<ProcessJavaResTask>
        ) {
            super.handleProvider(taskProvider)
            creationConfig.taskContainer.processJavaResourcesTask = taskProvider

            creationConfig.artifacts.setInitialProvider(
                    taskProvider,
                    ProcessJavaResTask::outDirectory
                ).withName("out").on(InternalArtifactType.JAVA_RES)
        }

        override fun configure(
            task: ProcessJavaResTask
        ) {
            super.configure(task)

            task.from(creationConfig.sources.resources.getAsFileTrees())
            task.duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
    }
}
