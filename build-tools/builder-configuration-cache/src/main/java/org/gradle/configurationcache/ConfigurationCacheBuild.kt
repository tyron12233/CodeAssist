package org.gradle.configurationcache

import org.gradle.api.internal.BuildDefinition
import org.gradle.api.internal.GradleInternal
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.internal.build.CompositeBuildParticipantBuildState
import org.gradle.internal.build.IncludedBuildState
import java.io.File


interface ConfigurationCacheBuild {

    val gradle: GradleInternal

    val state: CompositeBuildParticipantBuildState

    fun createProject(path: String, dir: File, buildDir: File)

    fun getProject(path: String): ProjectInternal

    fun registerProjects()

    fun addIncludedBuild(buildDefinition: BuildDefinition): IncludedBuildState
}
