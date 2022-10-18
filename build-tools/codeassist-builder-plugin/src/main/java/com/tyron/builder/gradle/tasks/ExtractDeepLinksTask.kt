package com.tyron.builder.gradle.tasks

import com.android.SdkConstants.FD_RES_NAVIGATION
import com.android.ide.common.blame.SourceFilePosition
import com.android.manifmerger.NavigationXmlDocumentData
import com.android.manifmerger.NavigationXmlLoader
import com.android.utils.FileUtils
import com.google.gson.GsonBuilder
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.BuildAnalyzer
import com.tyron.builder.gradle.internal.tasks.NonIncrementalTask
import com.tyron.builder.gradle.internal.tasks.TaskCategory
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.internal.utils.setDisallowChanges
import com.tyron.builder.plugin.SdkConstants.FN_NAVIGATION_JSON
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

private val DOT_XML_EXT = Regex("\\.xml$")

/**
 * A task that parses the navigation xml files and produces a single navigation.json file with the
 * deep link data needed for any downstream app manifest merging.
 */
@CacheableTask
@BuildAnalyzer(primaryTaskCategory = TaskCategory.ANDROID_RESOURCES)
abstract class ExtractDeepLinksTask: NonIncrementalTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val navFilesFolders: ListProperty<Directory>

    @get:Optional
    @get:Input
    abstract val manifestPlaceholders: MapProperty<String, String>

    /**
     * If [forAar] is true, (1) use [SourceFilePosition.UNKNOWN] to avoid leaking source file
     * locations into the AAR, and (2) don't write an output navigation.json when there are no
     * navigation xml inputs because we don't want to package an empty navigation.json in the AAR.
     */
    @get:Optional
    @get:Input
    abstract val forAar: Property<Boolean>

    @get:OutputFile
    abstract val navigationJson: RegularFileProperty

    override fun doTaskAction() {
        val navigationIds = mutableSetOf<String>()
        val navDatas = mutableListOf<NavigationXmlDocumentData>()
        navFilesFolders.get().forEach { directory ->
            val folder = directory.asFile
            if (folder.exists()) {
                folder.listFiles()?.map { navigationFile ->
                    val navigationId = navigationFile.name.replace(DOT_XML_EXT, "")
                    if (navigationIds.add(navigationId)) {
                        navigationFile.inputStream().use { inputStream ->
                            navDatas.add(
                                NavigationXmlLoader
                                    .load(navigationId, navigationFile, inputStream)
                                    .convertToData(manifestPlaceholders.get().toMap(), forAar.get())
                            )
                        }
                    }
                }
            }
        }
        if (!forAar.get() || navDatas.isNotEmpty()) {
            FileUtils.writeToFile(
                navigationJson.asFile.get(),
                GsonBuilder().setPrettyPrinting().create().toJson(navDatas)
            )
        }
    }

    class CreationAction(
        creationConfig: ComponentCreationConfig
    ) : BaseCreationAction(creationConfig) {
        override val forAar = false
        override val internalArtifactType = InternalArtifactType.NAVIGATION_JSON
        override val name: String
            get() = computeTaskName("extractDeepLinks")
    }

    class AarCreationAction(
        creationConfig: ComponentCreationConfig
    ) : BaseCreationAction(creationConfig) {
        override val forAar = true
        override val internalArtifactType = InternalArtifactType.NAVIGATION_JSON_FOR_AAR
        override val name: String
            get() = computeTaskName("extractDeepLinksForAar")
    }

    abstract class BaseCreationAction(
        creationConfig: ComponentCreationConfig
    ) : VariantTaskCreationAction<ExtractDeepLinksTask, ComponentCreationConfig>(
        creationConfig
    ) {

        abstract val forAar: Boolean
        abstract val internalArtifactType: InternalArtifactType<RegularFile>

        override val type: Class<ExtractDeepLinksTask>
            get() = ExtractDeepLinksTask::class.java

        override fun handleProvider(
            taskProvider: TaskProvider<ExtractDeepLinksTask>
        ) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                ExtractDeepLinksTask::navigationJson
            ).withName(FN_NAVIGATION_JSON).on(internalArtifactType)
        }

        override fun configure(
            task: ExtractDeepLinksTask
        ) {
            super.configure(task)
            task.navFilesFolders.set(
                creationConfig.sources.res.all.map {
                    it.flatten()
                }.map { directories ->
                    directories.map { directory ->
                        directory.dir(FD_RES_NAVIGATION)
                    }
                }.orElse(emptyList())
            )
            task.manifestPlaceholders.setDisallowChanges(
                creationConfig.manifestPlaceholdersCreationConfig?.placeholders,
                handleNullable = {
                    empty()
                }
            )
            task.forAar.set(forAar)
        }
    }
}
