
package com.tyron.builder.gradle.internal.tasks.featuresplit

import com.android.utils.FileUtils
import com.google.common.base.Joiner
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.ide.dependencies.getIdString
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.ARTIFACT_TYPE
import com.tyron.builder.gradle.internal.publishing.PublishedConfigSpec
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.BuildAnalyzer
import com.tyron.builder.gradle.internal.tasks.NonIncrementalTask
import com.tyron.builder.gradle.internal.tasks.TaskCategory
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.internal.utils.setDisallowChanges
import org.gradle.api.Action
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*

private val aarOrJarType = Action { container: AttributeContainer ->
    container.attribute(ARTIFACT_TYPE, AndroidArtifacts.ArtifactType.AAR_OR_JAR.type)
}

/** Task to write the list of transitive dependencies.  */
@CacheableTask
@BuildAnalyzer(primaryTaskCategory = TaskCategory.METADATA)
abstract class PackagedDependenciesWriterTask : NonIncrementalTask() {

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    private lateinit var runtimeAarOrJarDeps: ArtifactCollection

    @get:Input
    val content: List<String>
       get() = runtimeAarOrJarDeps.map { it.toIdString() }.sorted()

    // the list of packaged dependencies by transitive dependencies.
    private lateinit var transitivePackagedDeps : ArtifactCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val transitivePackagedDepsFC : FileCollection
        get() = transitivePackagedDeps.artifactFiles

    @get:Input
    abstract val currentProjectIds: ListProperty<String>

    override fun doTaskAction() {
        val apkFilters = mutableSetOf<String>()
        val contentFilters = mutableSetOf<String>()
        // load the transitive information and remove from the full content.
        // We know this is correct because this information is also used in
        // FilteredArtifactCollection to remove this content from runtime-based ArtifactCollection.
        // However since we directly use the Configuration here, we have to manually remove it.
        for (transitiveDep in transitivePackagedDeps) {
            // register the APK that generated this list to remove it from our list
            apkFilters.add(transitiveDep.toIdString())
            // read its packaged content to also remove it.
            val lines = transitiveDep.file.readLines()
            contentFilters.addAll(lines)
        }

        val contentWithProject = content + currentProjectIds.get()

        // compute the overall content
        val filteredContent =
            contentWithProject.filter {
                !apkFilters.contains(it) && !contentFilters.contains(it)
            }.sorted()

        val asFile = outputFile.get().asFile
        FileUtils.mkdirs(asFile.parentFile)
        asFile.writeText(Joiner.on(System.lineSeparator()).join(filteredContent))
    }

    /**
     * Action to create the task that generates the transitive dependency list to be consumed by
     * other modules.
     *
     * This cannot depend on preBuild as it would introduce a dependency cycle.
     */
    class CreationAction(creationConfig: ComponentCreationConfig) :
        VariantTaskCreationAction<PackagedDependenciesWriterTask, ComponentCreationConfig>(
            creationConfig,
            dependsOnPreBuildTask = false
        ) {

        override val name: String
            get() = computeTaskName("generate", "FeatureTransitiveDeps")
        override val type: Class<PackagedDependenciesWriterTask>
            get() = PackagedDependenciesWriterTask::class.java

        override fun handleProvider(
            taskProvider: TaskProvider<PackagedDependenciesWriterTask>
        ) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                PackagedDependenciesWriterTask::outputFile
            ).withName("deps.txt").on(InternalArtifactType.PACKAGED_DEPENDENCIES)
        }

        override fun configure(
            task: PackagedDependenciesWriterTask
        ) {
            super.configure(task)
            val apiAndRuntimeConfigurations = listOfNotNull(
                creationConfig.variantDependencies.getElements(
                    PublishedConfigSpec(
                        AndroidArtifacts.PublishedConfigType.API_ELEMENTS
                    )
                ),
                creationConfig.variantDependencies.getElements(
                    PublishedConfigSpec(
                        AndroidArtifacts.PublishedConfigType.RUNTIME_ELEMENTS
                    )
                )
            )
            task.currentProjectIds.setDisallowChanges(
                apiAndRuntimeConfigurations.let { configurations ->

                    val capabilitiesList = configurations.map { it.outgoing.capabilities }.filter {
                        it.isNotEmpty()
                    }.ifEmpty {
                        listOf(listOf(creationConfig.services.projectInfo.defaultProjectCapability))
                    }

                    val projectId = "${creationConfig.services.projectInfo.path}::${task.variantName}"
                    capabilitiesList.map { capabilities ->
                        encodeCapabilitiesInId(projectId) {
                            capabilities.joinToString(";") { it.toString() }
                        }
                    }.distinct()
                }
            )
            task.runtimeAarOrJarDeps =
                creationConfig.variantDependencies
                    .runtimeClasspath
                    .incoming
                    .artifactView { it.attributes(aarOrJarType) }
                    .artifacts
            task.dependsOn(task.runtimeAarOrJarDeps.artifactFiles)

            task.transitivePackagedDeps =
                creationConfig.variantDependencies.getArtifactCollection(
                    AndroidArtifacts.ConsumedConfigType.PROVIDED_CLASSPATH,
                    AndroidArtifacts.ArtifactScope.PROJECT,
                    AndroidArtifacts.ArtifactType.PACKAGED_DEPENDENCIES)
        }
    }
}

private fun ComponentIdentifier.toIdString(
    variantProvider: () -> String?,
    capabilitiesProvider: () -> String
) : String {
    val id = when (this) {
        is ProjectComponentIdentifier -> {
            val variant = variantProvider()
            if (variant == null) {
                getIdString()
            } else {
                "${getIdString()}::${variant}"
            }
        }
        is ModuleComponentIdentifier -> "$group:$module"
        else -> toString()
    }

    return encodeCapabilitiesInId(id, capabilitiesProvider)
}

private fun encodeCapabilitiesInId(
    id: String,
    capabilitiesProvider: () -> String
): String {
    return "$id;${capabilitiesProvider.invoke()}"
}

fun removeVariantNameFromId(
    id: String
): String {
    return if (id.contains("::")) {
        val libraryWithoutVariant = id.substringBeforeLast("::")
        val capabilities = id.substringAfter(";")
        "$libraryWithoutVariant;$capabilities"
    } else {
        id
    }
}
