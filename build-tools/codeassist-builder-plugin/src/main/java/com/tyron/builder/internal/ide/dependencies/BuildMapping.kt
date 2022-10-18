package com.tyron.builder.internal.ide.dependencies

import com.google.common.collect.ImmutableMap
import org.gradle.api.artifacts.component.BuildIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.invocation.Gradle

/**
 * Build mapping between the gradle build name (as used by Gradle internally) and the path of the
 * gradle build.
 *
 * This is used for composite builds that include sub-builds.
 */
typealias BuildMapping = ImmutableMap<String, String>

private const val CURRENT_BUILD_NAME = "__current_build__"
// For Gradle source dependencies we cannot get a name, so use this one.
const val UNKNOWN_BUILD_NAME = "__unknown__"

fun ProjectComponentIdentifier.getBuildId(
    mapping: BuildMapping
): String? {
    return mapping[if (build.isCurrentBuild)
        CURRENT_BUILD_NAME
    else
        build.name]
}

fun ProjectComponentIdentifier.getIdString(): String {
    return projectPath.apply {
        (build as BuildIdentifier?)?.let { plus(":${it.name}") }
    }
}

fun Gradle.computeBuildMapping(): BuildMapping {
    val builder = ImmutableMap.builder<String, String>()

    // Get the root dir for current build.
    // This is necessary to handle the case when dependency comes from the same build with consumer,
    // i.e. when BuildIdentifier.isCurrentBuild returns true. In that case, BuildIdentifier.getName
    // returns ":" instead of the actual build name.
    val currentBuildPath = rootProject.projectDir.absolutePath
    builder.put(CURRENT_BUILD_NAME, currentBuildPath)

    var rootGradleProject: Gradle? = this
    // first, ensure we are starting from the root Gradle object.

    while (rootGradleProject!!.parent != null) {
        rootGradleProject = rootGradleProject.parent
    }

    // get the root dir for the top project if different from current project.
    if (rootGradleProject !== this) {
        builder.put(
            rootGradleProject.rootProject.name,
            rootGradleProject.rootProject.projectDir.absolutePath
        )
    }

    for (includedBuild in rootGradleProject.includedBuilds) {
        val includedBuildPath = includedBuild.projectDir.absolutePath
        // current build has been added with key CURRENT_BUIlD_NAME, avoid redundant entry.
        if (includedBuildPath != currentBuildPath) {
            builder.put(includedBuild.name, includedBuildPath)
        }
    }

    return builder.build()
}

val BuildMapping.currentBuild: String? get() = this[CURRENT_BUILD_NAME]