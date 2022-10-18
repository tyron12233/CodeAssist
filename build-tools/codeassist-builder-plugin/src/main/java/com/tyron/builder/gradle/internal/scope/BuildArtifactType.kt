package com.tyron.builder.gradle.internal.scope

import com.tyron.builder.api.artifact.Artifact
import com.tyron.builder.api.artifact.ArtifactKind
import org.gradle.api.Incubating
import org.gradle.api.file.Directory

/**
 * Artifact type use for transform
 *
 *
 * This interface is not currently usable. It is a work in progress.
 */
@Incubating
sealed class BuildArtifactType : Artifact.Single<Directory>(
    ArtifactKind.DIRECTORY,
    Category.INTERMEDIATES
) {
    @Incubating
    object JAVAC_CLASSES : BuildArtifactType()
    @Incubating
    object JAVA_COMPILE_CLASSPATH: BuildArtifactType()
}