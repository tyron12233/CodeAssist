package com.tyron.builder.gradle.internal.res.namespaced

import com.android.utils.FileUtils
import com.google.common.collect.ImmutableList
import com.tyron.builder.core.ComponentTypeImpl
import com.tyron.builder.gradle.internal.AndroidJarInput
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.component.TestComponentCreationConfig
import com.tyron.builder.gradle.internal.initialize
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.scope.InternalMultipleArtifactType
import com.tyron.builder.gradle.internal.services.Aapt2Input
import com.tyron.builder.gradle.internal.services.getErrorFormatMode
import com.tyron.builder.gradle.internal.services.registerAaptService
import com.tyron.builder.gradle.internal.tasks.BuildAnalyzer
import com.tyron.builder.gradle.internal.tasks.NonIncrementalTask
import com.tyron.builder.gradle.internal.tasks.TaskCategory
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.internal.aapt.AaptOptions
import com.tyron.builder.internal.aapt.AaptPackageConfig
import com.tyron.builder.internal.utils.setDisallowChanges
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.File

/**
 * Task to link the resources in a library project into an AAPT2 static library.
 */
@CacheableTask
@BuildAnalyzer(primaryTaskCategory = TaskCategory.ANDROID_RESOURCES, secondaryTaskCategories = [TaskCategory.LINKING])
abstract class LinkLibraryAndroidResourcesTask : NonIncrementalTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputResourcesDirectories: ListProperty<Directory>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val libraryDependencies: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sharedLibraryDependencies: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    @get:Optional
    abstract val tested: RegularFileProperty

    @get:Input
    abstract val namespace: Property<String>

    @get:Input
    abstract val mergeOnly: Property<Boolean>

    @get:OutputDirectory lateinit var aaptIntermediateDir: File private set
    @get:OutputFile abstract val staticLibApk: RegularFileProperty

    @get:Nested
    abstract val androidJarInput: AndroidJarInput

    @get:Nested
    abstract val aapt2: Aapt2Input

    override fun doTaskAction() {

        val imports = ImmutableList.builder<File>()
        // Link against library dependencies
        if (!mergeOnly.get()) {
            imports.addAll(libraryDependencies)
            imports.addAll(sharedLibraryDependencies)
        }

        val request = AaptPackageConfig(
                androidJarPath = androidJarInput.getAndroidJar().get().absolutePath,
                manifestFile = manifestFile.get().asFile,
                options = AaptOptions(),
                resourceDirs = ImmutableList.copyOf(inputResourcesDirectories.get().stream()
                    .map(Directory::getAsFile).iterator()),
                staticLibrary = true,
                imports = imports.build(),
                resourceOutputApk = staticLibApk.get().asFile,
                componentType = ComponentTypeImpl.LIBRARY,
                customPackageForR = namespace.get(),
                intermediateDir = aaptIntermediateDir,
                mergeOnly = mergeOnly.get())

        val aapt2ServiceKey = aapt2.registerAaptService()
        workerExecutor.noIsolation().submit(Aapt2LinkRunnable::class.java) {
//            it.initializeFromAndroidVariantTask(this)
            it.aapt2ServiceKey.set(aapt2ServiceKey)
            it.request.set(request)
            it.errorFormatMode.set(aapt2.getErrorFormatMode())
        }
    }

    class CreationAction(
        creationConfig: ComponentCreationConfig
    ) : VariantTaskCreationAction<LinkLibraryAndroidResourcesTask, ComponentCreationConfig>(
        creationConfig
    ) {

        override val name: String
            get() = computeTaskName("link", "Resources")
        override val type: Class<LinkLibraryAndroidResourcesTask>
            get() = LinkLibraryAndroidResourcesTask::class.java

        override fun handleProvider(
            taskProvider: TaskProvider<LinkLibraryAndroidResourcesTask>
        ) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                LinkLibraryAndroidResourcesTask::staticLibApk
            ).withName("res.apk").on(InternalArtifactType.RES_STATIC_LIBRARY)
        }

        override fun configure(
            task: LinkLibraryAndroidResourcesTask
        ) {
            super.configure(task)

            creationConfig.artifacts.setTaskInputToFinalProduct(
                InternalArtifactType.STATIC_LIBRARY_MANIFEST,
                task.manifestFile
            )

            task.inputResourcesDirectories.set(
                creationConfig.artifacts.getAll(
                    InternalMultipleArtifactType.RES_COMPILED_FLAT_FILES))
            if (!creationConfig.debuggable) {
                task.libraryDependencies.from(
                        creationConfig.variantDependencies.getArtifactFileCollection(
                                AndroidArtifacts.ConsumedConfigType.COMPILE_CLASSPATH,
                                AndroidArtifacts.ArtifactScope.ALL,
                                AndroidArtifacts.ArtifactType.RES_STATIC_LIBRARY))
                task.sharedLibraryDependencies.from(
                        creationConfig.variantDependencies.getArtifactFileCollection(
                                AndroidArtifacts.ConsumedConfigType.COMPILE_CLASSPATH,
                                AndroidArtifacts.ArtifactScope.ALL,
                                AndroidArtifacts.ArtifactType.RES_SHARED_STATIC_LIBRARY))
            }

            task.mergeOnly.setDisallowChanges(creationConfig.debuggable)

            (creationConfig as? TestComponentCreationConfig)?.onTestedVariant {
                it.artifacts.setTaskInputToFinalProduct(
                    InternalArtifactType.RES_STATIC_LIBRARY,
                    task.tested
                )
            }

            task.aaptIntermediateDir =
                    FileUtils.join(
                            creationConfig.services.projectInfo.getIntermediatesDir(), "res-link-intermediate", creationConfig.dirName)

            task.namespace.setDisallowChanges(creationConfig.namespace)

            creationConfig.services.initializeAapt2Input(task.aapt2)

            task.androidJarInput.initialize(creationConfig)
        }
    }
}
