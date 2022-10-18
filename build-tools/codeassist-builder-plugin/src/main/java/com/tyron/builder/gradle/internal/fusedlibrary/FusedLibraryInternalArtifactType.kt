package com.tyron.builder.gradle.internal.fusedlibrary

import com.tyron.builder.api.artifact.Artifact
import com.tyron.builder.api.artifact.ArtifactKind
import org.gradle.api.file.Directory
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.RegularFile

@Suppress("ClassName")
sealed class
FusedLibraryInternalArtifactType<T : FileSystemLocation>(
    kind: ArtifactKind<T>,
    category: Category = Category.INTERMEDIATES,
) : Artifact.Single<T>(kind, category) {

    // Directory of classes for use in the fused library.
    object CLASSES_WITH_REWRITTEN_R_CLASS_REFS: FusedLibraryInternalArtifactType<Directory>(ArtifactKind.DIRECTORY), Replaceable
    object MERGED_CLASSES: FusedLibraryInternalArtifactType<Directory>(ArtifactKind.DIRECTORY), Replaceable
    // Directory containing merged resources from all libraries and their dependencies.
    object MERGED_RES: FusedLibraryInternalArtifactType<Directory>(ArtifactKind.DIRECTORY), Replaceable
    // Directory containing blame log of fused library manifest merging
    object MERGED_RES_BLAME_LOG: FusedLibraryInternalArtifactType<Directory>(ArtifactKind.DIRECTORY), Replaceable
    object CLASSES_JAR: FusedLibraryInternalArtifactType<RegularFile>(ArtifactKind.FILE), Replaceable
    object BUNDLED_LIBRARY: FusedLibraryInternalArtifactType<RegularFile>(ArtifactKind.FILE), Replaceable
    // R Class containing all Android resource symbols from libraries contained in a fused library.
    object FUSED_R_CLASS : FusedLibraryInternalArtifactType<RegularFile>(FILE), Replaceable
    object INCREMENTAL_MERGED_RES : FusedLibraryInternalArtifactType<Directory>(ArtifactKind.DIRECTORY), Replaceable
    object MERGED_MANIFEST: FusedLibraryInternalArtifactType<RegularFile>(FILE), Replaceable
    object MANIFEST_MERGE_REPORT: FusedLibraryInternalArtifactType<RegularFile>(FILE), Replaceable
    // Republished artifacts
    object MERGED_AIDL: FusedLibraryInternalArtifactType<Directory>(DIRECTORY), Replaceable
    object MERGED_RENDERSCRIPT_HEADERS: FusedLibraryInternalArtifactType<Directory>(DIRECTORY), Replaceable
    object MERGED_PREFAB_PACKAGE_CONFIGURATION: FusedLibraryInternalArtifactType<Directory>(DIRECTORY), Replaceable
    object MERGED_PREFAB_PACKAGE: FusedLibraryInternalArtifactType<Directory>(DIRECTORY), Replaceable
    object MERGED_ASSETS: FusedLibraryInternalArtifactType<Directory>(DIRECTORY), Replaceable
    object MERGED_JNI: FusedLibraryInternalArtifactType<Directory>(DIRECTORY), Replaceable
    object MERGED_NAVIGATION_JSON: FusedLibraryInternalArtifactType<RegularFile>(FILE), Replaceable
    object MERGED_AAR_METADATA: FusedLibraryInternalArtifactType<RegularFile>(FILE), Replaceable
    object MERGED_JAVA_RES: FusedLibraryInternalArtifactType<RegularFile>(FILE), Replaceable
}
