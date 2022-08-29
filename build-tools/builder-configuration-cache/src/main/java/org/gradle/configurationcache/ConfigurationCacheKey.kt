package org.gradle.configurationcache

import com.google.common.hash.Hasher
import com.google.common.hash.Hashing
import org.gradle.configurationcache.extensions.unsafeLazy
import org.gradle.configurationcache.initialization.ConfigurationCacheStartParameter
import org.gradle.internal.buildtree.BuildActionModelRequirements
import org.gradle.internal.hash.Hashes
import org.gradle.internal.service.scopes.Scopes
import org.gradle.internal.service.scopes.ServiceScope
import org.gradle.util.GradleVersion
import org.gradle.util.internal.GFileUtils.relativePathOf
import java.io.File
import java.nio.charset.StandardCharsets


@ServiceScope(Scopes.BuildTree::class)
class ConfigurationCacheKey(
    private val startParameter: ConfigurationCacheStartParameter,
    private val buildActionRequirements: BuildActionModelRequirements
) {

    val string: String by unsafeLazy {
        Hashes.toCompactString(
            Hashing.md5().newHasher().apply {
                putCacheKeyComponents()
            }.hash()
        )
    }

    override fun toString() = string

    override fun hashCode(): Int = string.hashCode()

    override fun equals(other: Any?): Boolean = (other as? ConfigurationCacheKey)?.string == string

    private fun Hasher.putCacheKeyComponents() {
        putString(GradleVersion.current().version, StandardCharsets.UTF_8)

        putString(
            startParameter.settingsFile?.let {
                relativePathOf(it, startParameter.rootDirectory)
            } ?: "",
            StandardCharsets.UTF_8
        )

        putAll(
            startParameter.includedBuilds.map {
                relativePathOf(it, startParameter.rootDirectory)
            }
        )

        buildActionRequirements.appendKeyTo(this)

        // TODO:bamboo review with Adam
//        require(buildActionRequirements.isRunsTasks || startParameter.requestedTaskNames.isEmpty())
        if (buildActionRequirements.isRunsTasks) {
            appendRequestedTasks()
        }

        putBoolean(startParameter.isOffline)
    }

    private
    fun Hasher.appendRequestedTasks() {
        val requestedTaskNames = startParameter.requestedTaskNames
        putAll(requestedTaskNames)

        val excludedTaskNames = startParameter.excludedTaskNames
        putAll(excludedTaskNames)

        val taskNames = requestedTaskNames.asSequence() + excludedTaskNames.asSequence()
        val hasRelativeTaskName = taskNames.any { !it.startsWith(':') }
        if (hasRelativeTaskName) {
            // Because unqualified task names are resolved relative to the selected
            // sub-project according to either `projectDirectory` or `currentDirectory`,
            // the relative directory information must be part of the key.
            val projectDir = startParameter.projectDirectory
            if (projectDir != null) {
                relativePathOf(
                    projectDir,
                    startParameter.rootDirectory
                ).let { relativeProjectDir ->
                    putString(relativeProjectDir, StandardCharsets.UTF_8)
                }
            } else {
                relativeChildPathOrNull(
                    startParameter.currentDirectory,
                    startParameter.rootDirectory
                )?.let { relativeSubDir ->
                    putString(relativeSubDir, StandardCharsets.UTF_8)
                }
            }
        }
    }

    private
    fun Hasher.putAll(list: Collection<String>) {
        putInt(list.size)
        list.forEach { putString(it, StandardCharsets.UTF_8)}
    }

    /**
     * Returns the path of [target] relative to [base] if
     * [target] is a child of [base] or `null` otherwise.
     */
    private
    fun relativeChildPathOrNull(target: File, base: File): String? =
        relativePathOf(target, base)
            .takeIf { !it.startsWith('.') }
}