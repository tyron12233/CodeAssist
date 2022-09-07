package com.tyron.builder.gradle.internal.tasks

import com.tyron.builder.gradle.internal.TaskManager
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.ArtifactScope.ALL
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.ArtifactType.MANIFEST
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.ConsumedConfigType.COMPILE_CLASSPATH
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.ConsumedConfigType.RUNTIME_CLASSPATH
import com.tyron.builder.gradle.internal.ide.dependencies.getIdString
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.internal.component.local.model.OpaqueComponentArtifactIdentifier
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Pre build task that does some checks for application variants
 *
 * Caching disabled by default for this task because the task does very little work.
 * The task performs no disk I/O and has no real Output.
 * Calculating cache hit/miss and fetching results is likely more expensive than
 * simply executing the task.
 */
@DisableCachingByDefault
abstract class AppPreBuildTask : NonIncrementalTask() {

    // list of Android only compile and runtime classpath.
    private lateinit var compileManifests: ArtifactCollection
    private lateinit var runtimeManifests: ArtifactCollection

    @get:OutputDirectory
    lateinit var fakeOutputDirectory: File
        private set

    @get:Input
    val compileDependencies: Set<String> by lazy {
        getAndroidDependencies(compileManifests)
    }

    @get:Input
    val runtimeDependencies: Set<String> by lazy {
        getAndroidDependencies(runtimeManifests)
    }

    override fun doTaskAction() {
        val compileDeps = compileDependencies.toMutableSet()
        compileDeps.removeAll(runtimeDependencies)

        if (compileDeps.isNotEmpty()) {
            val formattedDependencies = compileDeps.joinToString(
                prefix = "-> ",
                separator = "\n-> ",
                limit = 5,
                truncated = "... (Total: ${compileDeps.size})"
            )
            throw RuntimeException(
                "The following Android dependencies are set to compileOnly which is not supported:\n$formattedDependencies"
            )
        }
    }

    private class EmptyCreationAction(creationConfig: ComponentCreationConfig) :
        TaskManager.AbstractPreBuildCreationAction<AndroidVariantTask, ComponentCreationConfig>(creationConfig) {

        override val type: Class<AndroidVariantTask>
            get() = AndroidVariantTask::class.java
    }

    private class CheckCreationAction(creationConfig: ComponentCreationConfig) :
        TaskManager.AbstractPreBuildCreationAction<AppPreBuildTask, ComponentCreationConfig>(creationConfig) {

        override val type: Class<AppPreBuildTask>
            get() = AppPreBuildTask::class.java

        override fun configure(
            task: AppPreBuildTask
        ) {
            super.configure(task)

            task.compileManifests =
                creationConfig.variantDependencies.getArtifactCollection(COMPILE_CLASSPATH, ALL, MANIFEST)
            task.runtimeManifests =
                creationConfig.variantDependencies.getArtifactCollection(RUNTIME_CLASSPATH, ALL, MANIFEST)

            task.fakeOutputDirectory = File(
                creationConfig.services.projectInfo.getIntermediatesDir(),
                "prebuild/${creationConfig.dirName}"
            )
        }
    }

    companion object {
        @JvmStatic
        fun getCreationAction(
            creationConfig: ComponentCreationConfig
        ): TaskManager.AbstractPreBuildCreationAction<*, *> {
            return if (creationConfig.componentType.isBaseModule && creationConfig.global.hasDynamicFeatures) {
                CheckCreationAction(creationConfig)
            } else EmptyCreationAction(creationConfig)

        }
    }
}

private fun getAndroidDependencies(artifactView: ArtifactCollection): Set<String> {
    return artifactView.artifacts.asSequence().mapNotNull { it.toIdString() }.toSortedSet()
}

private fun ResolvedArtifactResult.toIdString(): String? {
    return when (val id = id.componentIdentifier) {
        is ProjectComponentIdentifier -> id.getIdString()
        is ModuleComponentIdentifier -> id.toString()
        is OpaqueComponentArtifactIdentifier -> {
            // skip those for now.
            // These are file-based dependencies and it's unlikely to be an AAR.
            null
        }
        else -> null
    }
}
