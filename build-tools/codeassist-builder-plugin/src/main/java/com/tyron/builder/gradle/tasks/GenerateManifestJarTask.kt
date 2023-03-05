package com.tyron.builder.gradle.tasks

import com.tyron.builder.api.artifact.SingleArtifact
import com.tyron.builder.gradle.internal.component.ApkCreationConfig
import com.tyron.builder.gradle.internal.generators.ManifestClassData
import com.tyron.builder.gradle.internal.generators.ManifestClassGenerator
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.BuildAnalyzer
import com.tyron.builder.gradle.internal.tasks.NonIncrementalTask
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.packaging.JarFlinger
import com.tyron.builder.gradle.internal.tasks.TaskCategory
import com.tyron.builder.internal.utils.setDisallowChanges
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider

/**
 * Creates a Manifest Class as a compiled JAR file as [InternalArtifactType.COMPILE_MANIFEST_JAR].
 * This manifest class is used for accessing Android Manifest custom permission names.
 */
@CacheableTask
@BuildAnalyzer(primaryTaskCategory = TaskCategory.MANIFEST, secondaryTaskCategories = [TaskCategory.ZIPPING])
abstract class GenerateManifestJarTask : NonIncrementalTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mergedManifests: RegularFileProperty

    @get:Input
    abstract val namespace: Property<String>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @TaskAction
    override fun doTaskAction() {
        ManifestClassGenerator(
                ManifestClassData(
                        manifestFile = mergedManifests.get().asFile,
                        namespace = namespace.get(),
                        outputFilePath = outputJar.get().asFile
                )
        ).apply {
            if (customPermissions.any()) {
                generate()
            } else {
                // create an empty jar
                JarFlinger(outputJar.get().asFile.toPath()).close()
            }
        }
    }

    class CreationAction(
            creationConfig: ApkCreationConfig
    ) : VariantTaskCreationAction<GenerateManifestJarTask, ApkCreationConfig>(creationConfig) {
        override val name: String
            get() = computeTaskName("generate", "ManifestClass")
        override val type: Class<GenerateManifestJarTask>
            get() = GenerateManifestJarTask::class.java

        override fun handleProvider(taskProvider: TaskProvider<GenerateManifestJarTask>) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts.setInitialProvider(
                            taskProvider,
                            GenerateManifestJarTask::outputJar
                    ).withName("Manifest.jar")
                    .on(InternalArtifactType.COMPILE_MANIFEST_JAR)
        }

        override fun configure(task: GenerateManifestJarTask) {
            super.configure(task)
            creationConfig
                    .artifacts
                    .setTaskInputToFinalProduct(
                            SingleArtifact.MERGED_MANIFEST,
                            task.mergedManifests
                    )
            task.namespace.setDisallowChanges(creationConfig.namespace)
        }
    }
}
