package com.tyron.builder.gradle.internal.res.namespaced

import com.android.SdkConstants
import com.android.utils.FileUtils
import com.google.common.collect.ImmutableList
import com.tyron.builder.core.ComponentTypeImpl
import com.tyron.builder.gradle.internal.AndroidJarInput
import com.tyron.builder.gradle.internal.component.ApkCreationConfig
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.initialize
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.services.Aapt2Input
import com.tyron.builder.gradle.internal.services.getErrorFormatMode
import com.tyron.builder.gradle.internal.services.registerAaptService
import com.tyron.builder.gradle.internal.tasks.BuildAnalyzer
import com.tyron.builder.gradle.internal.tasks.NonIncrementalTask
import com.tyron.builder.gradle.internal.tasks.TaskCategory
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.gradle.internal.tasks.factory.features.AndroidResourcesTaskCreationAction
import com.tyron.builder.gradle.internal.tasks.factory.features.AndroidResourcesTaskCreationActionImpl
import com.tyron.builder.internal.aapt.AaptOptions
import com.tyron.builder.internal.aapt.AaptPackageConfig
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import java.io.File

/**
 * Task to link the resource and the AAPT2 static libraries of its dependencies.
 *
 * Currently only implemented for tests. TODO: Clean up split support so this can support splits too.
 *
 * Outputs an ap_ file that can then be merged with the rest of the app to become a functioning apk,
 * as well as the generated R classes for this app that can be compiled against.
 */
@CacheableTask
@BuildAnalyzer(primaryTaskCategory = TaskCategory.ANDROID_RESOURCES)
abstract class ProcessAndroidAppResourcesTask : NonIncrementalTask() {

    @get:InputFiles @get:Optional @get:PathSensitive(PathSensitivity.RELATIVE) lateinit var aaptFriendlyManifestFileDirectory: Provider<Directory> private set
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) lateinit var manifestFileDirectory: Provider<Directory> private set
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val thisSubProjectStaticLibrary: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) lateinit var libraryDependencies: FileCollection private set

    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) lateinit var sharedLibraryDependencies: FileCollection private set

    @get:OutputDirectory lateinit var aaptIntermediateDir: File private set
    @get:OutputDirectory abstract val rClassSource: DirectoryProperty
    @get:OutputFile abstract val resourceApUnderscoreDirectory: DirectoryProperty

    @get:Nested
    abstract val aapt2: Aapt2Input

    @get:Input
    @get:Optional
    abstract val noCompress: ListProperty<String>

    @get:Nested
    abstract val androidJarInput: AndroidJarInput

    override fun doTaskAction() {
        val staticLibraries = ImmutableList.builder<File>()
        staticLibraries.add(thisSubProjectStaticLibrary.get().asFile)
        staticLibraries.addAll(libraryDependencies.files)
        val manifestFile = if (aaptFriendlyManifestFileDirectory.isPresent())
            (File(aaptFriendlyManifestFileDirectory.get().asFile, SdkConstants.ANDROID_MANIFEST_XML))
        else (File(manifestFileDirectory.get().asFile, SdkConstants.ANDROID_MANIFEST_XML))

        val config = AaptPackageConfig(
                androidJarPath = androidJarInput.getAndroidJar().get().absolutePath,
                manifestFile = manifestFile,
                options = AaptOptions(noCompress.orNull, additionalParameters = null),
                staticLibraryDependencies = staticLibraries.build(),
                imports = ImmutableList.copyOf(sharedLibraryDependencies.asIterable()),
                sourceOutputDir = rClassSource.get().asFile,
                resourceOutputApk = resourceApUnderscoreDirectory.get().file("res.apk").asFile,
                componentType = ComponentTypeImpl.LIBRARY,
                intermediateDir = aaptIntermediateDir)

        val aapt2ServiceKey = aapt2.registerAaptService()
        workerExecutor.noIsolation().submit(Aapt2LinkRunnable::class.java) {
//            it.initializeFromAndroidVariantTask(this)
            it.aapt2ServiceKey.set(aapt2ServiceKey)
            it.request.set(config)
            it.errorFormatMode.set(aapt2.getErrorFormatMode())
        }
    }

    class CreationAction(creationConfig: ComponentCreationConfig) :
        VariantTaskCreationAction<ProcessAndroidAppResourcesTask, ComponentCreationConfig>(
            creationConfig
        ), AndroidResourcesTaskCreationAction by AndroidResourcesTaskCreationActionImpl(
            creationConfig
        ) {

        override val name: String
            get() = computeTaskName("process", "NamespacedResources")
        override val type: Class<ProcessAndroidAppResourcesTask>
            get() = ProcessAndroidAppResourcesTask::class.java

        override fun handleProvider(
            taskProvider: TaskProvider<ProcessAndroidAppResourcesTask>
        ) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                ProcessAndroidAppResourcesTask::rClassSource
            ).withName("out").on(InternalArtifactType.RUNTIME_R_CLASS_SOURCES)
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                ProcessAndroidAppResourcesTask::resourceApUnderscoreDirectory
            ).withName("out").on(InternalArtifactType.PROCESSED_RES)
        }

        override fun configure(
            task: ProcessAndroidAppResourcesTask
        ) {
            super.configure(task)

            val artifacts = creationConfig.artifacts
            task.aaptFriendlyManifestFileDirectory =
                artifacts.get(InternalArtifactType.AAPT_FRIENDLY_MERGED_MANIFESTS)

            task.manifestFileDirectory =
                artifacts.get(creationConfig.global.manifestArtifactType)

            creationConfig.artifacts.setTaskInputToFinalProduct(
                InternalArtifactType.RES_STATIC_LIBRARY,
                task.thisSubProjectStaticLibrary
            )
            task.libraryDependencies =
                    creationConfig.variantDependencies.getArtifactFileCollection(
                            AndroidArtifacts.ConsumedConfigType.RUNTIME_CLASSPATH,
                            AndroidArtifacts.ArtifactScope.ALL,
                            AndroidArtifacts.ArtifactType.RES_STATIC_LIBRARY)
            task.sharedLibraryDependencies =
                    creationConfig.variantDependencies.getArtifactFileCollection(
                            AndroidArtifacts.ConsumedConfigType.COMPILE_CLASSPATH,
                            AndroidArtifacts.ArtifactScope.ALL,
                            AndroidArtifacts.ArtifactType.RES_SHARED_STATIC_LIBRARY)

            task.aaptIntermediateDir =
                    FileUtils.join(
                            creationConfig.services.projectInfo.getIntermediatesDir(), "res-process-intermediate", creationConfig.dirName)

            task.androidJarInput.initialize(creationConfig)
            if (creationConfig is ApkCreationConfig) {
                task.noCompress.set(androidResourcesCreationConfig.androidResources.noCompress)
            }
            task.noCompress.disallowChanges()
            creationConfig.services.initializeAapt2Input(task.aapt2)

        }
    }

}
