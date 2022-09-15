package com.tyron.builder.gradle.internal.tasks.databinding

import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.BuildAnalyzer
import com.tyron.builder.gradle.internal.tasks.NonIncrementalTask
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.internal.utils.setDisallowChanges
import com.tyron.builder.gradle.options.BooleanOption
import com.tyron.builder.gradle.internal.tasks.TaskCategory
import com.android.utils.FileUtils
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskProvider
import org.gradle.work.DisableCachingByDefault

/**
 * Task to create an empty class annotated with a data binding annotation (it could be any data
 * binding annotation), so that the Java compiler still invokes data binding in the case that data
 * binding is used (e.g., in layout files) but the source code does not use data binding
 * annotations.
 *
 * Caching disabled by default for this task because the task does very little work.
 * The taskAction does no complex processing -- it just writes a file to disk with some
 * statically determinate content.

 * Calculating cache hit/miss and fetching results is likely more expensive than
 * simply executing the task.
 */
@DisableCachingByDefault
@BuildAnalyzer(primaryTaskCategory = TaskCategory.DATA_BINDING)
abstract class DataBindingTriggerTask : NonIncrementalTask() {

    @get:Input
    abstract val namespace: Property<String>

    @get:Input
    abstract val useAndroidX: Property<Boolean>

    @get:OutputDirectory
    abstract val triggerDir: DirectoryProperty

    override fun doTaskAction() {
        // Create an empty class annotated with a data binding annotation. It could be any data
        // binding annotation, so use @BindingBuildInfo for now.
        val annotation: Class<*> =
            if (useAndroidX.get()) {
                androidx.databinding.BindingBuildInfo::class.java
            } else {
                android.databinding.BindingBuildInfo::class.java
            }
        val fileContents =
            """
            package ${namespace.get()};

            @${annotation.canonicalName}
            public class $DATA_BINDING_TRIGGER_CLASS {}
            """.trimIndent()

        val outputFile = triggerDir.get().asFile.resolve(
            "${namespace.get().replace('.', '/')}/$DATA_BINDING_TRIGGER_CLASS.java"
        )
        FileUtils.mkdirs(outputFile.parentFile)
        outputFile.writeText(fileContents)
    }

    class CreationAction(creationConfig: ComponentCreationConfig) :
        VariantTaskCreationAction<DataBindingTriggerTask, ComponentCreationConfig>(
            creationConfig
        ) {

        override val name: String = computeTaskName("dataBindingTrigger")

        override val type: Class<DataBindingTriggerTask> = DataBindingTriggerTask::class.java

        override fun handleProvider(
            taskProvider: TaskProvider<DataBindingTriggerTask>
        ) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                DataBindingTriggerTask::triggerDir
            ).on(InternalArtifactType.DATA_BINDING_TRIGGER)
        }

        override fun configure(task: DataBindingTriggerTask) {
            super.configure(task)
            task.namespace.setDisallowChanges(creationConfig.namespace)
            task.useAndroidX.setDisallowChanges(
                creationConfig.services.projectOptions[BooleanOption.USE_ANDROID_X]
            )
        }
    }
}

const val DATA_BINDING_TRIGGER_CLASS = "DataBindingTriggerClass"