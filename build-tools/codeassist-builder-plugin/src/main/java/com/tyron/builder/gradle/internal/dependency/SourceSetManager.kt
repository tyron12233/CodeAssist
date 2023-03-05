package com.tyron.builder.gradle.internal.dependency

import com.tyron.builder.errors.IssueReporter
import com.tyron.builder.gradle.api.AndroidSourceSet
import com.tyron.builder.gradle.internal.dsl.AndroidSourceSetFactory
import com.tyron.builder.gradle.internal.scope.DelayedActionsExecutor
import com.tyron.builder.gradle.internal.services.DslServices
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

class SourceSetManager(
    project: Project,
    private val publishPackage: Boolean,
    private val dslServices : DslServices,
    private val buildArtifactActions: DelayedActionsExecutor) {
    val sourceSetsContainer: NamedDomainObjectContainer<AndroidSourceSet> = project.container(
        AndroidSourceSet::class.java,
        AndroidSourceSetFactory(project, publishPackage, dslServices))
    private val configurations: ConfigurationContainer = project.configurations
    private val logger: Logger = Logging.getLogger(this.javaClass)

    private val configuredSourceSets = mutableSetOf<String>()

    fun setUpTestSourceSet(name: String): AndroidSourceSet {
        return setUpSourceSet(name, true)
    }

    @JvmOverloads
    fun setUpSourceSet(name: String, isTestComponent: Boolean = false): AndroidSourceSet {
        val sourceSet = sourceSetsContainer.maybeCreate(name)
        if (!configuredSourceSets.contains(name)) {
            createConfigurationsForSourceSet(sourceSet, isTestComponent)
            configuredSourceSets.add(name)
        }
        return sourceSet
    }

    private fun createConfigurationsForSourceSet(
        sourceSet: AndroidSourceSet, isTestComponent: Boolean) {
        val apiName = sourceSet.apiConfigurationName
        val implementationName = sourceSet.implementationConfigurationName
        val runtimeOnlyName = sourceSet.runtimeOnlyConfigurationName
        val compileOnlyName = sourceSet.compileOnlyConfigurationName

        val api = if (!isTestComponent) {
            createConfiguration(apiName, getConfigDesc("API", sourceSet.name))
        } else {
            null
        }

        val implementation = createConfiguration(
            implementationName,
            getConfigDesc("Implementation only", sourceSet.name))
        api?.let {
            implementation.extendsFrom(it)
        }

        createConfiguration(runtimeOnlyName, getConfigDesc("Runtime only", sourceSet.name))
        createConfiguration(compileOnlyName, getConfigDesc("Compile only", sourceSet.name))

        // then the secondary configurations.
        createConfiguration(
            sourceSet.wearAppConfigurationName,
            "Link to a wear app to embed for object '" + sourceSet.name + "'.")

        createConfiguration(
            sourceSet.annotationProcessorConfigurationName,
            "Classpath for the annotation processor for '" + sourceSet.name + "'.")
    }

    /**
     * Creates a Configuration for a given source set.
     *
     * @param name the name of the configuration to create.
     * @param description the configuration description.
     * @param canBeResolved Whether the configuration can be resolved directly.
     * @return the configuration
     * @see Configuration.isCanBeResolved
     */
    private fun createConfiguration(
        name: String, description: String, canBeResolved: Boolean = false): Configuration {
        logger.debug("Creating configuration {}", name)

        val configuration = configurations.maybeCreate(name)

        configuration.isVisible = false
        configuration.description = description
        configuration.isCanBeConsumed = false
        configuration.isCanBeResolved = canBeResolved

        return configuration
    }

    private fun getConfigDesc(name: String, sourceSetName: String): String {
        return "$name dependencies for '$sourceSetName' sources."
    }

    // Check that all sourceSets in the container have been set up with configurations.
    // This will alert users who accidentally mistype the name of a sourceSet in their buildscript
    fun checkForUnconfiguredSourceSets() {
        sourceSetsContainer.forEach { sourceSet ->
            if (!configuredSourceSets.contains(sourceSet.name)) {
                val message = ("The SourceSet '${sourceSet.name}' is not recognized " +
                        "by the Android Gradle Plugin. Perhaps you misspelled something?")
                dslServices.issueReporter.reportError(IssueReporter.Type.GENERIC, message)
            }
        }
    }

    fun executeAction(action: Action<NamedDomainObjectContainer<out AndroidSourceSet>>) {
        action.execute(sourceSetsContainer)
    }

    fun executeAction(action: NamedDomainObjectContainer<out AndroidSourceSet>.() -> Unit) {
        action.invoke(sourceSetsContainer)
    }

    fun runBuildableArtifactsActions() {
        buildArtifactActions.runAll()
    }
}
