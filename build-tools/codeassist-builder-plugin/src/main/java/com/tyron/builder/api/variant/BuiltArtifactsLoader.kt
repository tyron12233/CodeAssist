package com.tyron.builder.api.variant

import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection

/**
 * Facility to load [BuiltArtifacts] instances from metadata files in the file system.
 *
 * When an artifact can have more than one output file, its [com.android.build.api.artifact.SingleArtifact] is annotated with
 * [com.android.build.api.artifact.SingleArtifact.ContainsMany]. [com.android.build.api.artifact.ArtifactTypes.APK] is an example
 * of such an artifact: the folder produced can contain multiple APK files when multi-apk build
 * is configured.
 *
 * To be able to understand the content of such a folder, and in particular to know which file is
 * associated with which [VariantFilter], a metadata file is stored along side the artifact files.
 *
 * [BuiltArtifactsLoader] will allow to load the content of the metadata file is obtained information
 * about each file produced by AGP.
 *
 * This interface should not be implemented by third party.
 *
 * Implementation of this interface can be obtained from
 * [com.android.build.api.artifact.Artifacts.getBuiltArtifactsLoader]
 */
interface BuiltArtifactsLoader {

    /**
     * Loads a metadata file from the provided [folder] and returns a new [BuiltArtifacts]
     * containing the [Collection] of [BuiltArtifact] that are present in the [folder]
     *
     * @param folder the directory abstraction that should contain built artifacts and associated
     * metadata file saved using the [BuiltArtifacts.save] methods.
     */
    fun load(folder: Directory): BuiltArtifacts?

    /**
     * Loads a metadata file from the provided [FileCollection] and returns a new [BuiltArtifacts]
     * containing the [Collection] of [BuiltArtifact] that are present in the [FileCollection]
     *
     * @param fileCollection the file collection that should contain built artifacts and associated
     * metadata file saved using the [BuiltArtifacts.save] methods.
     */
    fun load(fileCollection: FileCollection): BuiltArtifacts?
}