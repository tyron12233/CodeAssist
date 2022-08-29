package org.gradle.configurationcache.initialization

import org.gradle.StartParameter
import org.gradle.api.internal.StartParameterInternal
import org.gradle.configurationcache.extensions.unsafeLazy
import org.gradle.initialization.layout.BuildLayout
import org.gradle.internal.service.scopes.Scopes
import org.gradle.internal.service.scopes.ServiceScope
import java.io.File


@ServiceScope(Scopes.BuildTree::class)
class ConfigurationCacheStartParameter(
    private val buildLayout: BuildLayout,
    startParameter: StartParameter
) {

    private
    val startParameter = startParameter as StartParameterInternal

    val gradleProperties: Map<String, Any?>
        get() = startParameter.projectProperties

    val isQuiet: Boolean
        get() = startParameter.isConfigurationCacheQuiet

    val maxProblems: Int
        get() = startParameter.configurationCacheMaxProblems

    val failOnProblems: Boolean
        get() = TODO() //startParameter.configurationCacheProblems == ConfigurationCacheProblemsOption.Value.FAIL

    val recreateCache: Boolean
        get() = startParameter.isConfigurationCacheRecreateCache

    /**
     * See [StartParameter.getProjectDir].
     */
    val projectDirectory: File?
        get() = startParameter.projectDir

    val currentDirectory: File
        get() = startParameter.currentDir

    val settingsDirectory: File
        get() = buildLayout.settingsDir

    val isOffline get() = startParameter.isOffline

    @Suppress("DEPRECATION")
    val settingsFile: File?
        get() = startParameter.settingsFile

    val rootDirectory: File
        get() = buildLayout.rootDirectory

    val isRefreshDependencies
        get() = startParameter.isRefreshDependencies

    val isWriteDependencyLocks
        get() = startParameter.isWriteDependencyLocks && !isUpdateDependencyLocks

    val isUpdateDependencyLocks
        get() = startParameter.lockedDependenciesToUpdate.isNotEmpty()

    val requestedTaskNames: List<String> by unsafeLazy {
        startParameter.taskNames
    }

    val excludedTaskNames: Set<String>
        get() = startParameter.excludedTaskNames

    val allInitScripts: List<File>
        get() = startParameter.allInitScripts

    val gradleUserHomeDir: File
        get() = startParameter.gradleUserHomeDir

    val includedBuilds: List<File>
        get() = startParameter.includedBuilds
}
