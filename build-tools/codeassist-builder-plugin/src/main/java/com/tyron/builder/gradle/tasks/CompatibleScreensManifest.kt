package com.tyron.builder.gradle.tasks

import com.android.SdkConstants
import com.android.resources.Density
import com.android.utils.FileUtils
import com.google.common.base.Charsets
import com.google.common.io.Files
import com.tyron.builder.api.variant.FilterConfiguration
import com.tyron.builder.api.variant.impl.*
import com.tyron.builder.gradle.internal.component.ApkCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType.COMPATIBLE_SCREEN_MANIFEST
import com.tyron.builder.gradle.internal.tasks.BuildAnalyzer
import com.tyron.builder.gradle.internal.tasks.NonIncrementalTask
import com.tyron.builder.gradle.internal.tasks.TaskCategory
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.internal.utils.setDisallowChanges
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.tooling.BuildException
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.io.IOException

/**
 * Task to generate a manifest snippet that just contains a compatible-screens node with the given
 * density and the given list of screen sizes.
 *
 * Caching disabled by default for this task because the task does very little work.
 * Input files are written to a minimal XML file and no computation is required.
 * Calculating cache hit/miss and fetching results is likely more expensive than
 * simply executing the task.
 */
@DisableCachingByDefault
@BuildAnalyzer(primaryTaskCategory = TaskCategory.MANIFEST, secondaryTaskCategories = [TaskCategory.SOURCE_GENERATION])
abstract class CompatibleScreensManifest : NonIncrementalTask() {

    @get:Input
    abstract val applicationId: Property<String>

    @get:Input
    abstract val componentType: Property<String>

    @get:Input
    lateinit var screenSizes: Set<String>
        internal set

    @get:OutputDirectory
    abstract val outputFolder: DirectoryProperty

    @get:Nested
    abstract val variantOutputs : ListProperty<VariantOutputImpl>

    @get:Input
    @get:Optional
    abstract val minSdkVersion: Property<String?>

    override fun doTaskAction() {

        BuiltArtifactsImpl(
            artifactType = COMPATIBLE_SCREEN_MANIFEST,
            applicationId = applicationId.get(),
            variantName = variantName,
            elements = variantOutputs.get().mapNotNull {
                val generatedManifest = generate(it)
                if (generatedManifest != null)
                    BuiltArtifactImpl.make(
                        outputFile = generatedManifest.absolutePath,
                        versionCode = it.versionCode.orNull,
                        versionName = it.versionName.orNull,
                        variantOutputConfiguration = it.variantOutputConfiguration
                    )
                else
                    null
            }
        ).save(outputFolder.get())
    }

    private fun generate(variantOutput: VariantOutputImpl): File? {
        val densityFilter = variantOutput.variantOutputConfiguration.getFilter(
            FilterConfiguration.FilterType.DENSITY) ?: return null

        val content = StringBuilder()
        content.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            .append("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n")
            .append("\n")
        if (minSdkVersion.isPresent) {
            content.append("    <uses-sdk android:minSdkVersion=\"")
                .append(minSdkVersion.get())
                .append("\"/>\n")
        }
        content.append("    <compatible-screens>\n")

        // convert unsupported values to numbers.
        val density = convert(densityFilter.identifier, Density.XXHIGH, Density.XXXHIGH)

        for (size in screenSizes) {
            content.append("        <screen android:screenSize=\"")
                .append(size)
                .append("\" " + "android:screenDensity=\"")
                .append(density).append("\" />\n")
        }

        content.append(
                "    </compatible-screens>\n" + "</manifest>"
        )

        val splitFolder = File(outputFolder.get().asFile,
            variantOutput.variantOutputConfiguration.dirName())
        FileUtils.mkdirs(splitFolder)
        val manifestFile = File(splitFolder, SdkConstants.ANDROID_MANIFEST_XML)

        try {
            Files.asCharSink(manifestFile, Charsets.UTF_8).write(content.toString())
        } catch (e: IOException) {
            throw BuildException(e.message, e)
        }

        return manifestFile
    }

    private fun convert(density: String, vararg densitiesToConvert: Density): String {
        for (densityToConvert in densitiesToConvert) {
            if (densityToConvert.resourceValue == density) {
                return densityToConvert.dpiValue.toString()
            }
        }
        return density
    }

    class CreationAction(creationConfig: ApkCreationConfig, private val screenSizes: Set<String>) :
        VariantTaskCreationAction<CompatibleScreensManifest, ApkCreationConfig>(
            creationConfig
        ) {

        override val name: String
            get() = computeTaskName("create", "CompatibleScreenManifests")
        override val type: Class<CompatibleScreensManifest>
            get() = CompatibleScreensManifest::class.java

        override fun handleProvider(
            taskProvider: TaskProvider<CompatibleScreensManifest>
        ) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                CompatibleScreensManifest::outputFolder
            ).on(COMPATIBLE_SCREEN_MANIFEST)
        }

        override fun configure(
            task: CompatibleScreensManifest
        ) {
            super.configure(task)

            task.screenSizes = screenSizes
            task.applicationId.setDisallowChanges(creationConfig.applicationId)

            task.componentType.set(creationConfig.componentType.toString())
            task.componentType.disallowChanges()

            creationConfig.outputs.getEnabledVariantOutputs().forEach(task.variantOutputs::add)
            task.variantOutputs.disallowChanges()

            task.minSdkVersion.set(task.project.provider { creationConfig.minSdkVersion.getApiString() })
            task.minSdkVersion.disallowChanges()
        }
    }
}
