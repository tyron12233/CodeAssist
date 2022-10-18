package com.tyron.builder.gradle.internal.variant

import com.tyron.builder.api.artifact.impl.ArtifactsImpl
import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.core.ComponentType
import com.tyron.builder.gradle.internal.scope.MutableTaskContainer
import com.tyron.builder.gradle.internal.services.VariantServices
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import java.io.File

/** Data about a variant that produce a Library bundle (.aar)  */
class LibraryVariantData(
    componentIdentity: ComponentIdentity,
    artifacts: ArtifactsImpl,
    services: VariantServices,
    taskContainer: MutableTaskContainer
) : BaseVariantData(
    componentIdentity,
    artifacts,
    services,
    taskContainer
), TestedVariantData {
    private val testVariants: MutableMap<ComponentType, TestVariantData> = mutableMapOf()

    override fun getTestVariantData(type: ComponentType): TestVariantData? {
        return testVariants[type]
    }

    override fun setTestVariantData(testVariantData: TestVariantData, type: ComponentType) {
        testVariants[type] = testVariantData
    }

    // Overridden to add source folders to a generateAnnotationsTask, if it exists.
    override fun registerJavaGeneratingTask(
        taskProvider: TaskProvider<out Task>,
        generatedSourceFolders: Collection<File>
    ) {
        super.registerJavaGeneratingTask(taskProvider, generatedSourceFolders)
        addSourcesToGenerateAnnotationsTask(generatedSourceFolders)
    }

    // TODO: remove and use a normal dependency on the final list of source files.
    private fun addSourcesToGenerateAnnotationsTask(sourceFolders: Collection<File>) {
        println("TODO: addSourcesToGenerateAnnotationsTask")
//        taskContainer.generateAnnotationsTask?.let { taskProvider ->
//            taskProvider.configure { task ->
//                for (f in sourceFolders) {
//                    task.source(f)
//                }
//            }
//        }
    }
}