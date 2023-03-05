package com.tyron.builder.gradle.tasks.sync
//import com.tyron.builder.gradle.internal.tasks.TaskCategory
import com.android.ide.common.build.filebasedproperties.variant.VariantProperties
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.NonIncrementalTask
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskProvider
import org.gradle.work.DisableCachingByDefault
import java.io.BufferedOutputStream
import java.io.FileOutputStream

@DisableCachingByDefault
//@BuildAnalyzer(primaryTaskCategory = TaskCategory.SYNC)
abstract class AbstractVariantModelTask: NonIncrementalTask() {

    companion object {
        fun getTaskName(creationConfig: ComponentCreationConfig) =
            creationConfig.computeTaskName("create", "VariantModel")
    }

    @get:OutputFile
    abstract val outputModelFile: RegularFileProperty

    override fun doTaskAction() {
        val variant = VariantProperties.newBuilder().also { variant ->
            addVariantContent(variant)
        }.build()

        BufferedOutputStream(FileOutputStream(outputModelFile.asFile.get())).use {
            variant.writeTo(it)
        }
    }

    protected abstract fun addVariantContent(variant: VariantProperties.Builder)

    abstract class CreationAction<T : AbstractVariantModelTask, U: ComponentCreationConfig>(
        creationConfig: U
    ): VariantTaskCreationAction<T, U>(
            creationConfig = creationConfig,
            dependsOnPreBuildTask = false,
        ) {

        override val name: String
            get() = getTaskName(creationConfig)

        override fun handleProvider(taskProvider: TaskProvider<T>) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                AbstractVariantModelTask::outputModelFile
            ).on(InternalArtifactType.VARIANT_MODEL)
        }
    }
}
