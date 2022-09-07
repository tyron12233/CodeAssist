package com.tyron.builder.plugin.tasks

import com.tyron.builder.api.artifact.Artifact
import com.tyron.builder.api.artifact.ArtifactTransformationRequest
import com.tyron.builder.api.artifact.SingleArtifact
import com.tyron.builder.api.variant.impl.BuiltArtifactsImpl
import com.tyron.builder.gradle.internal.component.ApkCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.scope.InternalArtifactType.APK_IDE_MODEL
import com.tyron.builder.gradle.internal.tasks.BuildAnalyzer
import com.tyron.builder.gradle.internal.tasks.TaskCategory
import com.tyron.builder.gradle.options.BooleanOption
import com.tyron.builder.gradle.tasks.PackageAndroidArtifact
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskProvider
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Task to package an Android application (APK).  */
@DisableCachingByDefault
@BuildAnalyzer(primaryTaskCategory = TaskCategory.APK_PACKAGING)
abstract class PackageApplication : PackageAndroidArtifact() {
    private lateinit var transformationRequest: ArtifactTransformationRequest<PackageApplication>

    @Internal
    override fun getTransformationRequest(): ArtifactTransformationRequest<PackageApplication> {
        return transformationRequest
    }
    // ----- CreationAction -----
    /**
     * Configures the task to perform the "standard" packaging, including all files that should end
     * up in the APK.
     */
    class CreationAction(
        creationConfig: ApkCreationConfig,
        private val outputDirectory: File,
        manifests: Provider<Directory>,
        manifestType: Artifact<Directory>
    ) : PackageAndroidArtifact.CreationAction<PackageApplication>(
        creationConfig,
        manifests,
        manifestType
    ) {
        private var transformationRequest: ArtifactTransformationRequest<PackageApplication>? = null
        private var task: PackageApplication? = null
        override val name: String
            get() = computeTaskName("package")

        override val type: Class<PackageApplication>
            get() = PackageApplication::class.java

        override fun handleProvider(
            taskProvider: TaskProvider<PackageApplication>
        ) {
            super.handleProvider(taskProvider)
            creationConfig.taskContainer.packageAndroidTask = taskProvider
            val useOptimizedResources = !creationConfig.debuggable &&
                    !creationConfig.componentType.isForTesting &&
                    creationConfig.services.projectOptions[BooleanOption.ENABLE_RESOURCE_OPTIMIZATIONS]
            val useResourcesShrinker = creationConfig
                .androidResourcesCreationConfig
                ?.useResourceShrinker == true
            val operationRequest = creationConfig.artifacts.use(taskProvider)
                .wiredWithDirectories(
                    PackageAndroidArtifact::getResourceFiles,
                    PackageApplication::getOutputDirectory)

            transformationRequest = when {
                useOptimizedResources -> operationRequest.toTransformMany(
                    InternalArtifactType.OPTIMIZED_PROCESSED_RES,
                    SingleArtifact.APK,
                    outputDirectory.absolutePath)
                useResourcesShrinker -> operationRequest.toTransformMany(
                    InternalArtifactType.SHRUNK_PROCESSED_RES,
                    SingleArtifact.APK,
                    outputDirectory.absolutePath)
                else -> operationRequest.toTransformMany(
                    InternalArtifactType.PROCESSED_RES,
                    SingleArtifact.APK,
                    outputDirectory.absolutePath)
            }

            // in case configure is called before handleProvider, we need to save the request.
            transformationRequest?.let {
                task?.let { t -> t.transformationRequest = it }
            }
            creationConfig
                .artifacts
                .setInitialProvider(taskProvider, PackageApplication::getIdeModelOutputFile)
                .atLocation(outputDirectory)
                .withName(BuiltArtifactsImpl.METADATA_FILE_NAME)
                .on(APK_IDE_MODEL)
        }

        override fun finalConfigure(task: PackageApplication) {
            super.finalConfigure(task)
            this.task = task
            transformationRequest?.let {
                task.transformationRequest = it
            }
        }

    }

    companion object {
        @JvmStatic
        fun recordMetrics(
            projectPath: String?,
            apkOutputFile: File?,
            resourcesApFile: File?,
            analyticsService: Any
        ) {
            val metricsStartTime = System.nanoTime()
//            val metrics = GradleBuildProjectMetrics.newBuilder()
            val apkSize = getSize(apkOutputFile)
//            if (apkSize != null) {
//                metrics.apkSize = apkSize
//            }
//            val resourcesApSize =
//                getSize(resourcesApFile)
//            if (resourcesApSize != null) {
//                metrics.resourcesApSize = resourcesApSize
//            }
//            metrics.metricsTimeNs = System.nanoTime() - metricsStartTime
//            analyticsService.getProjectBuillder(projectPath!!)?.setMetrics(metrics)
        }

        private fun getSize(file: File?): Long? {
            return if (file == null) {
                null
            } else try {
                Files.size(file.toPath())
            } catch (e: IOException) {
                null
            }
        }
    }
}
