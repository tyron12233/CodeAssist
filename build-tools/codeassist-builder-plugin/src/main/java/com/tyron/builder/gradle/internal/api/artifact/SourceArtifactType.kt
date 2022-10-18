package com.tyron.builder.gradle.internal.api.artifact

import com.tyron.builder.api.artifact.Artifact
import com.tyron.builder.api.artifact.ArtifactKind
import org.gradle.api.Incubating;
import org.gradle.api.file.Directory

/** [Artifact] for source set. */
@Incubating
sealed class SourceArtifactType: Artifact.Single<Directory>(
    ArtifactKind.DIRECTORY,
    Category.SOURCES
) {
    object JAVA_SOURCES : SourceArtifactType()
    object KOTLIN_SOURCES : SourceArtifactType()
    object JAVA_RESOURCES : SourceArtifactType()
    object ASSETS : SourceArtifactType()
    object ANDROID_RESOURCES : SourceArtifactType()
    object AIDL : SourceArtifactType()
    object RENDERSCRIPT : SourceArtifactType()
    object JNI : SourceArtifactType()
    object JNI_LIBS : SourceArtifactType()
    object SHADERS : SourceArtifactType()
    object ML_MODELS : SourceArtifactType()
    object CUSTOMS: SourceArtifactType()
}