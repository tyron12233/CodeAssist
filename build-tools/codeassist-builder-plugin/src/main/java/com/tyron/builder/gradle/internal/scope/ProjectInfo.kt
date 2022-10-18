package com.tyron.builder.gradle.internal.scope

import com.google.common.base.Preconditions
import com.tyron.builder.core.BuilderConstants
import com.tyron.builder.internal.ide.dependencies.BuildMapping
import com.tyron.builder.internal.ide.dependencies.computeBuildMapping
import com.android.SdkConstants
import org.gradle.api.Project
import org.gradle.api.capabilities.Capability
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.plugins.BasePluginConvention
import org.gradle.api.provider.Provider
import org.gradle.api.resources.TextResource
import org.gradle.internal.component.external.model.ImmutableCapability
import java.io.File

/**
 * A class that provides data about the Gradle project object without exposing the Project itself
 *
 * FIXME remove getProject() and old File-based APIs.
 */
class ProjectInfo(private val project: Project) {

    companion object {
        @JvmStatic
        fun Project.getBaseName(): String {
            val convention = this.convention.findPlugin(BasePluginConvention::class.java)?.let {
                Preconditions.checkNotNull(
                    it
                )
            }
            return convention!!.archivesBaseName
        }
    }

    fun getProjectBaseName(): String = project.getBaseName()

    val path: String
        get() = project.path

    val name: String
        get() = project.name

    val group: String
        get() = project.group.toString()

    val version: String
        get() = project.version.toString()

    val defaultProjectCapability: Capability
        get() = ImmutableCapability(project.group.toString(), project.name, "unspecified")

    val projectDirectory: Directory
        get() = project.layout.projectDirectory

    val buildFile: File
        get() = project.buildFile

    val buildDirectory: DirectoryProperty
        get() = project.layout.buildDirectory

    val rootDir: File
        get() = project.rootDir

    @Deprecated("Use rootBuildDirectory")
    val rootBuildDir: File
        get() = project.rootProject.buildDir

    val rootBuildDirectory: DirectoryProperty
        get() = project.rootProject.layout.buildDirectory

    val gradleUserHomeDir: File
            get() = project.gradle.gradleUserHomeDir

    val intermediatesDirectory: Provider<Directory>
        get() = project.layout.buildDirectory.dir(SdkConstants.FD_INTERMEDIATES)

    fun intermediatesDirectory(path: String): Provider<Directory> =
        project.layout.buildDirectory.dir(SdkConstants.FD_INTERMEDIATES).map {
            it.dir(path)
        }

    fun intermediatesFile(path: String): Provider<RegularFile> =
        project.layout.buildDirectory.dir(SdkConstants.FD_INTERMEDIATES).map {
            it.file(path)
        }

    @Deprecated("DO NOT USE - Only use the new Gradle Property objects")
    fun createTestResources(value: String): TextResource = project.resources.text.fromString(value)

    fun hasPlugin(plugin: String): Boolean = project.plugins.hasPlugin(plugin)

    @Deprecated("Use buildDirectory instead")
    fun getBuildDir(): File {
        return project.buildDir
    }

    fun getTestResultsFolder(): File? {
        return File(getBuildDir(), "test-results")
    }

    fun getReportsDir(): File {
        return File(getBuildDir(), BuilderConstants.FD_REPORTS)
    }

    fun getTestReportFolder(): File? {
        return File(getBuildDir(), "reports/tests")
    }

    @Deprecated("Use the version that returns a provider")
    fun getIntermediatesDir(): File {
        return File(getBuildDir(), SdkConstants.FD_INTERMEDIATES)
    }

    fun getTmpFolder(): File {
        return File(getIntermediatesDir(), "tmp")
    }

    fun getOutputsDir(): File {
        return File(getBuildDir(), SdkConstants.FD_OUTPUTS)
    }

    fun getJacocoAgentOutputDirectory(): File {
        return File(getIntermediatesDir(), "jacoco")
    }

    fun getJacocoAgent(): File {
        return File(getJacocoAgentOutputDirectory(), "jacocoagent.jar")
    }

    fun computeBuildMapping(): BuildMapping = project.gradle.computeBuildMapping()
}