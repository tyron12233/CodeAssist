package com.tyron.builder.gradle.internal.privaysandboxsdk

import com.tyron.builder.api.artifact.Artifact
import com.tyron.builder.api.artifact.ArtifactKind
import org.gradle.api.file.Directory
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.RegularFile

@Suppress("ClassName")
sealed class
PrivacySandboxSdkInternalArtifactType<T : FileSystemLocation>(
    kind: ArtifactKind<T>,
    category: Category = Category.INTERMEDIATES,
) : Artifact.Single<T>(kind, category) {

    // generated manifest file that contains permissions to be automatically added to the sandbox.
    object SANDBOX_MANIFEST: PrivacySandboxSdkInternalArtifactType<RegularFile>(ArtifactKind.FILE), Replaceable

    // final .asb file ready to be uploaded to Play Store
    object ASB: PrivacySandboxSdkInternalArtifactType<RegularFile>(ArtifactKind.FILE, category = Category.OUTPUTS), Replaceable
    // Locally derived Android Sdk Archive file for local testing
    object ASAR: PrivacySandboxSdkInternalArtifactType<RegularFile>(ArtifactKind.FILE, category = Category.OUTPUTS), Replaceable

    object LINKED_MERGE_RES_FOR_ASB: PrivacySandboxSdkInternalArtifactType<RegularFile>(ArtifactKind.FILE), Replaceable
    object MODULE_BUNDLE: PrivacySandboxSdkInternalArtifactType<RegularFile>(ArtifactKind.FILE), Replaceable
    object APP_METADATA: PrivacySandboxSdkInternalArtifactType<RegularFile>(FILE), Replaceable

    // Directory containing merged resources from all libraries and their dependencies.
    object MERGED_RES: PrivacySandboxSdkInternalArtifactType<Directory>(ArtifactKind.DIRECTORY), Replaceable

    // Directory containing blame log of fused library manifest merging
    object MERGED_RES_BLAME_LOG: PrivacySandboxSdkInternalArtifactType<Directory>(ArtifactKind.DIRECTORY), Replaceable

    object INCREMENTAL_MERGED_RES: PrivacySandboxSdkInternalArtifactType<Directory>(ArtifactKind.DIRECTORY), Replaceable
    object STUB_JAR: PrivacySandboxSdkInternalArtifactType<RegularFile>(FILE), Replaceable
    object DEX_ARCHIVE: PrivacySandboxSdkInternalArtifactType<Directory>(ArtifactKind.DIRECTORY), Replaceable
    object DEX_ARCHIVE_INPUT_JAR_HASHES: PrivacySandboxSdkInternalArtifactType<RegularFile>(ArtifactKind.FILE), Replaceable

    object DESUGAR_GRAPH: PrivacySandboxSdkInternalArtifactType<Directory>(ArtifactKind.DIRECTORY), Replaceable

    object DEX: PrivacySandboxSdkInternalArtifactType<Directory>(ArtifactKind.DIRECTORY), Replaceable
}
