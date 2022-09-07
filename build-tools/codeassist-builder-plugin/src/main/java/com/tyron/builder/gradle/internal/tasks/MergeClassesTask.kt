package com.tyron.builder.gradle.internal.tasks

import com.android.SdkConstants.DOT_JAR
import com.tyron.builder.gradle.internal.TaskManager
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.packaging.JarCreatorFactory
import com.tyron.builder.gradle.internal.packaging.JarCreatorType
import com.tyron.builder.gradle.internal.pipeline.TransformManager
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.dexing.ClassFileInput.CLASS_MATCHER
import com.tyron.builder.internal.utils.fromDisallowChanges
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskProvider
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.util.zip.Deflater

/**
 * A task that merges the project and runtime dependency class files into a single jar.
 *
 * This task is necessary in a feature or base module when minification is enabled in the base
 * because the base needs to know which classes came from which modules to eventually split the
 * classes to the correct APKs via the Dex Splitter.
 */
@CacheableTask
//@BuildAnalyzer(primaryTaskCategory = TaskCategory.COMPILED_CLASSES, secondaryTaskCategories = [TaskCategory.MERGING])
abstract class MergeClassesTask : NonIncrementalTask() {

    @get:Classpath
    abstract val inputFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:Input
    lateinit var jarCreatorType: JarCreatorType
        private set

    override fun doTaskAction() {
        workerExecutor.noIsolation().submit(MergeClassesWorkAction::class.java) {
//            it.initializeFromAndroidVariantTask(this)
            it.inputFiles.from(inputFiles)
            it.outputFile.set(outputFile)
            it.jarCreatorType.set(jarCreatorType)
        }
    }

    abstract class MergeClassesWorkAction :
        WorkAction<MergeClassesWorkAction.Parameters> {
        abstract class Parameters : WorkParameters {
            abstract val inputFiles: ConfigurableFileCollection
            abstract val outputFile: RegularFileProperty
            abstract val jarCreatorType: Property<JarCreatorType>
        }

        override fun execute() {
            JarCreatorFactory.make(
                parameters.outputFile.asFile.get().toPath(),
                CLASS_MATCHER,
                parameters.jarCreatorType.get()
            ).use { out ->
                // Don't compress because compressing takes extra time, and this jar doesn't go
                // into any APKs or AARs.
                out.setCompressionLevel(Deflater.NO_COMPRESSION)
                parameters.inputFiles.forEach {
                    if (it.isFile && it.name.endsWith(DOT_JAR)) {
                        out.addJar(it.toPath())
                    } else if (it.isDirectory) {
                        out.addDirectory(it.toPath())
                    }
                }
            }
        }
    }

    class CreationAction(
        creationConfig: ComponentCreationConfig
    ) : VariantTaskCreationAction<MergeClassesTask, ComponentCreationConfig>(
        creationConfig
    ) {
        override val type = MergeClassesTask::class.java
        override val name: String = computeTaskName("merge", "Classes")

        // Because ordering matters for the transform pipeline, we need to fetch the classes as soon
        // as this creation action is instantiated.
        @Suppress("DEPRECATION") // Legacy support
        private val inputFiles =
            creationConfig
                .transformManager
                .getPipelineOutputAsFileCollection { contentTypes, scopes ->
                    contentTypes.contains(com.tyron.builder.api.transform.QualifiedContent.DefaultContentType.CLASSES)
                            && scopes.intersect(TransformManager.SCOPE_FULL_PROJECT).isNotEmpty()
                }

        override fun handleProvider(
            taskProvider: TaskProvider<MergeClassesTask>
        ) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                MergeClassesTask::outputFile
            ).withName(if (creationConfig.componentType.isBaseModule) {
                "base.jar"
            } else {
                TaskManager.getFeatureFileName(creationConfig.services.projectInfo.path, DOT_JAR)
            }).on(InternalArtifactType.MODULE_AND_RUNTIME_DEPS_CLASSES)
        }

        override fun configure(
            task: MergeClassesTask
        ) {
            super.configure(task)
            task.inputFiles.fromDisallowChanges(inputFiles)
            task.jarCreatorType = creationConfig.global.jarCreatorType
        }
    }
}
